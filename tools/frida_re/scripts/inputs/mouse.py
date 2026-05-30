"""High-level mouse actions for NC2.

All coordinate input takes CLIENT-AREA pixels (0,0 = top-left of the
NC2 window). The session translates to virtual-desk coords behind
the scenes.

Mouse buttons:
    'left'   — primary fire / select
    'right'  — secondary fire / context menu / camera-grab in NC2
    'middle' — third button (NC2 has no default bind)

Mouse wheel:
    wheel(s, +120)  — scroll up (one notch)
    wheel(s, -120)  — scroll down

Notes:
    NC2's first-person camera uses RELATIVE mouse motion (delta from
    last frame) — so for camera control prefer ``look_rel(dx, dy)``.
    Absolute moves are useful for UI clicks (inventory slots, dialog
    buttons, etc.)
"""

from __future__ import annotations

import time
from typing import Optional

from .session import Session


def _client_to_virtual_desk(s: Session, x: int, y: int) -> Optional[
        tuple[int, int]]:
    """Convert a client-area (x, y) to virtual-desk 0..65535 coords
    used by SendInput's MOUSEEVENTF_ABSOLUTE | VIRTUALDESK path."""
    rc = s.client_rect()
    if rc is None:
        return None
    sx = rc['screen_x'] + x
    sy = rc['screen_y'] + y
    cx = rc.get('cx_screen', 1920)
    cy = rc.get('cy_screen', 1080)
    if cx <= 0 or cy <= 0:
        return None
    vx = int(round(sx * 65535 / cx))
    vy = int(round(sy * 65535 / cy))
    return vx, vy


def move_to(s: Session, x: int, y: int) -> bool:
    """Move cursor to client-area (x, y)."""
    v = _client_to_virtual_desk(s, x, y)
    if v is None: return False
    s.mouse_move_abs(*v)
    return True


def look_rel(s: Session, dx: int, dy: int) -> None:
    """Relative mouse motion — drives the first-person camera.
    ``dx``>0 turns right, ``dy``>0 looks down. Reasonable steps are
    ±5 to ±50 per frame; larger jumps can desync the look-angle
    if the engine clamps per-tick deltas."""
    s.mouse_move_rel(dx, dy)


def press_button(s: Session, button: str = 'left') -> None:
    s.mouse_button(button, True)


def release_button(s: Session, button: str = 'left') -> None:
    s.mouse_button(button, False)


def click(s: Session, button: str = 'left',
          x: Optional[int] = None, y: Optional[int] = None,
          hold_ms: int = 30) -> None:
    """Click ``button`` at client-area (x, y), or at the current
    cursor location if x/y are None."""
    if x is not None and y is not None:
        if not move_to(s, x, y):
            raise RuntimeError("move_to failed — is NC2 focused?")
        s.sleep(20)
    s.mouse_button(button, True)
    s.sleep(hold_ms)
    s.mouse_button(button, False)


def double_click(s: Session, button: str = 'left',
                 x: Optional[int] = None, y: Optional[int] = None,
                 gap_ms: int = 80) -> None:
    click(s, button, x, y)
    s.sleep(gap_ms)
    click(s, button)


def drag(s: Session, from_xy: tuple[int, int],
         to_xy: tuple[int, int],
         button: str = 'left',
         steps: int = 12,
         step_ms: int = 25) -> None:
    """Press at from_xy, glide to to_xy in ``steps`` increments,
    release. Used for inventory drag-and-drop, slider widgets."""
    fx, fy = from_xy
    tx, ty = to_xy
    if not move_to(s, fx, fy):
        raise RuntimeError("move_to(start) failed")
    s.sleep(40)
    s.mouse_button(button, True)
    for i in range(1, steps + 1):
        x = fx + (tx - fx) * i // steps
        y = fy + (ty - fy) * i // steps
        move_to(s, x, y)
        s.sleep(step_ms)
    s.mouse_button(button, False)


def wheel(s: Session, delta: int = 120) -> None:
    """One Windows wheel-notch = ±120 by convention."""
    s.mouse_wheel(delta)


def fire_burst(s: Session, ms: int = 250) -> None:
    """Hold LMB for ``ms`` to fire the equipped weapon."""
    s.mouse_button('left', True)
    s.sleep(ms)
    s.mouse_button('left', False)


def aim_pulse(s: Session, ms: int = 500) -> None:
    """Hold RMB for ``ms`` (zoom/aim, or in NC2 right-click look)."""
    s.mouse_button('right', True)
    s.sleep(ms)
    s.mouse_button('right', False)
