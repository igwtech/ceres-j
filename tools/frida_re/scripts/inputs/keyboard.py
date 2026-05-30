"""High-level keyboard actions for NC2.

Each function takes a Session (from inputs.session). The session
handles focus and the SendInput plumbing.

Quick reference for callers:

    walk_forward(s, 3000)            # hold W for 3s
    walk(s, dx=+1, dz=+1, ms=2000)   # diagonal: A+W combined
    open_panel(s, 'inventory')       # F2
    fire_weapon(s, slot=3, ms=200)   # equip slot 3 then trigger
    chat_say(s, "/who")              # type a slash-command + Enter

All functions assert focus before sending — call ``focus_nc2(s)``
once at the start of a sequence if you want batch focus management.
"""

from __future__ import annotations

import time
from typing import Iterable, List, Optional

from . import vk
from .session import Session


# Per-key default press duration. NC2's input pump samples
# WM_KEYDOWN/UP per game tick; <50 ms presses are sometimes missed.
TAP_MS = 60


def tap(s: Session, key: str, hold_ms: int = TAP_MS) -> None:
    """Press + release a single key. Resolves NC2 actions ('forward')
    OR Win32 VK names ('F2') OR raw ints/hex."""
    s.key_press(vk.resolve(key), hold_ms)


def hold(s: Session, key: str, ms: int) -> None:
    """Hold a key down for ``ms`` milliseconds, then release. Use this
    for movement and continuous actions where ``tap`` would only
    register a single ms step."""
    code = vk.resolve(key)
    s.key_down(code)
    # Sleep happens inside the agent so the gadget process holds
    # the key for the right wall-clock duration. We also re-focus
    # periodically (every 500 ms) in case Wine drops it.
    chunk = 500
    remaining = ms
    while remaining > 0:
        step = min(chunk, remaining)
        s.sleep(step)
        remaining -= step
        # Cheap focus re-assert.
        s.focus_nc2()
    s.key_up(code)


# ── Movement helpers ───────────────────────────────────────────────


def walk_forward(s: Session, ms: int = 1000) -> None:
    hold(s, 'forward', ms)


def walk_backward(s: Session, ms: int = 1000) -> None:
    hold(s, 'backward', ms)


def strafe_left(s: Session, ms: int = 1000) -> None:
    hold(s, 'strafe_left', ms)


def strafe_right(s: Session, ms: int = 1000) -> None:
    hold(s, 'strafe_right', ms)


def turn_left(s: Session, ms: int = 250) -> None:
    hold(s, 'turn_left', ms)


def turn_right(s: Session, ms: int = 250) -> None:
    hold(s, 'turn_right', ms)


def jump(s: Session) -> None:
    tap(s, 'jump')


def crouch(s: Session) -> None:
    tap(s, 'crouch')


def prone(s: Session) -> None:
    tap(s, 'prone')


def walk(s: Session, dx: int = 0, dz: int = 0, ms: int = 1000) -> None:
    """Compound movement: ``dx``∈{-1,0,+1} for strafe, ``dz``∈{-1,0,+1}
    for forward/back. Holds the combined keys for ``ms``.
    """
    keys: List[str] = []
    if dz > 0: keys.append('forward')
    if dz < 0: keys.append('backward')
    if dx > 0: keys.append('strafe_right')
    if dx < 0: keys.append('strafe_left')
    codes = [vk.resolve(k) for k in keys]
    for c in codes:
        s.key_down(c)
    chunk = 500
    remaining = ms
    while remaining > 0:
        step = min(chunk, remaining)
        s.sleep(step)
        remaining -= step
        s.focus_nc2()
    for c in codes:
        s.key_up(c)


# ── UI panels (F-keys) ─────────────────────────────────────────────


KNOWN_PANELS = ('inventory', 'skills', 'missions', 'map', 'character',
                'apartment', 'channels', 'options', 'help', 'menu')


def open_panel(s: Session, panel: str) -> None:
    """Tap the F-key for a UI panel by NC2 action name (case-insens)."""
    if panel.lower() not in KNOWN_PANELS:
        raise ValueError(f"unknown panel {panel!r}; known: {KNOWN_PANELS}")
    tap(s, panel)


