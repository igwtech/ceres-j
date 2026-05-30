"""Unit tests for orchestrator.controller.

Uses the ``frida`` stub installed in conftest.py — no live agent.
Verifies:

* The controller resolves the device via the documented Frida API.
* It pushes the preamble + agent source into the script source.
* Callbacks registered via ``on_event`` receive ``send`` payloads
  and ignore ``error`` envelopes.
* ``call`` dispatches to ``script.exports_sync.<method>``.
* Lifecycle: ``disconnect`` is idempotent and safe after a partial
  connect failure.
"""

from pathlib import Path

import pytest

from orchestrator.controller import AgentController, AgentLoadError


pytestmark = pytest.mark.unit


@pytest.fixture
def agent_file(tmp_path: Path) -> Path:
    f = tmp_path / "_agent.js"
    f.write_text("// dummy agent\nsend({ev:'ready'});\n",
                 encoding="utf-8")
    return f


def _make_ctrl(agent_file: Path, **kw) -> AgentController:
    return AgentController(
        host=kw.get("host", "127.0.0.1"),
        port=kw.get("port", 27042),
        agent_path=agent_file,
        hook_table=kw.get("hook_table", [
            {"name": "udp_cipher_a",
             "offset": 0x00160090,
             "purpose": "UDP cipher A"},
        ]),
    )


class TestConstruction:
    def test_rejects_missing_agent(self, tmp_path: Path):
        with pytest.raises(FileNotFoundError):
            AgentController(agent_path=tmp_path / "nope.js")

    def test_constructs_clean(self, agent_file: Path):
        ctrl = _make_ctrl(agent_file)
        # No connection attempted yet.
        assert ctrl.call.__name__ == "call"  # smoke


class TestConnect:
    def test_connects_to_device_at_given_host_port(
            self, agent_file: Path, stub_frida):
        ctrl = _make_ctrl(agent_file, host="10.0.0.42", port=42424)
        ctrl.connect()
        assert stub_frida.added == ["10.0.0.42:42424"]
        # In Frida 17.x we enumerate processes and attach to the
        # gadget host's PID. The stub seeds one fake process with
        # pid=1234 named 'Gadget'.
        assert stub_frida.device.attached_targets == [1234]
        ctrl.disconnect()

    def test_pushes_hook_table_as_preamble(
            self, agent_file: Path, stub_frida):
        hook = [{"name": "udp_cipher_a",
                 "offset": 0x160090, "purpose": "x"}]
        ctrl = _make_ctrl(agent_file, hook_table=hook)
        ctrl.connect()
        script = stub_frida.device.session.last_script
        assert script is not None
        assert "__FRIDA_RE_HOOKS__" in script.source
        # The hook offset survives JSON round-trip.
        assert "160090" in script.source or "1442000" in script.source \
            or '"offset": 1441936' in script.source \
            or '"offset":1441936' in script.source
        assert "// dummy agent" in script.source
        assert script.loaded is True
        ctrl.disconnect()

    def test_idempotency_guard(self, agent_file: Path, stub_frida):
        ctrl = _make_ctrl(agent_file)
        ctrl.connect()
        with pytest.raises(RuntimeError, match="already connected"):
            ctrl.connect()
        ctrl.disconnect()

    def test_load_failure_wrapped(self, agent_file: Path, stub_frida,
                                  monkeypatch):
        ctrl = _make_ctrl(agent_file)

        # Make the stub script raise from load().
        real_create = stub_frida.device.session.create_script

        def broken_create(source: str):
            script = real_create(source)
            script.load = lambda: (_ for _ in ()).throw(
                RuntimeError("syntax error at line 5"))
            return script

        stub_frida.device.session.create_script = broken_create  # type: ignore[assignment]
        with pytest.raises(AgentLoadError, match="syntax error"):
            ctrl.connect()


