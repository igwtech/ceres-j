"""NC2 instrumentation harness — reusable modules for running one
deterministic, repeatable client pass (launch -> kill) against retail
or Ceres-J, collecting wire (decrypted trace) + visual (D3D9 frames) +
client-state artifacts.

  from harness import keys
  from harness.narrate import Narrator
  from harness.session import Session
  from harness.lifecycle import Lifecycle
"""
from . import keys                      # noqa: F401
from .narrate import Narrator           # noqa: F401
