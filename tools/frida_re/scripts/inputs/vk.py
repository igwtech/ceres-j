"""Win32 Virtual-Key constants + NC2 key name → VK mapping.

We keep our own table (instead of importing one from a Win32 wrapper
library) so the input toolkit has zero non-stdlib runtime deps and
agents can use the same map in JS via copy-paste.

NC2 key bindings are the engine defaults (Reakktor 2026 client).
Custom keymaps live in `~/Neocron2/keybindings.cfg` — if a user
changes bindings, override via the CLI's ``--vk`` flag or by
extending NC2_KEYS.

References:
  https://learn.microsoft.com/en-us/windows/win32/inputdev/virtual-key-codes
"""

from __future__ import annotations

from typing import Dict


# ── Letters ────────────────────────────────────────────────────────
VK_LETTERS = {chr(c): c for c in range(ord('A'), ord('Z') + 1)}
# ── Digits ─────────────────────────────────────────────────────────
VK_DIGITS = {str(d): 0x30 + d for d in range(10)}

# ── Modifiers + control keys ──────────────────────────────────────
VK_CONTROL = {
    'BACK':       0x08, 'BACKSPACE': 0x08,
    'TAB':        0x09,
    'CLEAR':      0x0c,
    'RETURN':     0x0d, 'ENTER': 0x0d,
    'SHIFT':      0x10,
    'CONTROL':    0x11, 'CTRL': 0x11,
    'ALT':        0x12, 'MENU': 0x12,
    'PAUSE':      0x13,
    'CAPITAL':    0x14, 'CAPSLOCK': 0x14,
    'ESCAPE':     0x1b, 'ESC': 0x1b,
    'SPACE':      0x20,
    'PAGEUP':     0x21, 'PRIOR': 0x21,
    'PAGEDOWN':   0x22, 'NEXT':  0x22,
    'END':        0x23,
    'HOME':       0x24,
    'LEFT':       0x25,
    'UP':         0x26,
    'RIGHT':      0x27,
    'DOWN':       0x28,
    'SELECT':     0x29,
    'PRINT':      0x2a,
    'EXECUTE':    0x2b,
    'SNAPSHOT':   0x2c, 'PRINTSCREEN': 0x2c,
    'INSERT':     0x2d, 'INS': 0x2d,
    'DELETE':     0x2e, 'DEL': 0x2e,
    'HELP':       0x2f,
    'LWIN':       0x5b,
    'RWIN':       0x5c,
    'APPS':       0x5d,
    'SLEEP':      0x5f,
    'MULTIPLY':   0x6a,
    'ADD':        0x6b,
    'SEPARATOR':  0x6c,
    'SUBTRACT':   0x6d,
    'DECIMAL':    0x6e,
    'DIVIDE':     0x6f,
    'NUMLOCK':    0x90,
    'SCROLL':     0x91,
    'LSHIFT':     0xa0, 'RSHIFT':   0xa1,
    'LCONTROL':   0xa2, 'RCONTROL': 0xa3,
    'LMENU':      0xa4, 'RMENU':    0xa5,
    'OEM_1':      0xba, 'SEMICOLON': 0xba,
    'OEM_PLUS':   0xbb,
    'OEM_COMMA':  0xbc, 'COMMA':     0xbc,
    'OEM_MINUS':  0xbd,
    'OEM_PERIOD': 0xbe, 'PERIOD':    0xbe,
    'OEM_2':      0xbf, 'SLASH':     0xbf,
    'OEM_3':      0xc0, 'GRAVE':     0xc0, 'TILDE': 0xc0,
    'OEM_4':      0xdb, 'LBRACKET':  0xdb,
    'OEM_5':      0xdc, 'BACKSLASH': 0xdc,
    'OEM_6':      0xdd, 'RBRACKET':  0xdd,
    'OEM_7':      0xde, 'QUOTE':     0xde,
}

# Function keys F1..F24
VK_FUNCTION = {f'F{i}': 0x70 + (i - 1) for i in range(1, 25)}

# Numpad 0..9
VK_NUMPAD = {f'NUMPAD{i}': 0x60 + i for i in range(10)}

# Master union
VK_ALL: Dict[str, int] = {}
VK_ALL.update(VK_LETTERS)
VK_ALL.update(VK_DIGITS)
VK_ALL.update(VK_CONTROL)
VK_ALL.update(VK_FUNCTION)
VK_ALL.update(VK_NUMPAD)


# ── NC2-specific high-level aliases ────────────────────────────────
# These name the *action* rather than the literal key, so callers
# don't have to remember "the inventory key is F2". When NC2 changes
# bindings, only this table needs updating.
NC2_KEYS: Dict[str, str] = {
    # Movement (WASD default)
    'forward':   'W',
    'backward':  'S',
    'strafe_left':  'A',
    'strafe_right': 'D',
    'turn_left':    'LEFT',
    'turn_right':   'RIGHT',
    'jump':         'SPACE',
    'crouch':       'C',
    'prone':        'X',
    'run_toggle':   'SHIFT',   # hold to walk vs default-run
    'auto_run':     'NUMPAD_PLUS',  # not bound by default in NC2

    # Combat / weapon
    'fire':           None,    # LMB (mouse)
    'alt_fire':       None,    # RMB (mouse)
    'reload':         'R',
    'flashlight':     'F',
    'lock_target':    'TAB',
    'cycle_weapon':   'Y',
    # Weapon slots 1..0 (top row digits)
    'weapon_1': '1', 'weapon_2': '2', 'weapon_3': '3', 'weapon_4': '4',
    'weapon_5': '5', 'weapon_6': '6', 'weapon_7': '7', 'weapon_8': '8',
    'weapon_9': '9', 'weapon_0': '0',

    # UI panels (F-keys)
    'menu':           'ESC',
    'inventory':      'F2',
    'skills':         'F3',
    'missions':       'F4',
    'map':            'F5',
    'character':      'F6',
    'apartment':      'F7',
    'channels':       'F8',
    'options':        'F10',
    'help':           'F1',
    'screenshot':     'F12',

    # Chat
    'chat':           'RETURN',
    'chat_team':      'T',
    'chat_emote':     'U',

    # Camera / view
    'first_person':   'V',
    'free_look':      'PAGEUP',

    # Misc
    'use':            'E',     # interact with object in crosshair
    'pickup':         'G',
    'drop':           'B',
    'ready_weapon':   'Q',
    'logout':         None,    # only via /logout chat command
}


def resolve(name: str) -> int:
    """Map a key name (NC2 action OR Win32 VK name) → VK code.

    Lookup precedence: NC2 action alias → Win32 VK name → raw int.
    Names are case-insensitive.

    Raises ValueError if unmappable.
    """
    if not name:
        raise ValueError("empty key name")
    if isinstance(name, int):
        return name & 0xff
    if isinstance(name, str) and name.startswith("0x"):
        try:
            return int(name, 16) & 0xff
        except ValueError:
            pass
    key = name.upper()
    # NC2 alias?
    if key.lower() in NC2_KEYS:
        target = NC2_KEYS[key.lower()]
        if target is None:
            raise ValueError(f"NC2 action {name!r} is mouse-bound, "
                             "not a keyboard key")
        key = target.upper()
    if key in VK_ALL:
        return VK_ALL[key]
    raise ValueError(f"unknown key name: {name!r}")
