#!/usr/bin/env python3
"""In-world Frida observer: log in, enter world, then idle and capture
decrypted wire (trace.jsonl) for a long window while a SECOND player
(driven manually on another client) performs an action nearby.

Use to capture cross-player broadcasts (e.g. weapon draw) that the
headless bot can't see. The observer is a real client, so it receives
nearby-entity broadcasts.

  ./observer_probe.py retail --user msn2wolf --idle 150

Then drive the actor on your own client during the idle window. Afterwards
scan runs/<run_id>/trace.jsonl for the actor (by name / weapon opcodes).
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

SERVERS = {"ceres": "172.18.0.3", "retail": "157.90.195.74"}
FRIDA_RE_DIR = os.path.dirname(_HERE)
RUNS_ROOT = os.path.join(FRIDA_RE_DIR, "runs")
PY = sys.executable
NC2_DIR = os.environ.get("NC2_DIR", "/home/javier/Neocron2")
GADGET_PORT = 27042


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("server", choices=list(SERVERS))
    ap.add_argument("--user", default="msn2wolf")
    ap.add_argument("--password", default="sprlnk2kk")
    ap.add_argument("--slot-char", type=int, default=0)
    ap.add_argument("--idle", type=float, default=150.0,
                    help="seconds to idle in-world capturing")
    ap.add_argument("--run-id", default=None)
    a = ap.parse_args()

    server_ip = SERVERS[a.server]
    run_id = a.run_id or f"observe_{a.user}_{time.strftime('%Y%m%d_%H%M%S')}"
    run_dir = os.path.join(RUNS_ROOT, run_id)
    os.makedirs(run_dir, exist_ok=True)
    nar = Narrator(os.path.join(run_dir, "marks.log"))
    lc = Lifecycle(server_ip, run_dir, scripts_dir=_HERE,
                   frida_re_dir=FRIDA_RE_DIR, py=PY, nc2_dir=NC2_DIR,
                   port=GADGET_PORT)
    sess = None
    try:
        nar.mark(f"observer {run_id} {a.user} on {server_ip}")
        lc.clean_slate(); lc.set_server(); lc.launch_client()
        if not lc.wait_gadget(120):
            nar.say("FATAL: gadget never came up"); return 1
        lc.start_orchestrator()
        device = lc.wait_device(70)
        if not device:
            nar.say("FATAL: no device"); return 1
        nar.say(f"device {device}")
        sess = Session(port=GADGET_PORT)
        time.sleep(10)
        nar.say(f"ENTER-login {a.user}")
        sess.focus()
        sess.type_text(a.user); sess.key(K.TAB); sess.type_text(a.password)
        sess.keys([K.TAB, K.TAB]); sess.key(K.ENTER)
        time.sleep(3)
        sess.keys([K.TAB, K.ENTER])
        time.sleep(4)
        for _ in range(a.slot_char):
            sess.key(K.RIGHT)
        sess.key(K.ENTER)
        if not lc.wait_inworld(70):
            nar.say("FATAL: never reached in-world"); return 1
        nar.mark("IN_WORLD — START DRAWING THE ACTOR NOW")
        nar.say(f"IN WORLD. Idling {a.idle:.0f}s capturing trace.jsonl. "
                f"Draw/holster the actor's weapon on your client now.")
        end = time.time() + a.idle
        while time.time() < end:
            time.sleep(10)
            nar.say(f"…capturing ({int(end - time.time())}s left)")
        nar.mark("IDLE_END")
        return 0
    except Exception as e:
        nar.say(f"PASS ERROR: {type(e).__name__}: {e}")
        return 1
    finally:
        if sess:
            try:
                sess.close()
            except Exception:
                pass
        lc.teardown(); nar.close()
        print(f"trace: {os.path.join(run_dir, 'trace.jsonl')}")


if __name__ == "__main__":
    sys.exit(main())
