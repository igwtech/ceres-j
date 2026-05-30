#!/usr/bin/env python3
"""frida_input.py — CLI for sending NC2 keyboard + mouse input.

Usage examples:

  # Tap inventory (F2)
  frida_input.py key inventory
  frida_input.py key F2

  # Walk forward 3s
  frida_input.py hold forward 3000

  # Compound move: strafe right + forward 2s (diagonal)
  frida_input.py walk --dx +1 --dz +1 --ms 2000

  # Say something in chat
  frida_input.py say "hello world"

  # Slash command (GM, etc.)
  frida_input.py slash "/setpsi 0"

  # Mouse click at client coords (512, 384)
  frida_input.py click --x 512 --y 384 --button left

  # Look 30 right + 5 down (relative)
  frida_input.py look --dx 30 --dy 5

  # Drag from (100,100) to (200,300)
  frida_input.py drag --from-x 100 --from-y 100 --to-x 200 --to-y 300

  # Script multiple actions sequentially
  frida_input.py batch \
      key inventory \
      sleep 500 \
      click --x 300 --y 200 \
      key menu

Exit codes:
  0   success
  2   bad usage
  3   gadget not reachable
  4   NC2 window not found

Each invocation attaches a fresh Frida session, runs, detaches.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

# Make `inputs` importable when running from anywhere.
HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from inputs import keyboard, mouse, session, vk  # noqa: E402


def _print(obj):
    if isinstance(obj, (dict, list)):
        print(json.dumps(obj, indent=2))
    elif obj is not None:
        print(obj)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="frida_input", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=27042)
    p.add_argument("--no-focus", action="store_true",
                   help="skip NC2 focus assertion (use only if you "
                        "know NC2 already has foreground)")
    p.add_argument("--title", default="Neocron Evolution",
                   help="NC2 window title (override if custom build)")

    sub = p.add_subparsers(dest="cmd", required=True)

    # status — show NC2 window + foreground
    sp = sub.add_parser("status",
                        help="print NC2 hwnd + foreground window + client rect")

    # focus — just focus NC2 (no input)
    sp = sub.add_parser("focus", help="focus the NC2 window")

    # key <name> [--hold MS]
    sp = sub.add_parser("key", help="tap a key (NC2 action or VK name)")
    sp.add_argument("name")
    sp.add_argument("--hold", type=int, default=60,
                    help="hold time ms (default 60)")

    # hold <name> <ms> — hold a key for ms milliseconds
    sp = sub.add_parser("hold", help="hold a key for N milliseconds")
    sp.add_argument("name")
    sp.add_argument("ms", type=int)

    # walk --dx --dz --ms
    sp = sub.add_parser("walk", help="compound movement")
    sp.add_argument("--dx", type=int, default=0,
                    help="strafe: -1 left, +1 right")
    sp.add_argument("--dz", type=int, default=0,
                    help="forward: -1 back, +1 forward")
    sp.add_argument("--ms", type=int, default=1000)

    # say <text>
    sp = sub.add_parser("say", help="open chat, type, send")
    sp.add_argument("text")
    sp.add_argument("--team", action="store_true",
                    help="team chat instead of global")

    # slash <command>
    sp = sub.add_parser("slash", help="send a slash command")
    sp.add_argument("command",
                    help="e.g. \"/setpsi 0\" — leading slash optional")

    # click [--x --y] --button
    sp = sub.add_parser("click", help="mouse click")
    sp.add_argument("--x", type=int, default=None)
    sp.add_argument("--y", type=int, default=None)
    sp.add_argument("--button", default="left",
                    choices=("left", "right", "middle"))
    sp.add_argument("--hold-ms", type=int, default=30)

    # dclick — double click
    sp = sub.add_parser("dclick", help="double-click")
    sp.add_argument("--x", type=int, default=None)
    sp.add_argument("--y", type=int, default=None)
    sp.add_argument("--button", default="left",
                    choices=("left", "right", "middle"))

    # look --dx --dy
    sp = sub.add_parser("look", help="relative mouse motion (camera)")
    sp.add_argument("--dx", type=int, required=True)
    sp.add_argument("--dy", type=int, required=True)

    # move --x --y
    sp = sub.add_parser("move", help="move cursor to client (x, y)")
    sp.add_argument("--x", type=int, required=True)
    sp.add_argument("--y", type=int, required=True)

    # drag --from-x --from-y --to-x --to-y
    sp = sub.add_parser("drag", help="drag (e.g. inventory move)")
    sp.add_argument("--from-x", dest="fx", type=int, required=True)
    sp.add_argument("--from-y", dest="fy", type=int, required=True)
    sp.add_argument("--to-x", dest="tx", type=int, required=True)
    sp.add_argument("--to-y", dest="ty", type=int, required=True)
    sp.add_argument("--button", default="left",
                    choices=("left", "right", "middle"))
    sp.add_argument("--steps", type=int, default=12)

    # wheel <delta>
    sp = sub.add_parser("wheel", help="scroll wheel (±120 per notch)")
    sp.add_argument("delta", type=int)

    # sleep <ms>
    sp = sub.add_parser("sleep", help="sleep for milliseconds")
    sp.add_argument("ms", type=int)

    # vk-lookup <name> — useful for debugging keymaps
    sp = sub.add_parser("vk", help="show the VK code for a key/action name")
    sp.add_argument("name")

    # batch — run multiple sub-commands in one session
    sp = sub.add_parser("batch", help="run multiple sub-commands "
                                       "sequentially in one Frida session "
                                       "(arguments parsed greedily — use "
                                       "`--` to delimit cleanly)")
    sp.add_argument("tokens", nargs=argparse.REMAINDER)

    return p


def _run_one(args, s: "session.Session") -> None:
    """Dispatch a single sub-command using an existing Session."""
    if args.cmd == "status":
        hwnd = s.find_nc2()
        fg = s._script.exports_sync.foreground()
        rc = s.client_rect() if hwnd else None
        _print({"nc2_hwnd": hwnd, "foreground": fg, "client_rect": rc})
    elif args.cmd == "focus":
        _print(s.focus_nc2())
    elif args.cmd == "key":
        keyboard.tap(s, args.name, args.hold)
    elif args.cmd == "hold":
        keyboard.hold(s, args.name, args.ms)
    elif args.cmd == "walk":
        keyboard.walk(s, dx=args.dx, dz=args.dz, ms=args.ms)
    elif args.cmd == "say":
        keyboard.chat_say(s, args.text,
                          channel_key='chat_team' if args.team else 'chat')
    elif args.cmd == "slash":
        keyboard.slash_command(s, args.command)
    elif args.cmd == "click":
        mouse.click(s, args.button, args.x, args.y, args.hold_ms)
    elif args.cmd == "dclick":
        mouse.double_click(s, args.button, args.x, args.y)
    elif args.cmd == "look":
        mouse.look_rel(s, args.dx, args.dy)
    elif args.cmd == "move":
        mouse.move_to(s, args.x, args.y)
    elif args.cmd == "drag":
        mouse.drag(s, (args.fx, args.fy), (args.tx, args.ty),
                   button=args.button, steps=args.steps)
    elif args.cmd == "wheel":
        mouse.wheel(s, args.delta)
    elif args.cmd == "sleep":
        s.sleep(args.ms)
    elif args.cmd == "vk":
        # Let ValueError propagate; main() returns rc=2 on it.
        code = vk.resolve(args.name)
        _print({"name": args.name, "vk_hex": f"0x{code:02x}",
                "vk_dec": code})
    else:
        raise AssertionError(f"dispatch missing for {args.cmd!r}")


def _split_batch_tokens(tokens):
    """Split batch tokens into individual sub-command argument lists.
    Recognised commands: key, hold, walk, say, slash, click, dclick,
    look, move, drag, wheel, sleep, focus, status, vk. Tokens before
    the next recognised command go into the current sub-command's args."""
    KNOWN = {"key", "hold", "walk", "say", "slash", "click", "dclick",
             "look", "move", "drag", "wheel", "sleep", "focus",
             "status", "vk"}
    chunks: list[list[str]] = []
    cur: list[str] = []
    for tok in tokens:
        if tok in KNOWN and cur and cur[0] in KNOWN:
            chunks.append(cur)
            cur = [tok]
        elif not cur:
            cur = [tok]
        else:
            cur.append(tok)
    if cur:
        chunks.append(cur)
    return chunks


def main(argv=None) -> int:
    p = build_parser()
    args = p.parse_args(argv)

    # The 'vk' command is offline — no Frida needed.
    if args.cmd == "vk":
        try:
            _run_one(args, None)  # type: ignore[arg-type]
        except ValueError as e:
            print(f"error: {e}", file=sys.stderr)
            return 2
        return 0

    try:
        with session.attach(host=args.host, port=args.port,
                            nc2_title=args.title) as s:
            if not args.no_focus:
                fr = s.focus_nc2()
                if isinstance(fr, dict) and not fr.get("ok"):
                    print(f"warning: focus failed: {fr}", file=sys.stderr)
            if args.cmd == "batch":
                sub_parser = build_parser()
                chunks = _split_batch_tokens(args.tokens)
                for chunk in chunks:
                    sub_args = sub_parser.parse_args(chunk)
                    _run_one(sub_args, s)
            else:
                _run_one(args, s)
    except RuntimeError as e:
        print(f"error: {e}", file=sys.stderr)
        if "gadget" in str(e).lower() or "frida" in str(e).lower():
            return 3
        return 1
    except Exception as e:
        print(f"error: {type(e).__name__}: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
