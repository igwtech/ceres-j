"""Win32 Virtual-Key codes for NC2 input scripting.

Letters/digits are just their ASCII uppercase values, so use letter()
/ digit() for anything not named here.
"""

# Editing / navigation
ESC = 0x1B; TAB = 0x09; ENTER = 0x0D; SPACE = 0x20; BACKSPACE = 0x08
UP = 0x26; DOWN = 0x28; LEFT = 0x25; RIGHT = 0x27

# Function keys (contiguous 0x70..0x7B)
F1, F2, F3, F4, F5, F6 = 0x70, 0x71, 0x72, 0x73, 0x74, 0x75
F7, F8, F9, F10, F11, F12 = 0x76, 0x77, 0x78, 0x79, 0x7A, 0x7B

# Modifiers (generic + left/right)
CTRL = 0x11; LCTRL = 0xA2; RCTRL = 0xA3
SHIFT = 0x10; LSHIFT = 0xA0; RSHIFT = 0xA1
ALT = 0x12; LALT = 0xA4; RALT = 0xA5

# Common gameplay letters (movement + interact)
W = 0x57; A = 0x41; S = 0x53; D = 0x44
E = 0x45; C = 0x43; Q = 0x51; R = 0x52; F = 0x46


def letter(ch: str) -> int:
    """VK code for a letter, e.g. letter('m') -> 0x4D."""
    return ord(ch.upper())


def digit(n) -> int:
    """VK code for a top-row digit 0-9."""
    return 0x30 + int(n)
