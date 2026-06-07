#!/usr/bin/env python3
"""Full two-Frida-client retail equip-broadcast test.

ACTOR  = instrumented client A (~/Neocron2,     gadget 27042) — draws QB weapons.
OBSERVER = instrumented client B (~/Neocron2_obs, gadget 27043) — co-located,
           just captures. Both are REAL clients, so both fully world-enter and
           receive nearby-entity broadcasts (the bot can't).

Then we scan the OBSERVER's trace.jsonl for the actor (by char name and for
weapon/ShortPlayer broadcasts appearing during the draw window) — that's the
cross-player "other players see the weapon" broadcast.

Both chars must be co-located in a NORMAL zone (Pepper Park). Input is
Frida-injected per-process, so the two GUI windows don't fight for focus.

  ./two_frida_equip.py --actor-user msn2wolf --observer-user msn3wolf \
       --draw-keys 1,2,1
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

RETAIL = "157.90.195.74"
FRIDA_RE_DIR = os.path.dirname(_HERE)
RUNS_ROOT = os.path.join(FRIDA_RE_DIR, "runs")
PY = sys.executable
NC2_A = "/home/javier/Neocron2"
NC2_B = "/home/javier/Neocron2_obs"


def bringup(lc, port, label, nar, t_gadget=120, t_agent=70):
    if not lc.wait_gadget(t_gadget):
        nar.say(f"FATAL[{label}]: gadget {port} never came up"); return None
    lc.start_orchestrator()
    dev = lc.wait_device(t_agent)
    if not dev:
        nar.say(f"FATAL[{label}]: no device on {port}"); return None
    nar.say(f"[{label}] device {dev} on port {port}")
    return dev


def login(sess, user, pw, nar, label):
    nar.say(f"[{label}] ENTER-login {user}")
    sess.focus()
    sess.type_text(user); sess.key(K.TAB); sess.type_text(pw)
    sess.keys([K.TAB, K.TAB]); sess.key(K.ENTER)
    time.sleep(3)
    sess.keys([K.TAB, K.ENTER])          # confirm server
    time.sleep(4)
    sess.key(K.ENTER)                    # enter world (slot 0)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--actor-user", default="msn2wolf")
    ap.add_argument("--actor-pass", default="sprlnk2kk")
    ap.add_argument("--observer-user", default="msn3wolf")
    ap.add_argument("--observer-pass", default="sprlnk2kk")
    ap.add_argument("--draw-keys", default="1,2,1")
    ap.add_argument("--run-id", default=None)
    a = ap.parse_args()
    keys = [int(x) for x in a.draw_keys.split(",") if x.strip()]
    run_id = a.run_id or f"twofrida_{time.strftime('%Y%m%d_%H%M%S')}"
    run_a = os.path.join(RUNS_ROOT, run_id + "_actor")
    run_b = os.path.join(RUNS_ROOT, run_id + "_observer")
    os.makedirs(run_a, exist_ok=True); os.makedirs(run_b, exist_ok=True)
    nar = Narrator(os.path.join(run_b, "marks.log"))

    lc_a = Lifecycle(RETAIL, run_a, scripts_dir=_HERE, frida_re_dir=FRIDA_RE_DIR,
                     py=PY, nc2_dir=NC2_A, port=27042)
    lc_b = Lifecycle(RETAIL, run_b, scripts_dir=_HERE, frida_re_dir=FRIDA_RE_DIR,
                     py=PY, nc2_dir=NC2_B, port=27043,
                     compat_path=os.path.expanduser("~/.cache/nc2_proton_compat_obs"))
    sess_a = sess_b = None
    try:
        lc_a.clean_slate()                       # one global clean
        lc_a.set_server(); lc_b.set_server()
        nar.say("launching ACTOR (27042) then OBSERVER (27043)…")
        lc_a.launch_client()
        time.sleep(5)
        lc_b.launch_client()
        dev_a = bringup(lc_a, 27042, "ACT", nar)
        dev_b = bringup(lc_b, 27043, "OBS", nar)
        if not dev_a or not dev_b:
            return 1
        sess_a = Session(port=27042)
        sess_b = Session(port=27043)
        # log both in (observer first so it's settled when actor draws)
        login(sess_b, a.observer_user, a.observer_pass, nar, "OBS")
        login(sess_a, a.actor_user, a.actor_pass, nar, "ACT")
        nar.say("both logging into world… waiting 25s to settle")
        time.sleep(25)
        nar.mark("DRAW_SEQUENCE_START")
        for rep in range(2):
            for n in keys:
                nar.mark(f"ACTOR draw key{n} rep{rep}")
                sess_a.key(K.digit(n))
                time.sleep(2.5)
        nar.mark("DRAW_SEQUENCE_END")
        time.sleep(3)
        nar.say("done; tearing down")
        return 0
    except Exception as e:
        nar.say(f"FATAL: {type(e).__name__}: {e}")
        return 1
    finally:
        for s in (sess_a, sess_b):
            if s:
                try:
                    s.close()
                except Exception:
                    pass
        lc_b.teardown(); lc_a.teardown(); nar.close()
        print(f"\nObserver trace: {os.path.join(run_b, 'trace.jsonl')}")
        print(f"Marks: {os.path.join(run_b, 'marks.log')}")


if __name__ == "__main__":
    sys.exit(main())
