#!/usr/bin/env python3
"""capture_retail_fire.py — capture a RETAIL firing/combat sequence so we
can map the weapon-fire packet families (visual vs authoritative) and
reproduce them in Ceres.

Drives the 64-bit harness: ENTER-login Krafteo (slot 0) -> pepper_p1 ->
draw the Lazar (QB '1') -> level the aim -> FIRE bursts (hold LMB),
RELOAD ('R'), and a long burst to drain the magazine. Every action is
bracketed in marks.log so the trace.jsonl windows isolate:
  - C->S fire command (0x03/0x1f act 0x01: weapon_id, target_id, shot data)
  - S->C ammo decrement (authoritative, self HUD)
  - S->C muzzle/shot/impact (visual broadcast)
  - reload command + ammo refill
  - empty-magazine behaviour
Screenshots capture the HUD ammo counter + muzzle flash for cross-check.

  ./capture_retail_fire.py                       # vs retail (157.90.195.74)
  SERVER_IP=172.18.0.3 ./capture_retail_fire.py  # control run vs Ceres
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
        f"fire_{time.strftime('%Y%m%d_%H%M%S')}"
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
            nar.say(f"(skip {name})"); return
        ok = sess.screenshot(os.path.join(run_dir, name), device)
        nar.say(f"screenshot {name} {'OK' if ok else 'FAILED'}"
                f"{(' — ' + why) if why else ''}")

    def fire(ms, tag):
        """Hold left mouse button for ms to fire."""
        nar.mark(f"FIRE_{tag} ({ms}ms)")
        sess.mouse_button("left", True)
        time.sleep(ms / 1000.0)
        sess.mouse_button("left", False)

    try:
        nar.mark(f"capture_fire {run_id} {USER} on {SERVER_IP}")
        lc.clean_slate(); lc.set_server(); lc.launch_client()
        if not lc.wait_gadget(120):
            nar.say("FATAL: gadget never came up"); return 1
        lc.start_orchestrator()
        device = lc.wait_device(40) or ""
        nar.say(f"device: {device or '(present-hook)'}")
        sess = Session(port=GADGET_PORT)
        time.sleep(10)

        nar.say(f"ENTER-login {USER} -> slot 0 (Krafteo)")
        sess.focus()
        sess.type_text(USER); sess.key(K.TAB); sess.type_text(PASSWORD)
        sess.keys([K.TAB, K.TAB]); sess.key(K.ENTER)
        time.sleep(3)
        sess.keys([K.TAB, K.ENTER])            # confirm server
        time.sleep(4)
        for _ in range(SLOT):                  # slot 0 = no movement
            sess.key(K.RIGHT); time.sleep(0.2)
        sess.key(K.ENTER)
        if not lc.wait_inworld(80):
            nar.say("FATAL: never reached in-world"); return 1
        time.sleep(4)
        nar.mark("IN_WORLD")
        sess.look(0, 150); time.sleep(0.4)     # level the aim

        # Draw the weapon first (QB slot 1).
        nar.mark("DRAW press 1")
        sess.key(K.digit(1)); time.sleep(2.0)
        shot("01_drawn.png", "weapon drawn, note ammo counter")

        # Burst 1 — short.
        fire(900, "1_short"); time.sleep(0.8)
        shot("02_after_fire1.png", "after short burst (ammo lower? muzzle?)")

        # Burst 2 — medium.
        fire(1500, "2_med"); time.sleep(0.8)
        shot("03_after_fire2.png", "after medium burst")

        # Reload.
        nar.mark("RELOAD press R")
        sess.hold(K.R, 200); time.sleep(2.5)
        shot("04_after_reload.png", "after reload (ammo back to full?)")

        # Burst 3 — long, try to drain the magazine.
        fire(4000, "3_drain"); time.sleep(0.8)
        shot("05_after_drain.png", "after long burst (empty? auto-reload?)")

        # One more tap to see empty-click / no-ammo behaviour.
        fire(600, "4_tap_after_drain"); time.sleep(0.6)
        shot("06_post_drain_tap.png", "tap after drain")

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
