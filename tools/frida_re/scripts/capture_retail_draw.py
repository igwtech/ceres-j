#!/usr/bin/env python3
"""capture_retail_draw.py — capture a clean RETAIL self weapon-draw so we
can byte-diff the actor-direction packets against Ceres and find the
first-person draw trigger Ceres is missing (the weapon model never
attaches even though security is clear + UpdateModel 0x2f is sent).

Drives the rebuilt 64-bit Frida harness against retail
(157.90.195.74): ENTER long-login msn3wolf -> Krafteo (slot 0, the
leftmost char) -> in-world (pepper). Then performs a MARKED draw/holster
sequence so each draw's wire window is bracketed in marks.log; the
orchestrator logs the decrypted wire to trace.jsonl and we grab D3D9
frames to confirm the weapon visibly draws on retail.

  ./capture_retail_draw.py            # vs retail
  SERVER_IP=172.18.0.3 ./capture_retail_draw.py   # control run vs Ceres
"""
from __future__ import annotations
import os, sys, time

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)
from harness import keys as K            # noqa: E402
from harness.narrate import Narrator     # noqa: E402
from harness.session import Session      # noqa: E402
from harness.lifecycle import Lifecycle  # noqa: E402

SERVER_IP = os.environ.get("SERVER_IP", "157.90.195.74")   # retail
FRIDA_RE_DIR = os.path.dirname(_HERE)
RUNS_ROOT = os.path.join(FRIDA_RE_DIR, "runs")
NC2_DIR = os.environ.get("NC2_DIR", "/home/javier/Neocron2")
GADGET_PORT = 27042
USER, PASSWORD, SLOT = "msn3wolf", "sprlnk2kk", 0


def main() -> int:
    run_id = os.environ.get("RUN_ID") or \
        f"retail_draw_{time.strftime('%Y%m%d_%H%M%S')}"
    run_dir = os.path.join(RUNS_ROOT, run_id)
    os.makedirs(run_dir, exist_ok=True)
    nar = Narrator(os.path.join(run_dir, "marks.log"))

    lc = Lifecycle(SERVER_IP, run_dir, scripts_dir=_HERE,
                   frida_re_dir=FRIDA_RE_DIR, py=sys.executable,
                   nc2_dir=NC2_DIR, port=GADGET_PORT)
    sess = None
    device = None

    def shot(name, why=""):
        if not device:
            nar.say(f"(skip {name}: no device)"); return
        ok = sess.screenshot(os.path.join(run_dir, name), device)
        nar.say(f"screenshot {name} {'OK' if ok else 'FAILED'}"
                f"{(' — ' + why) if why else ''}")

    try:
        nar.mark(f"capture_retail_draw {run_id} {USER} on {SERVER_IP}")
        lc.clean_slate(); lc.set_server(); lc.launch_client()
        if not lc.wait_gadget(120):
            nar.say("FATAL: gadget never came up"); return 1
        lc.start_orchestrator()
        device = lc.wait_device(40) or ""
        nar.say(f"live D3D9 device: {device or '(none — present-hook capture)'}")
        sess = Session(port=GADGET_PORT)
        time.sleep(10)

        nar.say(f"ENTER-login {USER} (long path) → leftmost char (Krafteo)")
        sess.focus()
        sess.type_text(USER); sess.key(K.TAB); sess.type_text(PASSWORD)
        sess.keys([K.TAB, K.TAB]); sess.key(K.ENTER)
        time.sleep(3)
        sess.keys([K.TAB, K.ENTER])            # confirm server
        time.sleep(4)
        # set_server() reset ini LastChar=0 → cursor starts at slot 0.
        # The cursor wraps, so step RIGHT only (no LEFT). SLOT=0 → ENTER.
        for _ in range(SLOT):
            sess.key(K.RIGHT); time.sleep(0.2)
        shot("00_charselect.png", "char-select — Krafteo (slot 0)?")
        sess.key(K.ENTER)                      # enter world
        if not lc.wait_inworld(80):
            nar.say("FATAL: never reached in-world"); return 1
        time.sleep(4)
        nar.mark("IN_WORLD")
        # level the camera so the first-person weapon is in frame
        sess.look(0, 160); time.sleep(0.4)
        shot("01_holstered.png", "baseline — weapon holstered")
        time.sleep(1.0)

        # Marked draw/holster cycles. The marks bracket each action's
        # wire window so trace_diff can isolate the draw packets.
        for i in range(1, 5):
            nar.mark(f"DRAW_{i} press 1")
            sess.key(K.digit(1)); time.sleep(2.2)
            shot(f"draw_{i}.png", f"draw #{i} — pistol in hand?")
            time.sleep(0.6)
            nar.mark(f"HOLSTER_{i} press 1")
            sess.key(K.digit(1)); time.sleep(2.2)
            shot(f"holster_{i}.png", f"holster #{i}")
            time.sleep(0.6)
        nar.mark("DONE")
        time.sleep(2)
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
