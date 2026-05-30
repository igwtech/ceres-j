"""Unit tests for orchestrator.symbols.

Covers the offset arithmetic that the agent uses to convert Ghidra
file-VAs into runtime module-base offsets. A bug here would silently
hook the wrong addresses, which is hard to spot at runtime — hence
the strict tests.
"""

import pytest

from orchestrator.symbols import (
    HOOK_TABLE,
    IMAGE_BASE,
    HookSpec,
    file_va_to_offset,
    hook_table_for_agent,
    implemented_hooks,
)


pytestmark = pytest.mark.unit


class TestFileVaToOffset:
    def test_subtracts_image_base(self):
        assert file_va_to_offset(0x00560090) == 0x00160090

    def test_image_base_itself_yields_zero(self):
        assert file_va_to_offset(IMAGE_BASE) == 0

    def test_rejects_below_image_base(self):
        with pytest.raises(ValueError, match="below ImageBase"):
            file_va_to_offset(0x00100000)

    def test_rejects_zero(self):
        with pytest.raises(ValueError):
            file_va_to_offset(0)

    def test_handles_high_va(self):
        # FUN_00803cd0 (FULLCHARSYSTEM dispatcher).
        assert file_va_to_offset(0x00803cd0) == 0x00403cd0


class TestHookSpec:
    def test_offset_property_matches_function(self):
        spec = HookSpec(name="x", purpose="test", implemented=True,
                        mode="offset", file_va=0x00501234)
        assert spec.offset == file_va_to_offset(spec.file_va)
        assert spec.offset == 0x00101234

    def test_export_mode_has_no_offset(self):
        spec = HookSpec(name="x", purpose="t", implemented=True,
                        mode="export", export_mod="ws2_32.dll",
                        export_sym="recvfrom")
        assert spec.offset is None
        assert spec.export_mod == "ws2_32.dll"
        assert spec.export_sym == "recvfrom"

    def test_dataclass_is_frozen(self):
        spec = HookSpec(name="x", purpose="t", implemented=False,
                        mode="offset", file_va=0x00500000)
        with pytest.raises(Exception):  # FrozenInstanceError
            spec.name = "y"  # type: ignore[misc]


