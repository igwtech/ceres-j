"""Frida attach + agent-script loader + RPC client.

Connects to a frida-gadget.dll running inside the Wine prefix
(listening on a TCP port, default 27042), pushes the agent JS,
and exposes:

* ``connect()`` / ``disconnect()`` lifecycle.
* ``call(method, *args)`` — synchronous RPC into agent's
  ``rpc.exports``.
* ``on_event(callback)`` — register a sink for ``send()`` events
  from the agent. The callback receives a raw dict.

The controller deliberately keeps Frida-specific imports inside
method bodies so the rest of the orchestrator can be imported and
unit-tested without `frida` installed (tests stub the module via
``conftest.py``).
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any, Callable, List, Optional

log = logging.getLogger(__name__)


AgentEventCallback = Callable[[dict, Optional[bytes]], None]
"""Signature for an agent-event listener.

Receives ``(payload, data)`` where ``payload`` is the JSON dict
the agent's ``send()`` emitted and ``data`` is any optional binary
side-channel (used for large hex dumps where embedding in JSON
would be wasteful)."""


class AgentLoadError(RuntimeError):
    """Raised when the agent script fails to load."""


class AgentController:
    """Owns the Frida session + script lifecycle.

    Typical use:

        ctrl = AgentController(
            host="127.0.0.1", port=27042,
            agent_path=Path("agent/_agent.js"))
        ctrl.on_event(my_callback)
        ctrl.connect()
        result = ctrl.call("readMem", 0x00400000, 16)
        ctrl.disconnect()
    """

    def __init__(self, *,
                 host: str = "127.0.0.1",
                 port: int = 27042,
                 agent_path: Path,
                 hook_table: Optional[list[dict]] = None) -> None:
        if not agent_path.is_file():
            raise FileNotFoundError(f"agent script not found: {agent_path}")
        self._host = host
        self._port = port
        self._agent_path = agent_path
        self._hook_table = hook_table or []
        self._callbacks: List[AgentEventCallback] = []

        # Set on connect(); typed loosely to avoid hard dep on frida.
        self._device: Any = None
        self._session: Any = None
        self._script: Any = None

    # ------------------------------------------------------------------
    # Listener registration
    # ------------------------------------------------------------------

    def on_event(self, callback: AgentEventCallback) -> None:
        """Register a callback to receive agent ``send()`` events."""
        if not callable(callback):
            raise TypeError("callback must be callable")
        self._callbacks.append(callback)

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def connect(self) -> None:
        """Connect to the gadget, attach, push the agent script.

        Idempotent: calling twice without ``disconnect()`` raises.
        """
        if self._script is not None:
            raise RuntimeError("AgentController already connected")
        import frida  # local import — orchestrator stays importable
                      # without frida for unit tests.

        log.info("connecting to gadget at %s:%d", self._host, self._port)
        mgr = frida.get_device_manager()
        self._device = mgr.add_remote_device(f"{self._host}:{self._port}")
        # In Frida 16.x, ``attach(0)`` worked because the gadget had a
        # reserved PID-0 slot. In 17.x it raises NotSupportedError
        # ("unable to act on other processes when embedded"). The
        # gadget exposes itself as a single process — enumerate, then
        # attach to that PID. Fall back to attach-by-name 'Gadget' if
        # enumeration returns nothing useful.
        self._session = self._attach_to_gadget()

        source = self._agent_path.read_text(encoding="utf-8")
        # Prepend a tiny preamble injecting the hook table the agent
        # should attach. Keeping it as a JSON literal means the agent
        # script doesn't need its own copy of the canonical table.
        import json
        preamble = (
            "globalThis.__FRIDA_RE_HOOKS__ = "
            + json.dumps(self._hook_table)
            + ";\n"
        )
        self._script = self._session.create_script(preamble + source)
        self._script.on("message", self._on_message)
        try:
            self._script.load()
        except Exception as exc:
            raise AgentLoadError(f"failed to load agent: {exc}") from exc
        log.info("agent loaded (%d hook(s) registered)",
                 len(self._hook_table))

    def _attach_to_gadget(self) -> Any:
        """Attach to the single process the embedded gadget exposes.

        Strategy (in order):
          1. ``device.enumerate_processes()`` — if it returns exactly
             one entry, attach to that PID. That's the embedded-gadget
             host process and it's what we want.
          2. ``device.attach('Gadget')`` — Frida's documented
             attach-by-name shortcut for the embedded gadget.
          3. Re-raise whatever Frida gave us on the last attempt so
             the user sees the real underlying error.
        """
        last_err: Exception | None = None
        try:
            procs = list(self._device.enumerate_processes())
            log.debug("gadget exposes %d process(es): %s",
                      len(procs),
                      [(p.pid, p.name) for p in procs])
            if len(procs) >= 1:
                # If multiple, prefer one named "Gadget" or the first
                # non-zero PID.
                target = None
                for p in procs:
                    if p.name == "Gadget":
                        target = p
                        break
                if target is None:
                    target = procs[0]
                log.info("attaching to gadget pid=%d name=%s",
                         target.pid, target.name)
                return self._device.attach(target.pid)
        except Exception as exc:  # noqa: BLE001 — try fallback
            log.debug("enumerate_processes failed: %s", exc)
            last_err = exc

        try:
            log.info("attaching to gadget by name='Gadget'")
            return self._device.attach("Gadget")
        except Exception as exc:
            if last_err is None:
                last_err = exc
            raise last_err

    def disconnect(self) -> None:
        """Tear down the session. Safe to call multiple times."""
        if self._script is not None:
            try:
                self._script.unload()
            except Exception as exc:
                log.debug("script unload error (ignored): %s", exc)
            self._script = None
        if self._session is not None:
            try:
                self._session.detach()
            except Exception as exc:
                log.debug("session detach error (ignored): %s", exc)
            self._session = None
        self._device = None

    # ------------------------------------------------------------------
    # RPC
    # ------------------------------------------------------------------

    def call(self, method: str, *args: Any) -> Any:
        """Synchronous RPC into ``rpc.exports`` in the agent.

        Frida's Python binding exposes RPC via either
        ``script.exports.<method>(*args)`` (legacy, async-only as of
        16.x) or ``script.exports_sync.<method>(*args)`` (preferred,
        blocks until the agent returns). We try sync first, fall
        back to the legacy attribute for older Frida builds.
        """
        if self._script is None:
            raise RuntimeError("AgentController not connected")
        exports = getattr(self._script, "exports_sync", None) \
            or self._script.exports
        fn = getattr(exports, method, None)
        if fn is None:
            raise AttributeError(
                f"agent has no exported method: {method}")
        return fn(*args)

    # ------------------------------------------------------------------
    # Internal: dispatch Frida message → registered callbacks
    # ------------------------------------------------------------------

    def _on_message(self, message: dict, data: Optional[bytes]) -> None:
        """Frida message router.

        Frida wraps every ``send()`` in a message envelope of the
        form ``{"type": "send", "payload": …}`` for normal events
        and ``{"type": "error", …}`` for agent-side exceptions.
        We surface errors as warnings and only forward ``send``
        payloads to listeners.
        """
        msg_type = message.get("type")
        if msg_type == "error":
            log.warning("agent error: %s", message.get("description"))
            stack = message.get("stack")
            if stack:
                log.debug("agent stack: %s", stack)
            return
        if msg_type != "send":
            log.debug("ignoring frida message type=%s", msg_type)
            return
        payload = message.get("payload")
        if not isinstance(payload, dict):
            log.debug("ignoring non-dict send payload: %r", payload)
            return
        for cb in self._callbacks:
            try:
                cb(payload, data)
            except Exception:
                log.exception("event callback raised")

    # Context manager sugar.
    def __enter__(self) -> "AgentController":
        self.connect()
        return self

    def __exit__(self, *exc) -> None:
        self.disconnect()
