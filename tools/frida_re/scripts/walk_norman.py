#!/usr/bin/env python3
"""walk_norman.py — instrumented NC2 pass that logs into retail as
*Norman Gates* (char 2 / slot 1 of msn3wolf), enters the world, and
records a continuous "walking around" frame sequence for a video.

Built on the reusable `harness` package (same lifecycle as run_pass.py),
but with two differences:

  1. Char-select: instead of entering on the default slot-0 char, it
     MOUSE-CLICKS the 2nd portrait (Norman Gates) before entering.
  2. Capture: instead of a few checkpoint screenshots, it holds W and
     grabs D3D9 frames in a tight loop (with periodic mouselook turns)
     so the frames can be muxed into a walkthrough video by ffmpeg.

  ./walk_norman.py                 # vs retail, auto run_id
  ./walk_norman.py my_run 40       # run_id, walk seconds

Artifacts land in frida_re/runs/<run_id>/ :
  frames/f_####.png   the walkthrough frames (native 1024x768)
  charsel_*.png       before/after the portrait click (verify char)
  marks.log           narration
"""
from __future__ import annotations
import argparse
import os
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)

from harness import keys as K            # noqa: E402
from harness.narrate import Narrator     # noqa: E402
from harness.session import Session      # noqa: E402
from harness.lifecycle import Lifecycle  # noqa: E402

SERVER_IP = "157.90.195.74"              # retail (msn3wolf's chars live here)
FRIDA_RE_DIR = os.path.dirname(_HERE)
RUNS_ROOT = os.path.join(FRIDA_RE_DIR, "runs")
PY = sys.executable
NC2_DIR = os.environ.get("NC2_DIR", "/home/javier/Neocron2")
GADGET_PORT = 27042

# Char-select portrait centres in 1024x768 game space (measured from a
# retail char-select frame): Krafteo / NORMAN / Hannibal / Augusto.
PORTRAITS = {0: (295, 338), 1: (430, 338), 2: (562, 338), 3: (692, 338)}
NORMAN_SLOT = 1

T_GADGET_WAIT = 160
T_AGENT_WAIT = 60
T_MENU_SETTLE = 10
T_LOGIN_WAIT = 60


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run_id", nargs="?")
    ap.add_argument("walk_secs", nargs="?", type=int, default=35)
    args = ap.parse_args()

    run_id = args.run_id or f"norman_{time.strftime('%Y%m%d_%H%M%S')}"
    run_dir = os.path.join(RUNS_ROOT, run_id)
    frames_dir = os.path.join(run_dir, "frames")
    os.makedirs(frames_dir, exist_ok=True)

    nar = Narrator(os.path.join(run_dir, "marks.log"))
    lc = Lifecycle(SERVER_IP, run_dir, scripts_dir=_HERE,
                   frida_re_dir=FRIDA_RE_DIR, py=PY, nc2_dir=NC2_DIR,
                   port=GADGET_PORT)

    sess = None

    def shot(name, why=""):
        ok = sess.screenshot(os.path.join(run_dir, name), device)
        nar.say(f"screenshot {name}{(' — ' + why) if why else ''}"
                f"{'' if ok else ' [FAILED]'}")
        return ok

    def click_game(gx, gy):
        """Click at (gx,gy) in 1024x768 game space via absolute mouse."""
        ex = sess.input.exports_sync
        hwnd = sess._ensure_hwnd()
        rc = ex.client_rect(hwnd)
        px = rc["screen_x"] + gx * rc["width"] / 1024.0
        py = rc["screen_y"] + gy * rc["height"] / 768.0
        ax = int(round(px * 65535.0 / rc["cx_screen"]))
        ay = int(round(py * 65535.0 / rc["cy_screen"]))
        ex.mouse_move_abs(ax, ay); time.sleep(0.12)
        ex.mouse_move_abs(ax, ay); time.sleep(0.06)
        ex.mouse_button("left", True); time.sleep(0.05)
        ex.mouse_button("left", False)
        nar.say(f"click portrait game=({gx},{gy}) -> abs=({ax},{ay}) "
                f"client={rc}")

    try:
        nar.mark(f"run {run_id}  retail ({SERVER_IP})  walk={args.walk_secs}s")
        lc.clean_slate()
        lc.set_server()
        lc.launch_client()
        if not lc.wait_gadget(T_GADGET_WAIT):
            nar.say("FATAL: gadget never came up"); return 1
        lc.start_orchestrator()
        device = lc.wait_device(T_AGENT_WAIT)
        if not device:
            nar.say("FATAL: no device candidates in trace"); return 1
        nar.say(f"live D3D9 device: {device}")
        sess = Session(port=GADGET_PORT)

        # ── ENTER long-login (full server + char select) ─────────────
        nar.say("Main menu settle, then ENTER long-login as msn3wolf.")
        time.sleep(T_MENU_SETTLE)
        shot("00_menu.png", "main menu")
        sess.focus()
        sess.type_text("msn3wolf")
        sess.key(K.TAB)
        sess.type_text("sprlnk2kk")
        sess.keys([K.TAB, K.TAB])            # creds -> ENTER button
        sess.key(K.ENTER)
        time.sleep(3)
        sess.keys([K.TAB, K.ENTER])          # confirm server
        time.sleep(3)
        shot("charsel_before.png", "char-select (slot 0 default)")

        # ── Select Norman Gates (slot 1) by clicking the portrait ────
        nar.say("Selecting char 2 = Norman Gates (slot 1).")
        click_game(*PORTRAITS[NORMAN_SLOT])
        time.sleep(1.0)
        shot("charsel_after.png", "Norman selected? (verify before enter)")

        # Enter world. Nudge ENTER until sustained S->C UDP (in-world).
        nar.say("Entering world (ENTER nudges until in-world UDP).")
        inworld = False
        for n in range(8):
            sess.key(K.ENTER)
            if lc.wait_inworld(8, min_recv=50):
                inworld = True
                break
            nar.say(f"spawn nudge {n + 1}/8")
        if not inworld and not lc.wait_inworld(T_LOGIN_WAIT, min_recv=50):
            nar.say("FATAL: never reached in-world (no sustained UDP)")
            shot("99_stuck.png", "not in world")
            return 1
        time.sleep(2)
        shot("01_inworld.png", "spawned in-world as Norman")

        # ── Walk around, capturing frames for the video ──────────────
        nar.say(f"Walking ~{args.walk_secs}s, capturing frames.")
        sess.focus()
        ex = sess.input.exports_sync
        ex.key_down(K.W)                     # hold forward (continuous)
        t_end = time.time() + args.walk_secs
        i = 0
        # gentle S-curve: turn right for a stretch, then left, repeat.
        turn_seq = [0, 0, 0, 120, 120, 0, 0, -120, -120, 0]
        try:
            while time.time() < t_end:
                i += 1
                sess.screenshot(os.path.join(frames_dir, f"f_{i:04d}.png"),
                                device, settle=0.0, tries=2)
                turn = turn_seq[(i // 1) % len(turn_seq)]
                if turn:
                    sess.look(turn, steps=6, step_ms=8)
        finally:
            ex.key_up(K.W)
        nar.say(f"Captured {i} frames in {frames_dir}.")
        nar.mark("walk_complete")
        return 0
    except Exception as e:
        nar.say(f"PASS ERROR: {type(e).__name__}: {e}")
        return 1
    finally:
        if sess:
            sess.close()
        lc.teardown()
        nar.close()


if __name__ == "__main__":
    sys.exit(main())
