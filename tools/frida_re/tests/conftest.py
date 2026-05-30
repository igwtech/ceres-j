"""pytest configuration for frida_re tests.

* Adds the project root to ``sys.path`` so ``orchestrator`` can be
  imported without needing an installed package.
* Stubs the ``frida`` module if it is not installed, so unit tests
  for the controller can run in CI environments that don't have
  Frida wheels available. The stub is *not* used by any test that
  marks itself ``functional`` and connects to a real gadget — those
  are skipped automatically if real Frida is missing.
"""

from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Callable, List, Optional

# Make ``orchestrator`` importable.
HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
if str(PROJECT) not in sys.path:
    sys.path.insert(0, str(PROJECT))


# ---------------------------------------------------------------------
# Stub the ``frida`` module if it is missing.
# ---------------------------------------------------------------------

class _StubScript:
    """Minimal stand-in for ``frida.core.Script``.

    Captures ``on('message', cb)`` registrations and exposes
    ``inject_message(payload, data=None)`` so tests can fire fake
    agent events through the same code path the orchestrator uses
    in production.
    """

    def __init__(self, source: str) -> None:
        self.source = source
        self._handlers: List[Callable[[dict, Optional[bytes]], None]] = []
        self.loaded = False
        self.unloaded = False
        self.exports_sync = SimpleNamespace()
        self.exports = self.exports_sync   # legacy alias

    def on(self, event: str,
           handler: Callable[[dict, Optional[bytes]], None]) -> None:
        if event != "message":
            raise ValueError(f"stub only handles 'message', got {event!r}")
        self._handlers.append(handler)

    def load(self) -> None:
        self.loaded = True

    def unload(self) -> None:
        self.unloaded = True

    # Test affordance — not part of real Frida API.
    def inject_message(self, payload: dict,
                       data: Optional[bytes] = None) -> None:
        for h in self._handlers:
            h(payload, data)


class _StubSession:
    def __init__(self) -> None:
        self.detached = False
        self.last_script: Optional[_StubScript] = None

    def create_script(self, source: str) -> _StubScript:
        self.last_script = _StubScript(source)
        return self.last_script

    def detach(self) -> None:
        self.detached = True


class _StubProcess:
    """Stand-in for frida.core.Process used in enumerate_processes."""
    def __init__(self, pid: int, name: str) -> None:
        self.pid = pid
        self.name = name


class _StubDevice:
    def __init__(self) -> None:
        self.attached_targets: List[Any] = []
        self.session = _StubSession()
        # Default: one fake gadget process. Tests can override.
        self.processes: List[_StubProcess] = [_StubProcess(1234, "Gadget")]

    def attach(self, target: Any) -> _StubSession:
        self.attached_targets.append(target)
        return self.session

    def enumerate_processes(self) -> List[_StubProcess]:
        return list(self.processes)


class _StubDeviceManager:
    def __init__(self) -> None:
        self.added: List[str] = []
        self.device = _StubDevice()

    def add_remote_device(self, location: str) -> _StubDevice:
        self.added.append(location)
        return self.device


# Sentinel exposed so individual tests can poke at the singleton
# device manager and assert side effects.
_DEVICE_MANAGER = _StubDeviceManager()


def _get_device_manager() -> _StubDeviceManager:
    return _DEVICE_MANAGER


def _install_frida_stub() -> None:
    try:
        import frida  # noqa: F401 — real frida present, do nothing
        return
    except ImportError:
        pass

    stub = SimpleNamespace(
        get_device_manager=_get_device_manager,
        __version__="stub-0.0.0",
        # Surface the stub manager so tests can reach it cleanly.
        _stub_device_manager=_DEVICE_MANAGER,
    )
    sys.modules["frida"] = stub  # type: ignore[assignment]


_install_frida_stub()


# ---------------------------------------------------------------------
# Pytest fixtures
# ---------------------------------------------------------------------

import pytest  # noqa: E402 — must come after sys.path manipulation


@pytest.fixture
def stub_frida():
    """Returns the stub device manager and resets it for each test."""
    global _DEVICE_MANAGER
    _DEVICE_MANAGER = _StubDeviceManager()
    sys.modules["frida"]._stub_device_manager = _DEVICE_MANAGER  # type: ignore[attr-defined]
    sys.modules["frida"].get_device_manager = lambda: _DEVICE_MANAGER  # type: ignore[attr-defined]
    yield _DEVICE_MANAGER
