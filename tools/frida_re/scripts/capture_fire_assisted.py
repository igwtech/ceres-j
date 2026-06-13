#!/usr/bin/env python3
"""capture_fire_assisted.py — hybrid combat capture: the harness auto-logs
in Krafteo and draws the weapon, then STOPS driving and hands control to
the human to aim + fire at an NPC. The orchestrator records decrypted
wire the whole time, so you only do the part that needs a human eye.

Flow: login slot 0 -> pepper_p1 -> draw (QB '1') -> "YOUR TURN" -> idle N
seconds while YOU aim at an NPC and fire/reload -> capture.

  ./capture_fire_assisted.py                       # retail
  SERVER_IP=172.18.0.3 ./capture_fire_assisted.py  # ceres
  IDLE=240 ./capture_fire_assisted.py              # longer human window
"""
from __future__ import annotations
import os, sys, time

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)
from harness import keys as K            # noqa: E402
from harness.narrate import Narrator     # noqa: E402
from harness.session import Session      # noqa: E402
from harness.lifecycle import Lifecycle  # noqa: E402

SERVER_IP = os.environ.get("SERVER_IP", "157.90.195.74")
IDLE = float(os.environ.get("IDLE", "200"))
FRIDA_RE_DIR = os.path.dirname(_HERE)
RUNS_ROOT = os.path.join(FRIDA_RE_DIR, "runs")
NC2_DIR = os.environ.get("NC2_DIR", "/home/javier/Neocron2")
GADGET_PORT = 27042
USER, PASSWORD = "msn3wolf", "sprlnk2kk"
SLOT = int(os.environ.get("CHAR_SLOT", "0"))   # 0=Krafteo, 1=Norman Gates, ...


def main() -> int:
    run_id = os.environ.get("RUN_ID") or f"fire_assist_{time.strftime('%Y%m%d_%H%M%S')}"
    run_dir = os.path.join(RUNS_ROOT, run_id)
    os.makedirs(run_dir, exist_ok=True)
    nar = Narrator(os.path.join(run_dir, "marks.log"))
    lc = Lifecycle(SERVER_IP, run_dir, scripts_dir=_HERE,
                   frida_re_dir=FRIDA_RE_DIR, py=sys.executable,
                   nc2_dir=NC2_DIR, port=GADGET_PORT)
    sess = None
    device = None

    def shot(name, why=""):
        if not device: return
        ok = sess.screenshot(os.path.join(run_dir, name), device)
        nar.say(f"screenshot {name} {'OK' if ok else 'FAILED'} {why}")

    try:
        nar.mark(f"capture_fire_assisted {run_id} {USER} on {SERVER_IP}")
        lc.clean_slate(); lc.set_server(); lc.launch_client()
        if not lc.wait_gadget(120):
            nar.say("FATAL: gadget never came up"); return 1
        lc.start_orchestrator()
        device = lc.wait_device(40) or ""
        sess = Session(port=GADGET_PORT)
        time.sleep(10)

        nar.say(f"AUTO: ENTER-login {USER} -> slot 0 (Krafteo)")
        sess.focus()
        sess.type_text(USER); sess.key(K.TAB); sess.type_text(PASSWORD)
        sess.keys([K.TAB, K.TAB]); sess.key(K.ENTER)
        time.sleep(3)
        sess.keys([K.TAB, K.ENTER]); time.sleep(4)
        for _ in range(SLOT):
            sess.key(K.RIGHT); time.sleep(0.2)
        sess.key(K.ENTER)
        if not lc.wait_inworld(80):
            nar.say("FATAL: never reached in-world"); return 1
        time.sleep(4)
        nar.mark("IN_WORLD")

        # Draw the weapon. The client may remember the drawn state across
        # sessions, so confirm via a screenshot the human can eyeball.
        nar.mark("AUTO DRAW press 1")
        sess.key(K.digit(1)); time.sleep(2.0)
        shot("01_after_draw1.png", "is the weapon OUT? if not I press 1 again")
        # Press once more in case it was already drawn at login (toggle).
        # (Human will confirm from the screenshots which state is which.)

        nar.mark("=== YOUR TURN: aim at an NPC and FIRE / RELOAD now ===")
        nar.say(f"HANDOFF — controls are yours for {IDLE:.0f}s. If the weapon "
                f"is holstered, press 1. Aim at an NPC, hold LMB to fire until "
                f"it takes damage/dies, then R to reload and fire to empty.")
        end = time.time() + IDLE
        i = 0
        while time.time() < end:
            time.sleep(20)
            i += 1
            shot(f"snap_{i:02d}.png", "periodic")
            nar.say(f"...capturing ({int(end-time.time())}s left)")
        nar.mark("DONE")
        return 0
    except Exception as e:
        nar.say(f"PASS ERROR: {type(e).__name__}: {e}")
        return 1
    finally:
        if sess:
            try: sess.close()
            except Exception: pass
        lc.teardown(); nar.close()
        print(f"\nArtifacts: {run_dir}")


if __name__ == "__main__":
    sys.exit(main())
