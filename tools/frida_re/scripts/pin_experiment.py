#!/usr/bin/env python3
"""Deterministic struct-pinning experiment.

Pre-req: the DB row has UNIQUE sentinel values (set them, then run).
This does a clean-slate launch -> login -> in-world, then scans client
memory for each sentinel. A value that appears PINS that field's
address (and a dump around it reveals neighbouring fields). A value
that does NOT appear is recomputed/stored differently by the client.

  ./pin_experiment.py        # one clean-slate pass, scans SENTINELS
"""
from __future__ import annotations
import os
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)

from harness.session import Session         # noqa: E402
from harness.lifecycle import Lifecycle     # noqa: E402
from harness.login import robust_login      # noqa: E402
from harness import keys as K               # noqa: E402

SERVER_IP = "172.18.0.3"
FRIDA_RE_DIR = os.path.dirname(_HERE)

# (db_column, sentinel value) — must match what was UPDATE'd in the DB.
# Only DIRECT-stored fields can be sentineled here; the server
# re-derives pools from subskills, so pool columns get normalised away
# (and invalid pool values break char loading). cash is direct-stored.
SENTINELS = [
    ("cash", 53197),
]
CLUSTER_WINDOW = 0x600   # bytes; fields of one struct fall within this


def do_login(sess):
    sess.focus()
    sess.type_text("msn3wolf"); sess.key(K.TAB)
    sess.type_text("sprlnk2kk"); sess.keys([K.TAB, K.ENTER]); time.sleep(3)
    sess.keys([K.TAB, K.ENTER]); time.sleep(3); sess.key(K.ENTER)


def main():
    run_dir = os.path.join(FRIDA_RE_DIR, "runs",
                           "pin_" + time.strftime("%Y%m%d_%H%M%S"))
    os.makedirs(run_dir, exist_ok=True)
    lc = Lifecycle(SERVER_IP, run_dir, scripts_dir=_HERE,
                   frida_re_dir=FRIDA_RE_DIR, py=sys.executable)
    sess = None
    try:
        lc.clean_slate(); lc.set_server(); lc.launch_client()
        if not lc.wait_gadget(90):
            print("FATAL: no gadget"); return 1
        lc.start_orchestrator()
        device = lc.wait_device(60)
        if not device:
            print("FATAL: no device"); return 1
        sess = Session()

        def shot(name):
            ok = sess.screenshot(os.path.join(run_dir, name + ".png"), device)
            print(f"[shot] {name} {'ok' if ok else 'FAIL'}")

        if not robust_login(sess, lc, "msn3wolf", "sprlnk2kk", shot=shot):
            print("FATAL: login failed"); return 1
        print("[pin] in-world; settling"); time.sleep(3)
        print("\n==================== SENTINEL SCAN ====================")
        all_hits = {}   # label -> set of int addresses (u16 + u32)
        for label, val in SENTINELS:
            u32 = sess.scan(val, "u32", cap=60)
            u16 = sess.scan(val, "u16", cap=80)
            addrs = sorted({int(a, 16) for a in (u32 + u16)})
            all_hits[label] = addrs
            print(f"[{label}]={val}: u32={len(u32)} u16={len(u16)} "
                  f"total={len(addrs)}")

        # Cluster detection: the real character struct is where several
        # distinct sentinels fall within CLUSTER_WINDOW of each other.
        print("\n-------- CLUSTERS (>=3 fields within "
              f"0x{CLUSTER_WINDOW:x}) --------")
        anchor = min(all_hits, key=lambda k: len(all_hits[k]))
        found = False
        for a in all_hits[anchor]:
            near = {}
            for label, addrs in all_hits.items():
                best = None
                for b in addrs:
                    if abs(b - a) <= CLUSTER_WINDOW:
                        if best is None or abs(b - a) < abs(best - a):
                            best = b
                if best is not None:
                    near[label] = best - a
            if len(near) >= 3:
                found = True
                print(f"\nCLUSTER anchored @ {hex(a)} ({len(near)} fields):")
                for label, off in sorted(near.items(), key=lambda x: x[1]):
                    print(f"    {label:14} at {off:+#07x}  "
                          f"(abs {hex(a + off)})")
        if not found:
            print("  no >=3-field cluster — fields may be stored apart "
                  "or recomputed. Raw hits per field above.")
        print("\n======================================================")
        return 0
    finally:
        if sess:
            sess.close()
        lc.teardown()


if __name__ == "__main__":
    sys.exit(main())
