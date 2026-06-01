#!/usr/bin/env python3
"""run_pass.py — one deterministic, repeatable instrumented NC2 client
pass, from launch to kill, against a chosen server. Python rewrite of
run_pass.sh using the reusable `harness` package.

  ./run_pass.py ceres            # vs Ceres-J (172.18.0.3)
  ./run_pass.py retail [run_id]  # vs retail (157.90.195.74)

Artifacts land in  frida_re/runs/<run_id>/ :
  trace.jsonl   decrypted wire (udp_recv/send) + input + d3d events
  NN_*.png      D3D9 backbuffer frames at each checkpoint
  NN_state.json client-state snapshots
  marks.log     timestamped narration / step markers

Edit the STEP LIST at the bottom — keep it identical across servers so
the runs diff apples-to-apples.
"""
from __future__ import annotations
import argparse
import json
import os
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)

from harness import keys as K            # noqa: E402
from harness.narrate import Narrator     # noqa: E402
from harness.session import Session      # noqa: E402
from harness.lifecycle import Lifecycle  # noqa: E402

# ── Config (deterministic constants) ─────────────────────────────────
SERVERS = {"ceres": "172.18.0.3", "retail": "157.90.195.74"}
FRIDA_RE_DIR = os.path.dirname(_HERE)
RUNS_ROOT = os.path.join(FRIDA_RE_DIR, "runs")
PY = sys.executable
NC2_DIR = os.environ.get("NC2_DIR", "/home/javier/Neocron2")
GADGET_PORT = 27042

T_GADGET_WAIT = 90    # max wait for gadget port
T_AGENT_WAIT = 60     # max wait for agent ready + device in trace
T_MENU_SETTLE = 10     # menu render settle before login
T_LOGIN_WAIT = 60     # max wait for in-world (sustained UDP)
T_STEP = 8            # per movement step (s)
T_SETTLE = 2          # settle after each action before capture


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("server", choices=list(SERVERS))
    ap.add_argument("run_id", nargs="?")
    args = ap.parse_args()

    server_ip = SERVERS[args.server]
    run_id = args.run_id or f"{args.server}_{time.strftime('%Y%m%d_%H%M%S')}"
    run_dir = os.path.join(RUNS_ROOT, run_id)
    os.makedirs(run_dir, exist_ok=True)

    nar = Narrator(os.path.join(run_dir, "marks.log"))
    lc = Lifecycle(server_ip, run_dir, scripts_dir=_HERE,
                   frida_re_dir=FRIDA_RE_DIR, py=PY, nc2_dir=NC2_DIR,
                   port=GADGET_PORT)

    def shot(name, why=""):
        ok = sess.screenshot(os.path.join(run_dir, name), device)
        nar.say(f"screenshot {name}{(' — ' + why) if why else ''}"
                f"{'' if ok else ' [FAILED]'}")
        return ok

    def snapshot(name, label=""):
        if not sess.arm_state(secs=6):
            nar.say(f"STATE ({label}) | CHARSYS base unresolved "
                    "(HUD tick not seen — pin offsets / check in-world)")
            return
        snap = sess.read_state()
        with open(os.path.join(run_dir, name), "w") as f:
            json.dump(snap, f, indent=1)
        nar.state(snap, label)

    sess = None
    try:
        nar.mark(f"run {run_id}  server={args.server} ({server_ip})")
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

        # ── Deterministic pass ───────────────────────────────────────
        nar.say("At main menu. Expect the Neocron splash "
                "(RESUME/ENTER/OPTIONS).")
        time.sleep(T_MENU_SETTLE)
        shot("00_menu.png", "main menu before login")

        nar.say("Long login (ENTER path: full server+char select, avoids "
                "the LastChar resume dependency). Screenshotting each step.")
        sess.focus()
        sess.type_text('msn3wolf')
        sess.key(K.TAB)
        sess.type_text('sprlnk2kk')
        # Screenshots are diagnostic, not required for the pass — a flaky
        # frame must not abort the login (the input path is a separate
        # Frida script and keeps working). shot() already logs [FAILED].
        shot("login_1_creds.png", "username+password typed")
        # Buttons: RESUME ENTER OPTIONS CREDITS. RESUME is 1 Tab from the
        # password field; ENTER is 1 more Tab to the right.
        sess.keys([K.TAB, K.TAB])
        shot("login_2_enter_selected.png", "ENTER button highlighted?")
        sess.key(K.ENTER)
        time.sleep(3)
        shot("login_3_after_enter.png", "after ENTER (server-select screen?)")
        sess.keys([K.TAB, K.ENTER])           # confirm server (slot already set via INI)
        time.sleep(3)
        shot("login_4_charselect.png", "char-select screen (Krafteo slot 0?)")
        sess.key(K.ENTER)            # enter world with selected char
        time.sleep(3)
        shot("login_5_entering.png", "entering world?")
        if not lc.wait_inworld(T_LOGIN_WAIT):
            nar.say("FATAL: never reached in-world (no sustained UDP)")
            return 1
        time.sleep(T_SETTLE)
        nar.say("In world. Capturing baseline frame + state.")
        shot("01_inworld.png", "spawned in-world")
        snapshot("01_state.json", "baseline")

        # ── STEP LIST — edit; keep IDENTICAL across servers ──────────
        nar.say(f"STEP 1: walk forward {T_STEP}s. Expect position to "
                "advance; pools steady in a safe zone.")
        sess.hold(K.W, T_STEP * 800)
        time.sleep(T_SETTLE)
        shot("02_after_fwd.png", "after walk forward")
        snapshot("02_state.json", "after_fwd")

        nar.say("STEP 2: mouselook turn right (A/D strafe, so turning is "
                "the mouse). Expect heading change; pools steady. If the "
                "camera doesn't move, retry sess.look(400, hold_rmb=True).")
        sess.look(677)            # +dx = turn right; tune to mouse sensitivity
        time.sleep(T_SETTLE)
        shot("03_after_turn.png", "after mouselook turn")
        snapshot("03_state.json", "after_turn")
        
        sess.hold(K.W, T_STEP * 800)
        sess.look(-677)            # +dx = turn right; tune to mouse sensitivity
        time.sleep(T_SETTLE)
        shot("03.1_after_turn.png", "after mouselook turn")
        snapshot("03.1_state.json", "after_turn")
        
        nar.say("STEP 3: open inventory (F2), screenshot, close (Esc).")
        sess.key(K.F2)
        time.sleep(T_SETTLE)
        shot("04_inventory.png", "F2 inventory open")
        sess.key(K.ESC)

        nar.say("STEP 4: idle 3s. Expect steady state.")
        time.sleep(3)
        shot("05_idle.png", "idle")
        snapshot("05_state.json", "idle")
        # ── END STEP LIST ────────────────────────────────────────────

        nar.say(f"Pass complete. Artifacts in {run_dir}.")
        nar.mark("pass_complete")
        return 0
    except Exception as e:
        # A session hiccup (e.g. the 2nd-client script being torn down)
        # should end the pass cleanly, not dump a traceback.
        nar.say(f"PASS ERROR: {type(e).__name__}: {e}")
        return 1
    finally:
        if sess:
            sess.close()
        lc.teardown()
        nar.close()


if __name__ == "__main__":
    sys.exit(main())