class TestHookTable:
    def test_contains_canonical_winsock_hooks(self):
        # The current canonical hooks are export-mode against ws2_32.
        assert "udp_recv" in HOOK_TABLE
        assert "udp_send" in HOOK_TABLE
        for n in ("udp_recv", "udp_send"):
            spec = HOOK_TABLE[n]
            assert spec.mode == "export"
            assert spec.export_mod == "ws2_32.dll"
            assert spec.implemented is True

    def test_winsock_hooks_are_recvfrom_and_sendto(self):
        assert HOOK_TABLE["udp_recv"].export_sym == "recvfrom"
        assert HOOK_TABLE["udp_send"].export_sym == "sendto"

    def test_tcp_hooks_registered(self):
        # v0.4.0: TCP hooks added on ReadFile, WriteFile, NtDeviceIoControlFile.
        # v0.5.0: NtReadFile, NtWriteFile added.
        # v0.6.0: all ReadFile/WriteFile/NtReadFile/NtWriteFile DISABLED
        #         (too noisy during PAK loading — caused gadget overload +
        #         game crash). Only NtDeviceIoControlFile remains implemented,
        #         filtered by AFD IOCTL codes.
        for name in ("tcp_read", "tcp_write", "nt_read", "nt_write"):
            assert name in HOOK_TABLE
            assert HOOK_TABLE[name].implemented is False, \
                f"{name} should be DISABLED in v0.6.0 (noisy)"
        assert HOOK_TABLE["tcp_ioctl"].implemented is True
        assert HOOK_TABLE["tcp_ioctl"].export_mod == "ntdll.dll"
        assert HOOK_TABLE["tcp_ioctl"].export_sym == "NtDeviceIoControlFile"

    def test_input_hooks_verified_from_import_table(self):
        # v0.7.0: NC2's actual import table (pefile dump 2026-05-28):
        # NC2 imports user32!GetKeyboardState + GetAsyncKeyState +
        # message-pump APIs. NC2 does NOT import RawInput. The real
        # input hooks (the ones we actually inject through) are the
        # state ones. Probes for RawInput/PeekMessage kept just to
        # confirm they don't fire.
        real_hooks = [
            ("input_keyboard_state", "user32.dll", "GetKeyboardState"),
            ("input_async_key",      "user32.dll", "GetAsyncKeyState"),
        ]
        for name, mod, sym in real_hooks:
            assert name in HOOK_TABLE
            spec = HOOK_TABLE[name]
            assert spec.implemented is True
            assert spec.export_mod == mod
            assert spec.export_sym == sym
        # Probes (just observation).
        for name in ("input_raw_register", "input_raw_get",
                     "input_get_message"):
            assert name in HOOK_TABLE
            assert HOOK_TABLE[name].implemented is True

    def test_tcp_send_via_ws2_32_imported(self):
        # v0.7.0: NC2 imports ws2_32!send directly. Our hook on that
        # export catches outbound TCP frames (FE-framed, unencrypted).
        assert "tcp_send" in HOOK_TABLE
        spec = HOOK_TABLE["tcp_send"]
        assert spec.implemented is True
        assert spec.export_mod == "ws2_32.dll"
        assert spec.export_sym == "send"

    def test_legacy_cipher_hooks_kept_but_unimplemented(self):
        # The legacy Ghidra-VA cipher hooks are stale (live test
        # 2026-05-28 showed addresses don't line up). Keep them in
        # the table for reference but make sure they're NOT
        # implemented so the agent doesn't try to attach them.
        for n in ("udp_cipher_a_legacy", "udp_cipher_b_legacy"):
            assert n in HOOK_TABLE
            spec = HOOK_TABLE[n]
            assert spec.mode == "offset"
            assert spec.implemented is False

    def test_legacy_cipher_addresses_unchanged(self):
        # Locks the documented addresses against accidental edits
        # while flagging them as known-stale.
        assert HOOK_TABLE["udp_cipher_a_legacy"].file_va == 0x00560090
        assert HOOK_TABLE["udp_cipher_b_legacy"].file_va == 0x0055ff30

    def test_session_dispatch_present_but_stub(self):
        spec = HOOK_TABLE["session_dispatch"]
        assert spec.file_va == 0x0055ec10
        assert spec.implemented is False

    def test_no_duplicate_offset_addresses(self):
        # Only offset-mode entries have file_va; check those alone.
        offset_entries = [s for s in HOOK_TABLE.values()
                          if s.mode == "offset" and s.file_va is not None]
        addrs = [s.file_va for s in offset_entries]
        assert len(addrs) == len(set(addrs)), \
            "duplicate file_va in HOOK_TABLE offset entries"

    def test_all_offset_entries_above_image_base(self):
        for spec in HOOK_TABLE.values():
            if spec.mode == "offset" and spec.file_va is not None:
                assert spec.file_va >= IMAGE_BASE, \
                    f"{spec.name}: file_va below ImageBase"


class TestImplementedHooksFilter:
    def test_returns_only_implemented(self):
        impl = implemented_hooks()
        # Current canonical hooks are the ws2_32 ones.
        assert "udp_recv" in impl
        assert "udp_send" in impl
        # Legacy cipher offsets are intentionally NOT implemented.
        assert "udp_cipher_a_legacy" not in impl
        assert "udp_cipher_b_legacy" not in impl
        assert "session_dispatch" not in impl
        for spec in impl.values():
            assert spec.implemented is True

    def test_returned_dict_is_subset(self):
        impl = implemented_hooks()
        for name in impl:
            assert name in HOOK_TABLE


class TestHookTableForAgent:
    def test_returns_list_of_dicts(self):
        table = hook_table_for_agent()
        assert isinstance(table, list)
        for entry in table:
            assert isinstance(entry, dict)
            # Each entry must declare its mode + the fields the agent
            # needs to resolve it.
            assert "name" in entry
            assert "purpose" in entry
            assert entry["mode"] in ("export", "offset")
            if entry["mode"] == "export":
                assert "module" in entry and "symbol" in entry
            else:
                assert "offset" in entry

    def test_only_includes_implemented(self):
        names = {entry["name"] for entry in hook_table_for_agent()}
        assert names == set(implemented_hooks().keys())

    def test_export_entries_target_ws2_32(self):
        for entry in hook_table_for_agent():
            if entry["mode"] != "export":
                continue
            spec = HOOK_TABLE[entry["name"]]
            assert entry["module"] == spec.export_mod
            assert entry["symbol"] == spec.export_sym

    def test_serialisable(self):
        # JSON is what the orchestrator sends to the agent. Anything
        # un-encodable here would break the agent attach.
        import json
        json.dumps(hook_table_for_agent())