class TestOnEvent:
    def test_send_payload_reaches_callback(
            self, agent_file: Path, stub_frida):
        received: list = []

        def sink(payload, data):
            received.append((payload, data))

        ctrl = _make_ctrl(agent_file)
        ctrl.on_event(sink)
        ctrl.connect()
        script = stub_frida.device.session.last_script

        script.inject_message(
            {"type": "send", "payload": {"ev": "cipher_enter",
                                         "seed_lo": 1, "seed_hi": 2}})
        assert len(received) == 1
        assert received[0][0]["ev"] == "cipher_enter"
        ctrl.disconnect()

    def test_error_envelope_skipped(self, agent_file: Path, stub_frida):
        received: list = []
        ctrl = _make_ctrl(agent_file)
        ctrl.on_event(lambda p, d: received.append(p))
        ctrl.connect()
        script = stub_frida.device.session.last_script
        script.inject_message(
            {"type": "error", "description": "stack overflow"})
        assert received == []
        ctrl.disconnect()

    def test_non_dict_payload_skipped(
            self, agent_file: Path, stub_frida):
        received: list = []
        ctrl = _make_ctrl(agent_file)
        ctrl.on_event(lambda p, d: received.append(p))
        ctrl.connect()
        script = stub_frida.device.session.last_script
        script.inject_message({"type": "send", "payload": "not-a-dict"})
        assert received == []
        ctrl.disconnect()

    def test_multiple_callbacks_all_fire(
            self, agent_file: Path, stub_frida):
        a: list = []
        b: list = []
        ctrl = _make_ctrl(agent_file)
        ctrl.on_event(lambda p, d: a.append(p))
        ctrl.on_event(lambda p, d: b.append(p))
        ctrl.connect()
        script = stub_frida.device.session.last_script
        script.inject_message(
            {"type": "send", "payload": {"ev": "ready"}})
        assert len(a) == len(b) == 1
        ctrl.disconnect()

    def test_callback_exception_isolated(
            self, agent_file: Path, stub_frida):
        ok: list = []
        ctrl = _make_ctrl(agent_file)
        ctrl.on_event(lambda p, d: (_ for _ in ()).throw(
            RuntimeError("boom")))
        ctrl.on_event(lambda p, d: ok.append(p))
        ctrl.connect()
        script = stub_frida.device.session.last_script
        # The first sink raises; the second must still be invoked
        # and the message dispatch must not propagate.
        script.inject_message(
            {"type": "send", "payload": {"ev": "ready"}})
        assert len(ok) == 1
        ctrl.disconnect()

    def test_non_callable_callback_rejected(
            self, agent_file: Path):
        ctrl = _make_ctrl(agent_file)
        with pytest.raises(TypeError):
            ctrl.on_event(42)  # type: ignore[arg-type]


class TestRpcCall:
    def test_dispatches_to_exports_sync(
            self, agent_file: Path, stub_frida):
        ctrl = _make_ctrl(agent_file)
        ctrl.connect()
        script = stub_frida.device.session.last_script

        def fake_get_status():
            return {"module": "NeocronClient.exe",
                    "base": "0x00400000"}

        script.exports_sync.getStatus = fake_get_status
        result = ctrl.call("getStatus")
        assert result["base"] == "0x00400000"
        ctrl.disconnect()

    def test_passes_args(self, agent_file: Path, stub_frida):
        ctrl = _make_ctrl(agent_file)
        ctrl.connect()
        script = stub_frida.device.session.last_script

        recorded: list = []

        def fake_read_mem(addr, n):
            recorded.append((addr, n))
            return [0xfe, 0x0a, 0x00]

        script.exports_sync.readMem = fake_read_mem
        result = ctrl.call("readMem", 0x00400000, 3)
        assert recorded == [(0x00400000, 3)]
        assert result == [0xfe, 0x0a, 0x00]
        ctrl.disconnect()

    def test_unknown_method_raises(
            self, agent_file: Path, stub_frida):
        ctrl = _make_ctrl(agent_file)
        ctrl.connect()
        with pytest.raises(AttributeError, match="exported method"):
            ctrl.call("nonexistent")
        ctrl.disconnect()

    def test_call_before_connect_raises(self, agent_file: Path):
        ctrl = _make_ctrl(agent_file)
        with pytest.raises(RuntimeError, match="not connected"):
            ctrl.call("getStatus")


class TestDisconnect:
    def test_safe_to_call_without_connect(self, agent_file: Path):
        ctrl = _make_ctrl(agent_file)
        ctrl.disconnect()  # no-op

    def test_idempotent(self, agent_file: Path, stub_frida):
        ctrl = _make_ctrl(agent_file)
        ctrl.connect()
        ctrl.disconnect()
        ctrl.disconnect()  # second call must not raise

    def test_context_manager(self, agent_file: Path, stub_frida):
        with _make_ctrl(agent_file) as ctrl:
            script = stub_frida.device.session.last_script
            assert script.loaded
        assert stub_frida.device.session.last_script.unloaded
        assert stub_frida.device.session.detached