# ── Combat / weapon ────────────────────────────────────────────────


def equip_slot(s: Session, slot: int) -> None:
    """Equip the weapon/hotbar in slot 1..0 (where 0 = key '0')."""
    if not (0 <= slot <= 9):
        raise ValueError("slot must be 0..9")
    tap(s, f'weapon_{slot}')


def reload(s: Session) -> None:
    tap(s, 'reload')


def lock_target(s: Session) -> None:
    """TAB to cycle target lock."""
    tap(s, 'lock_target')


def use(s: Session) -> None:
    """E key — interact with object under crosshair."""
    tap(s, 'use')


def pickup(s: Session) -> None:
    tap(s, 'pickup')


# ── Chat ───────────────────────────────────────────────────────────


def chat_say(s: Session, message: str, channel_key: str = 'chat',
             post_delay_ms: int = 100) -> None:
    """Open chat (Enter by default), type ``message``, press Enter to
    send. Use ``channel_key='chat_team'`` to send team-chat.

    Characters are mapped via per-char key sequences. Symbols and
    digits route through their VK codes; letters route through their
    upper-case VK and SHIFT held when the source character is upper
    case.
    """
    tap(s, channel_key, TAP_MS)
    s.sleep(120)
    type_text(s, message)
    s.sleep(post_delay_ms)
    tap(s, 'RETURN')


def type_text(s: Session, text: str, per_char_ms: int = 30) -> None:
    """Type a plain-ASCII string by tapping each character's VK.
    Letters honour case via SHIFT. Useful for chat, search, names.
    Caveats: doesn't dead-key compose; non-ASCII raises.
    """
    SHIFT_SYMBOLS = {
        '!': '1', '@': '2', '#': '3', '$': '4', '%': '5',
        '^': '6', '&': '7', '*': '8', '(': '9', ')': '0',
        '_': 'OEM_MINUS', '+': 'OEM_PLUS',
        '{': 'OEM_4', '}': 'OEM_6', '|': 'OEM_5',
        ':': 'OEM_1', '"': 'OEM_7',
        '<': 'COMMA', '>': 'PERIOD', '?': 'OEM_2',
        '~': 'OEM_3',
    }
    UNSHIFTED_SYMBOLS = {
        '-': 'OEM_MINUS', '=': 'OEM_PLUS',
        '[': 'OEM_4', ']': 'OEM_6', '\\': 'OEM_5',
        ';': 'OEM_1', "'": 'OEM_7',
        ',': 'COMMA', '.': 'PERIOD', '/': 'OEM_2',
        '`': 'OEM_3',
    }
    SHIFT_VK = vk.resolve('SHIFT')

    for ch in text:
        if ch == ' ':
            s.key_press(vk.resolve('SPACE'), per_char_ms)
        elif ch.isdigit():
            s.key_press(vk.resolve(ch), per_char_ms)
        elif ch.isalpha():
            need_shift = ch.isupper()
            code = vk.resolve(ch.upper())
            if need_shift:
                s.key_down(SHIFT_VK)
            s.key_press(code, per_char_ms)
            if need_shift:
                s.key_up(SHIFT_VK)
        elif ch in SHIFT_SYMBOLS:
            code = vk.resolve(SHIFT_SYMBOLS[ch])
            s.key_down(SHIFT_VK)
            s.key_press(code, per_char_ms)
            s.key_up(SHIFT_VK)
        elif ch in UNSHIFTED_SYMBOLS:
            s.key_press(vk.resolve(UNSHIFTED_SYMBOLS[ch]),
                        per_char_ms)
        elif ch == '\n':
            s.key_press(vk.resolve('RETURN'), per_char_ms)
        elif ch == '\t':
            s.key_press(vk.resolve('TAB'), per_char_ms)
        else:
            raise ValueError(
                f"type_text: unmapped char {ch!r} (U+{ord(ch):04X})")
        s.sleep(15)


def slash_command(s: Session, cmd: str) -> None:
    """Convenience for ``/who``, ``/setpsi 0``, GM commands etc.
    Just wraps chat_say with a leading slash if missing.
    """
    if not cmd.startswith('/'):
        cmd = '/' + cmd
    chat_say(s, cmd)
