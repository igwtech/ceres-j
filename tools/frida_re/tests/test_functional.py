"""Functional / e2e test for the orchestrator pipeline.

End-to-end with a mocked Frida session: the test injects synthetic
agent events into a controller, the controller forwards them to a
recorder, the recorder persists them as JSONL — the same code paths
the real run would use, minus the actual Wine process.

Marked ``functional`` so CI can run unit tests alone if desired.
"""

import json
import sys
from pathlib import Path

import pytest

from orchestrator.controller import AgentController
from orchestrator.decode import decode_event
from orchestrator.recorder import JsonlRecorder


pytestmark = pytest.mark.functional


@pytest.fixture
def real_agent_path() -> Path:
    """Use the actual agent JS file shipped in the repo. This validates
    the file is at the expected path AND keeps the test honest — if
    the JS file disappears or the path drifts, this fails loudly."""
    p = Path(__file__).resolve().parent.parent / "agent" / "_agent.js"
    if not p.is_file():
        pytest.fail(f"agent file missing: {p}")
    return p


def _ready() -> dict:
    return {
        "type": "send",
        "payload": {
            "ev": "ready",
            "module": "NeocronClient.exe",
            "base": "0x00400000",
            "hooks": [
                {"name": "udp_cipher_a", "addr": "0x00560090"},
                {"name": "udp_cipher_b", "addr": "0x0055ff30"},
            ],
        },
    }


def _cipher_enter(seed_lo: int, seed_hi: int,
                  hex_body: str = "fe 0a 00 03 1f 01 00 25") -> dict:
    return {
        "type": "send",
        "payload": {
            "ev": "cipher_enter",
            "hook": "udp_cipher_a",
            "tid": 1234,
            "ts": 1716_900_000_000_000_000,
            "seed_lo": seed_lo,
            "seed_hi": seed_hi,
            "buf_hex": hex_body,
            "buf_len": len(hex_body.split()),
        },
    }


def _cipher_leave(seed_lo: int, seed_hi: int,
                  hex_body: str = "11 22 33 44 55 66 77 88") -> dict:
    return {
        "type": "send",
        "payload": {
            "ev": "cipher_leave",
            "hook": "udp_cipher_a",
            "tid": 1234,
            "ts": 1716_900_000_000_000_001,
            "seed_lo": seed_lo,
            "seed_hi": seed_hi,
            "buf_hex": hex_body,
            "buf_len": len(hex_body.split()),
        },
    }


class TestEndToEnd:
    def test_synthetic_session_recorded_to_jsonl(
            self, stub_frida, tmp_path: Path, real_agent_path: Path):
        trace = tmp_path / "trace.jsonl"
        recorder = JsonlRecorder(path=trace, stdout=None)

        def sink(payload, _data):
            recorder.record(decode_event(payload))

        ctrl = AgentController(
            host="127.0.0.1", port=27042,
            agent_path=real_agent_path,
            hook_table=[
                {"name": "udp_cipher_a",
                 "offset": 0x00160090,
                 "purpose": "UDP cipher A"},
            ],
        )
        ctrl.on_event(sink)
        ctrl.connect()
        script = stub_frida.device.session.last_script
        assert script is not None

        # Simulate the agent's bootstrap + a captured cipher round-trip.
        script.inject_message(_ready())
        script.inject_message(_cipher_enter(0x12, 0x34))
        script.inject_message(_cipher_leave(0x12, 0x34))
        # An error envelope must NOT land in JSONL.
        script.inject_message({"type": "error",
                               "description": "transient OS fault"})
        # A second packet to make sure the recorder isn't single-shot.
        script.inject_message(_cipher_enter(0xab, 0xcd,
                                            "ff ee dd cc"))
        script.inject_message(_cipher_leave(0xab, 0xcd,
                                            "00 11 22 33"))

        ctrl.disconnect()
        recorder.close()

        lines = trace.read_text().splitlines()
        assert len(lines) == 5  # ready + 2× enter + 2× leave
        events = [json.loads(line) for line in lines]

        assert events[0]["ev"] == "ready"
        assert events[1]["ev"] == "cipher_enter"
        assert events[1]["seed"] == 0x3412
        assert events[1]["buf_hex"] == "fe0a00031f010025"
        assert events[2]["ev"] == "cipher_leave"
        assert events[2]["seed"] == 0x3412
        assert events[3]["ev"] == "cipher_enter"
        assert events[3]["seed"] == 0xcdab
        assert events[4]["ev"] == "cipher_leave"
        assert events[4]["seed"] == 0xcdab

    def test_pretty_stdout_independent_of_jsonl(
            self, stub_frida, tmp_path: Path, real_agent_path: Path,
            capsys):
        recorder = JsonlRecorder(path=None, stdout=sys.stdout)
        ctrl = AgentController(
            host="127.0.0.1", port=27042,
            agent_path=real_agent_path, hook_table=[])
        ctrl.on_event(
            lambda p, d: recorder.record(decode_event(p)))
        ctrl.connect()
        script = stub_frida.device.session.last_script
        script.inject_message(_cipher_enter(0x01, 0x02))
        ctrl.disconnect()
        recorder.close()
        out = capsys.readouterr().out
        assert "cipher_enter" in out
        assert "0x0201" in out
        assert recorder.n_events == 1

    def test_headless_run_exits_after_duration(
            self, stub_frida, tmp_path: Path, real_agent_path: Path,
            monkeypatch, capsys):
        """``--duration N`` runs the orchestrator for N seconds then
        exits cleanly without calling input(). Regression guard so
        we don't accidentally re-enter the REPL in headless mode."""
        import orchestrator.__main__ as main_mod

        trace = tmp_path / "trace.jsonl"
        # interactive_loop must NOT be called in headless mode.
        called: list = []
        monkeypatch.setattr(main_mod, "interactive_loop",
                            lambda c: called.append("REPL"))
        rc = main_mod.main([
            "--host", "127.0.0.1", "--port", "27042",
            "--agent", str(real_agent_path),
            "--trace", str(trace),
            "--no-stdout",
            "--duration", "0.05",  # 50ms — fast for CI
        ])
        assert rc == 0
        assert called == []        # REPL was bypassed
        # Trace file exists (recorder opened+closed even if empty).
        assert trace.exists()

    def test_recorder_survives_decode_failure(
            self, stub_frida, tmp_path: Path, real_agent_path: Path):
        """A single bad event must not break the stream — the
        orchestrator's main()'s sink already wraps decode_event in
        try/except so that future unknown events don't kill recording.
        Replicating that pattern here as a regression guard."""
        trace = tmp_path / "trace.jsonl"
        recorder = JsonlRecorder(path=trace, stdout=None)

        def sink(payload, _data):
            try:
                recorder.record(decode_event(payload))
            except Exception:
                pass

        ctrl = AgentController(
            host="127.0.0.1", port=27042,
            agent_path=real_agent_path, hook_table=[])
        ctrl.on_event(sink)
        ctrl.connect()
        script = stub_frida.device.session.last_script

        # Malformed event (missing 'ev').
        script.inject_message({"type": "send",
                               "payload": {"hook": "nope"}})
        # Good event after malformed.
        script.inject_message(_cipher_enter(0x99, 0x88))
        ctrl.disconnect()
        recorder.close()

        lines = trace.read_text().splitlines()
        assert len(lines) == 1  # only the good event made it
        evt = json.loads(lines[0])
        assert evt["ev"] == "cipher_enter"
        assert evt["seed"] == 0x8899


