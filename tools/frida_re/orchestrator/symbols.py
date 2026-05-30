"""Canonical hook table.

Two hook resolution modes are supported:

* ``offset`` (legacy) — Ghidra file-VA minus ``IMAGE_BASE``. Agent
  computes ``module.base + offset`` at runtime. Brittle in practice
  for NC2 because the documented Ghidra addresses don't line up with
  function entries in the loaded binary (live test 2026-05-28).

* ``export`` (preferred) — module name + exported symbol. Agent does
  ``Process.findModuleByName(mod).findExportByName(sym)``. Stable
  across Wine builds, Frida releases, EXE patches.

The canonical hooks for NC2 protocol RE use **export** mode against
``ws2_32.dll!recvfrom`` (onLeave) and ``ws2_32.dll!sendto`` (onEnter).
That captures every UDP datagram pre- or post-cipher, which the
orchestrator then decrypts with the LFSR+CFB algorithm we already
cracked (``orchestrator.decrypt``).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Literal, Optional


IMAGE_BASE = 0x00400000
"""Legacy linker-default PE ImageBase. Only relevant to ``offset``
mode hooks. NC2's actual ImageBase is ``0x7b0e0000`` (file-true),
but Ghidra notes were authored with this override."""


MODULE_NAME = "NeocronClient.exe"
"""PE module name as it appears in Frida's enumeration."""


HookMode = Literal["offset", "export"]


@dataclass(frozen=True)
class HookSpec:
    """One hook entry.

    Attributes:
        name:        short stable identifier used in event payloads
        purpose:     one-line human description
        implemented: True if agent/_agent.js already attaches it
        mode:        ``"offset"`` (legacy VA) or ``"export"`` (preferred)
        file_va:     for offset mode — Ghidra file-VA (Image-base-relative)
        export_mod:  for export mode — DLL basename (e.g. ``ws2_32.dll``)
        export_sym:  for export mode — symbol name (e.g. ``recvfrom``)
    """
    name: str
    purpose: str
    implemented: bool
    mode: HookMode
    file_va: Optional[int] = None
    export_mod: Optional[str] = None
    export_sym: Optional[str] = None

    @property
    def offset(self) -> Optional[int]:
        if self.mode != "offset" or self.file_va is None:
            return None
        return self.file_va - IMAGE_BASE


def file_va_to_offset(file_va: int) -> int:
    """Convert a Ghidra file-VA to a runtime module-base offset."""
    if file_va < IMAGE_BASE:
        raise ValueError(
            f"file_va 0x{file_va:08x} is below ImageBase "
            f"0x{IMAGE_BASE:08x} — did you pass an offset by mistake?"
        )
    return file_va - IMAGE_BASE


