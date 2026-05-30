# NC2 + Frida launcher & input toolkit

Repo-resident scripts to launch the Neocron 2 client under Proton with
the Frida gadget pre-attached, and to inject keyboard + mouse input
into the live game from the shell or from Python.

Lives under `ceres-j/tools/frida_re/scripts/`. Nothing here goes
outside the project repo (no `/tmp/`-resident one-offs).

## TL;DR

```bash
# Terminal A — start NC2 with Frida gadget loaded.
./launch_nc2.sh
# (game window opens, then pauses at DllMain waiting for the orchestrator.)

# Terminal B — attach the orchestrator (records UDP/TCP, decrypts cipher).
./attach_orch.sh

# Terminal C — drive input.
./frida_input.py status
./frida_input.py key inventory          # F2
./frida_input.py hold forward 3000      # walk forward 3s
./frida_input.py walk --dx 1 --dz 1 --ms 2000   # strafe + forward
./frida_input.py click --x 512 --y 384 --button left
./frida_input.py look --dx 30 --dy 0    # turn right
./frida_input.py say "/who"
```

## Files

| File | Purpose |
|------|---------|
| `launch_nc2.sh` | Start NC2 via Proton with `WINEDLLOVERRIDES=dinput8=n,b` so the gadget loads. Auto-installs DLLs from `launcher-addon/` if missing. |
| `attach_orch.sh` | Wait for the gadget to bind 127.0.0.1:27042 then attach the orchestrator (`python -m orchestrator`), record JSONL trace, accept commands via control file. |
| `frida_input.py` | One-shot CLI for keyboard + mouse + cursor. Auto-attaches, runs the action, detaches. |
| `inputs/vk.py` | Win32 VK constants + NC2-action aliases (`forward`, `inventory`, `weapon_5`, …). |
| `inputs/session.py` | Frida session wrapper exposing `key_press`, `mouse_button`, `client_rect`, `focus_nc2`. |
| `inputs/keyboard.py` | High-level keyboard helpers — `walk_forward`, `open_panel`, `chat_say`, `type_text`, `slash_command`. |
| `inputs/mouse.py` | High-level mouse helpers — `move_to`, `click`, `drag`, `look_rel`, `wheel`, `fire_burst`. |

## launch_nc2.sh — environment overrides

| Var | Default | Purpose |
|-----|---------|---------|
| `NC2_DIR` | `/home/javier/Neocron2` | NC2 install directory |
| `NC2_EXE` | `neocronclient.exe` | game binary name |
| `PROTON_BIN` | auto-detected newest `GE-Proton*` | Proton executable |
| `COMPAT_DATA_PATH` | `~/.cache/nc2_proton_compat` | Wine prefix path (stable across runs) |
| `FRIDA_RE_AUTO_INSTALL` | `1` | copy gadget DLLs from `launcher-addon/` if missing |

## NC2 keyboard mapping

Use either the **NC2 action name** (preferred) or the **Win32 VK name**:

```bash
./frida_input.py key forward       # → W (NC2 default)
./frida_input.py key W             # same
./frida_input.py key inventory     # → F2
./frida_input.py key F2            # same
./frida_input.py vk forward        # offline: prints {"vk_hex":"0x57"}
```

Full NC2-action table (engine defaults — override via `inputs/vk.py`
`NC2_KEYS` if you change in-game bindings):

| Action | Default key |
|--------|-------------|
| `forward` / `backward` | `W` / `S` |
| `strafe_left` / `strafe_right` | `A` / `D` |
| `turn_left` / `turn_right` | `LEFT` / `RIGHT` |
| `jump` / `crouch` / `prone` | `SPACE` / `C` / `X` |
| `inventory` / `skills` / `missions` / `map` | `F2` / `F3` / `F4` / `F5` |
| `character` / `apartment` / `channels` / `options` | `F6` / `F7` / `F8` / `F10` |
| `menu` / `help` / `screenshot` | `ESC` / `F1` / `F12` |
| `chat` / `chat_team` | `ENTER` / `T` |
| `use` / `pickup` / `drop` / `ready_weapon` | `E` / `G` / `B` / `Q` |
| `reload` / `flashlight` / `lock_target` | `R` / `F` / `TAB` |
| `weapon_1`..`weapon_0` | `1`..`0` |
| `fire` / `alt_fire` | LMB / RMB (use `click left/right` instead) |

## Mouse

Coordinates are **client-area pixels** (0,0 = top-left of NC2 window):

```bash
./frida_input.py click --x 512 --y 384 --button left
./frida_input.py dclick --x 100 --y 100
./frida_input.py drag --from-x 50 --from-y 50 --to-x 250 --to-y 200
./frida_input.py wheel 120                # one notch up
./frida_input.py move --x 800 --y 600     # just move cursor
```

For first-person camera control prefer **relative** motion:

```bash
./frida_input.py look --dx 30 --dy 0       # turn right ~30 raw deltas
./frida_input.py look --dx -100 --dy 20    # turn left & look down
```

## Batch mode

Chain multiple actions in a single Frida session (cheaper than
attaching N times):

```bash
./frida_input.py batch \
    key inventory \
    sleep 500 \
    click --x 300 --y 200 \
    sleep 200 \
    key menu
```

The splitter recognises sub-commands `key`, `hold`, `walk`, `say`,
`slash`, `click`, `dclick`, `look`, `move`, `drag`, `wheel`, `sleep`,
`focus`, `status`. Flags after a command attach to it.

## Python API

For non-trivial automation, drive the session directly:

```python
import sys; sys.path.insert(0, '.../frida_re/scripts')
from inputs import session, keyboard, mouse

with session.attach() as s:
    s.focus_nc2()
    keyboard.walk(s, dx=+1, dz=+1, ms=3000)
    keyboard.open_panel(s, 'inventory')
    s.sleep(500)
    mouse.click(s, 'left', 512, 384)
    keyboard.chat_say(s, '/who')
```

## Caveats

- **Focus is sticky-managed via `AttachThreadInput`**: a single
  `focus_nc2()` per session is usually enough, but `hold()` and
  `walk()` re-assert focus every 500 ms inside the gadget in case
  Wine drops it during long key holds.
- **`riPress` (the agent-side RawInput injector) doesn't fire for
  menu nav** because NC2's splash/menus poll `GetMessage`/`WM_KEYDOWN`
  rather than RawInput. Use this toolkit (SendInput) for menus; use
  the orchestrator's `ri_press` only for in-game movement keys where
  riding on the existing RawInput pump is acceptable. See
  `[[frida-input-injection-pinned]]` in memory.
- **No Linux-side automation**: per `[[feedback-frida-only-no-host-tools]]`,
  the entire toolkit injects through Wine via Frida. There is no
  fallback through `xdotool`/`wayland` and there shouldn't be one.

## Tests

Unit + parser tests live in `tests/test_input_toolkit.py` (offline,
no live gadget needed). Run alongside the existing suite:

```bash
cd ceres-j/tools/frida_re
python -m pytest tests/test_input_toolkit.py -v
```

Full suite (187 tests as of v0.8.2):

```bash
python -m pytest tests/
```