# ---------------------------------------------------------------------
# v0.8.0 — consolidated agent JS contract
# ---------------------------------------------------------------------


class TestAgentJsContract:
    """The orchestrator/__main__.py REPL dispatches `capture`, `vk`,
    `dik`, `ri` etc. as RPC calls into the agent. If we silently rename
    or drop one of those RPC methods in the agent JS we'd only catch it
    against a live Wine process. This test pins the API surface from
    the JS side, AND verifies the JS file parses with Node.

    The v0.8.0 consolidation also introduced a `_captureOneDevice`
    module-level helper and a candidate-array attached to globalThis
    by the CreateDevice hook — pin those too so future refactors
    don't quietly break the multi-candidate capture path.
    """

    def test_agent_js_parses(self, real_agent_path: Path):
        import shutil
        import subprocess
        node = shutil.which("node")
        if node is None:
            pytest.skip("node not available")
        result = subprocess.run(
            [node, "--check", str(real_agent_path)],
            capture_output=True, text=True, timeout=15)
        assert result.returncode == 0, (
            f"node --check failed:\n{result.stderr}")

    def test_rpc_exports_expected_methods(self,
                                          real_agent_path: Path):
        src = real_agent_path.read_text()
        # Each method below is dispatched by orchestrator/__main__.py's
        # _dispatch_command. Missing one would crash the REPL at
        # runtime against a real gadget.
        expected = ["captureD3D9", "riPress", "vkPress", "vkClear",
                    "dikPress", "dikClear", "getStatus", "readMem",
                    "writeMem", "callFunction", "sendUdp"]
        for name in expected:
            assert f"{name}(" in src or f"{name}:" in src, (
                f"agent RPC method {name!r} missing from JS")

    def test_d3d9_candidate_path_intact(self, real_agent_path: Path):
        src = real_agent_path.read_text()
        # The CreateDevice hook must save multiple candidates because
        # the OUT-pointer arg index varies across DXVK builds.
        assert "__D3D9_DEVICE_CANDIDATES" in src
        # The capture path iterates candidates via _captureOneDevice.
        assert "_captureOneDevice" in src
        # And emits a d3d9_frame event with raw pixel bytes.
        assert "'d3d9_frame'" in src or '"d3d9_frame"' in src

    def test_connect_hook_registered(self, real_agent_path: Path):
        """v0.8.1: ws2_32!connect hook registered + emits tcp_connect
        with port/ip parsed from sockaddr_in. Caught at IAT scan
        (#263) — needed so we can attribute tcp_send hits to a
        specific destination (game server vs github phone-home)."""
        src = real_agent_path.read_text()
        assert "function connectHandler" in src
        assert "tcp_connect" in src
        # AF_INET decode path must read port as big-endian → host order.
        assert "fam === 2" in src
        # The handler must be wired into HOOK_FACTORIES so installHooks
        # actually attaches it.
        assert "tcp_connect: connectHandler" in src

    def test_connect_symbol_registered(self):
        """Symbol entry for tcp_connect must exist + be implemented."""
        from orchestrator.symbols import HOOK_TABLE
        assert "tcp_connect" in HOOK_TABLE
        spec = HOOK_TABLE["tcp_connect"]
        assert spec.implemented is True
        assert spec.export_mod == "ws2_32.dll"
        assert spec.export_sym == "connect"