# Canonical hook table. Adding a row + flipping ``implemented=True``
# is the only place a new hook needs to be registered for the agent
# to attach it (the orchestrator JSON-serialises this list and the
# agent reads it from ``globalThis.__FRIDA_RE_HOOKS__``).
HOOK_TABLE: Dict[str, HookSpec] = {
    spec.name: spec
    for spec in [
        # ── Canonical: Winsock wire capture ─────────────────────────
        HookSpec(
            name="udp_recv",
            purpose="ws2_32!recvfrom onLeave — captures encrypted UDP "
                    "datagrams the client just received. Decrypted in "
                    "the orchestrator via orchestrator.decrypt.",
            implemented=True,
            mode="export",
            export_mod="ws2_32.dll",
            export_sym="recvfrom",
        ),
        HookSpec(
            name="udp_send",
            purpose="ws2_32!sendto onEnter — captures encrypted UDP "
                    "datagrams the client is about to send. Decrypted "
                    "in the orchestrator via orchestrator.decrypt.",
            implemented=True,
            mode="export",
            export_mod="ws2_32.dll",
            export_sym="sendto",
        ),
        # v0.7.0: TCP send. NC2's import table shows `send` is imported;
        # NC2 calls it for outbound TCP (zone-cross 0x83/0x0c etc.).
        HookSpec(
            name="tcp_send",
            purpose="ws2_32!send onEnter — captures unencrypted TCP "
                    "frames (FE-framed) being sent. NC2 imports this "
                    "directly per pefile dump 2026-05-28.",
            implemented=True,
            mode="export",
            export_mod="ws2_32.dll",
            export_sym="send",
        ),
        # v0.8.1: TCP connect. NC2 imports `connect` directly. Logging
        # sockaddr_in (port, ip) tells us when the client dials the
        # game server vs the launcher's github phone-home, and gives
        # each subsequent tcp_send a socket FD we can attribute back
        # to a destination.
        HookSpec(
            name="tcp_connect",
            purpose="ws2_32!connect onEnter — log sockaddr (port + "
                    "dotted-quad IP) for each outgoing TCP dial. "
                    "Distinguishes launcher api.github.com TLS from "
                    "game-server gameserver:12000 / infoserver:7000.",
            implemented=True,
            mode="export",
            export_mod="ws2_32.dll",
            export_sym="connect",
        ),
        # ── TCP capture (v0.4.0) ────────────────────────────────────
        # NC2 doesn't use ws2_32 recv/send/WSARecv/WSASend (live-tested
        # 2026-05-28 — 0 hits in 20s of gameplay). It must reach the
        # socket via a deeper API. Hooking three candidates, content-
        # filtered by the 0xfe FE-framing marker NC2 uses for TCP.
        # Disabled in v0.6.0 — these fire millions of times during PAK
        # loading. The fe-prefix filter caught file content (audio,
        # PAK headers, code blocks) as false-positive "TCP frames" and
        # the hook overhead crashed the gadget mid-load. Keep the rows
        # for reference but don't implement.
        HookSpec(
            name="tcp_read",
            purpose="(DISABLED in v0.6.0) kernel32!ReadFile",
            implemented=False,
            mode="export",
            export_mod="kernel32.dll",
            export_sym="ReadFile",
        ),
        HookSpec(
            name="tcp_write",
            purpose="(DISABLED in v0.6.0) kernel32!WriteFile",
            implemented=False,
            mode="export",
            export_mod="kernel32.dll",
            export_sym="WriteFile",
        ),
        HookSpec(
            name="tcp_ioctl",
            purpose="ntdll!NtDeviceIoControlFile — Wine's AFD driver "
                    "IOCTL path; the lowest-level user-mode socket "
                    "I/O. v0.5.0 filters by AFD IOCTL codes (recv "
                    "0x12017/0x12047, send 0x1201f/0x12053) instead "
                    "of by content prefix — the AFD layer wraps the "
                    "application data so a 0xfe-prefix check on the "
                    "raw IOCTL buffer was wrong.",
            implemented=True,
            mode="export",
            export_mod="ntdll.dll",
            export_sym="NtDeviceIoControlFile",
        ),
        HookSpec(
            name="nt_read",
            purpose="(DISABLED in v0.6.0) ntdll!NtReadFile — too noisy "
                    "during PAK loading; caused gadget overload + game "
                    "crash 2026-05-28. NCE 2.5 doesn't use kernel-"
                    "transition reads for TCP anyway.",
            implemented=False,
            mode="export",
            export_mod="ntdll.dll",
            export_sym="NtReadFile",
        ),
        HookSpec(
            name="nt_write",
            purpose="(DISABLED in v0.6.0) ntdll!NtWriteFile",
            implemented=False,
            mode="export",
            export_mod="ntdll.dll",
            export_sym="NtWriteFile",
        ),
        # v0.6.0: input API probes. NC2 imports dinput8 but never
        # calls DirectInput8Create (verified live 2026-05-28).
        # Probe the alternatives — whichever fires during gameplay
        # is the path we hook for input injection.
        HookSpec(
            name="input_raw_register",
            purpose="user32!RegisterRawInputDevices probe — fires if "
                    "NC2 uses RawInput.",
            implemented=True,
            mode="export",
            export_mod="user32.dll",
            export_sym="RegisterRawInputDevices",
        ),
        HookSpec(
            name="input_raw_get",
            purpose="user32!GetRawInputData probe.",
            implemented=True,
            mode="export",
            export_mod="user32.dll",
            export_sym="GetRawInputData",
        ),
        HookSpec(
            name="input_get_message",
            purpose="user32!PeekMessageA probe — fires if NC2 uses "
                    "Win32 message queue (WM_KEYDOWN etc).",
            implemented=True,
            mode="export",
            export_mod="user32.dll",
            export_sym="PeekMessageA",
        ),
        # v0.7.0: THE real input hooks (verified via NC2 import table).
        HookSpec(
            name="input_keyboard_state",
            purpose="user32!GetKeyboardState — modifies the 256-byte "
                    "state buffer on return to inject our keys. NC2 "
                    "calls this for keyboard polling.",
            implemented=True,
            mode="export",
            export_mod="user32.dll",
            export_sym="GetKeyboardState",
        ),
        HookSpec(
            name="input_async_key",
            purpose="user32!GetAsyncKeyState — modifies the return "
                    "value to report our injected keys as pressed.",
            implemented=True,
            mode="export",
            export_mod="user32.dll",
            export_sym="GetAsyncKeyState",
        ),
        # ── Legacy: Ghidra-derived in-EXE addresses. Kept for ──────
        # ── reference but NOT implemented because the documented ───
        # ── addresses don't line up with function entries in the ───
        # ── loaded binary (live test 2026-05-28). ──────────────────
        HookSpec(
            name="udp_cipher_a_legacy",
            purpose="(legacy) UDP cipher — direction A. Documented "
                    "address doesn't line up with function entry; "
                    "use udp_recv / udp_send instead.",
            implemented=False,
            mode="offset",
            file_va=0x00560090,
        ),
        HookSpec(
            name="udp_cipher_b_legacy",
            purpose="(legacy) UDP cipher — direction B.",
            implemented=False,
            mode="offset",
            file_va=0x0055ff30,
        ),
        HookSpec(
            name="session_dispatch",
            purpose="reliable-channel session dispatcher (body byte0 = Type)",
            implemented=False,
            mode="offset",
            file_va=0x0055ec10,
        ),
        HookSpec(
            name="multipart_reassemble",
            purpose="0x03/0x07 multipart fragment reassembler",
            implemented=False,
            mode="offset",
            file_va=0x0055c270,
        ),
        HookSpec(
            name="charsys_tlv_parse",
            purpose="CHARSYS TLV parser (CharInfo body)",
            implemented=False,
            mode="offset",
            file_va=0x008447d0,
        ),
        HookSpec(
            name="fullcharsys_dispatch",
            purpose="FULLCHARSYSTEM event dispatcher (runtime CHARSYS)",
            implemented=False,
            mode="offset",
            file_va=0x00803cd0,
        ),
    ]
}


def implemented_hooks() -> Dict[str, HookSpec]:
    """Return only the hooks the agent currently attaches."""
    return {n: s for n, s in HOOK_TABLE.items() if s.implemented}


def hook_table_for_agent() -> List[dict]:
    """Serializable form pushed to the agent at attach time."""
    out: List[dict] = []
    for s in HOOK_TABLE.values():
        if not s.implemented:
            continue
        if s.mode == "export":
            out.append({
                "name": s.name,
                "mode": "export",
                "module": s.export_mod,
                "symbol": s.export_sym,
                "purpose": s.purpose,
            })
        elif s.mode == "offset":
            out.append({
                "name": s.name,
                "mode": "offset",
                "offset": s.offset,
                "purpose": s.purpose,
            })
        else:
            raise ValueError(f"unknown hook mode: {s.mode}")
    return out
