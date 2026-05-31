#!/usr/bin/env python3
"""capture_equip.py — login, idle baseline, then EQUIP/HOLSTER the
quickbelt (number keys 1..N draw/holster the weapon in each quickbelt
slot), capturing the equip-holster protocol (0x03/0x1f/0x1f etc.).
No D3D screenshots, so the flaky capture guard can't abort.

  capture_equip.py <ceres|retail>   -> prints trace path
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
                           f"equip_{server}_{time.strftime('%Y%m%d_%H%M%S')}")
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
        print(f"[equip] menu settle 12s ({server})"); time.sleep(12)
        print("[equip] login"); enter_login(sess)
        if not lc.wait_inworld(60):
            print("FATAL: never in-world"); return 1
        print("[equip] in-world; 3s baseline idle"); time.sleep(3)
        # Equip/holster: draw each quickbelt slot in turn (number keys).
        # Press, hold a beat, press again to holster — exercises the
        # equip-holster round trip in both directions.
        for n in (1, 2, 3):
            print(f"[equip] quickbelt slot {n} (draw)")
            sess.key(K.digit(n)); time.sleep(2.0)
        print("[equip] 3s capture window for equip protocol"); time.sleep(3)
        print(f"[equip] trace: {lc.trace}")
        return 0
    finally:
        if sess:
            sess.close()
        lc.teardown()


if __name__ == "__main__":
    sys.exit(main())
