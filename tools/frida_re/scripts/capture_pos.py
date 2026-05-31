#!/usr/bin/env python3
"""capture_pos.py — log into a server, idle in-world a few seconds, and
capture the player's position from the wire (Movement packets) WITHOUT
needing D3D screenshots (so the flaky-capture guard can't abort us).

Use to read the retail character's apartment map+coords, to replicate
on Ceres via the DB.

  capture_pos.py <ceres|retail>
"""
from __future__ import annotations
import os
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)

from harness.session import Session       # noqa: E402
from harness.lifecycle import Lifecycle   # noqa: E402
from harness import keys as K             # noqa: E402

SERVERS = {"ceres": "172.18.0.3", "retail": "157.90.195.74"}
FRIDA_RE_DIR = os.path.dirname(_HERE)


def enter_login(sess):
    # Same ENTER long-login path run_pass.py uses (no screenshots here).
    sess.focus()
    sess.type_text("msn3wolf"); sess.key(K.TAB)
    sess.type_text("sprlnk2kk")
    sess.keys([K.TAB, K.TAB]); sess.key(K.ENTER); time.sleep(3)
    sess.keys([K.TAB, K.ENTER]); time.sleep(3)
    sess.key(K.ENTER); time.sleep(3)


def main():
    server = sys.argv[1] if len(sys.argv) > 1 else "retail"
    server_ip = SERVERS[server]
    run_dir = os.path.join(FRIDA_RE_DIR, "runs",
                           f"pos_{server}_{time.strftime('%Y%m%d_%H%M%S')}")
    os.makedirs(run_dir, exist_ok=True)
    lc = Lifecycle(server_ip, run_dir, scripts_dir=_HERE,
                   frida_re_dir=FRIDA_RE_DIR, py=sys.executable)
    sess = None
    try:
        lc.clean_slate(); lc.set_server(); lc.launch_client()
        if not lc.wait_gadget(90):
            print("FATAL: no gadget"); return 1
        lc.start_orchestrator()
        if not lc.wait_device(60):
            print("FATAL: no device"); return 1
        sess = Session()
        print(f"[pos] menu settle 12s ({server})"); time.sleep(12)
        print("[pos] login (ENTER path)"); enter_login(sess)
        if not lc.wait_inworld(60):
            print("FATAL: never in-world"); return 1
        print("[pos] in-world; idling 4s to capture Movement")
        time.sleep(4)
        print(f"[pos] trace: {lc.trace}")

        # Locate the player struct in memory via the known position
        # floats (from the Movement/StartPos decode). The map/zone id
        # should live adjacent to the position vector.
        X, Y, Z = 282.0, -355.0, -271.9
        ax = [int(a, 16) for a in sess.scan(X, "f32", cap=60)]
        ay = [int(a, 16) for a in sess.scan(Y, "f32", cap=60)]
        print(f"[pos] x=282.0 hits={len(ax)}  y=-355.0 hits={len(ay)}")
        # The position is 3 consecutive floats (x, z, y) => x and y are
        # 8 bytes apart. Find x-hits with a y-hit at +8 (or nearby).
        ayset = set(ay)
        struct_addrs = []
        for a in ax:
            for delta in (8, -8, 4, -4, 12, -12):
                if a + delta in ayset:
                    struct_addrs.append((a, delta))
                    break
        print(f"[pos] candidate position structs: "
              f"{[(hex(a), d) for a, d in struct_addrs[:8]]}")
        for a, _ in struct_addrs[:4]:
            base = a - 0x20
            d = sess.dump_at(hex(base), 0, 0x80)
            if not d:
                continue
            print(f"\n[pos] dump around x@{hex(a)} (window from {d['start']}):")
            for i, (u, f) in enumerate(zip(d["u32"], d["f32"])):
                off = i * 4
                fr = f"{f:11.2f}" if f is not None else "       None"
                print(f"    +{off:#05x}  u32={u:>11}  f32={fr}")
        return 0
    finally:
        if sess:
            sess.close()
        lc.teardown()


if __name__ == "__main__":
    sys.exit(main())
