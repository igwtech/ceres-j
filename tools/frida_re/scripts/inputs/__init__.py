"""NC2 input injection toolkit (keyboard + mouse) via Frida.

All injection goes through SendInput + AttachThreadInput inside Wine.
(RawInput-only injection doesn't work for menu nav — see
`[[frida-input-injection-pinned]]`.)

The public API:

  from inputs import keyboard, mouse, focus, session

  with session.attach() as s:
      focus.focus_nc2(s)
      keyboard.press_key(s, 'W', hold_ms=3000)   # walk forward 3s
      mouse.click(s, 'left', 512, 384)           # click center
      keyboard.press_key(s, 'F2')                # open inventory

Each module's docstring explains its specific functions. Run the
CLI wrapper (`frida_input.py`) for one-shot scripted actions from
the shell.
"""
