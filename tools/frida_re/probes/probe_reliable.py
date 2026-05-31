#!/usr/bin/env python3
"""probe_reliable.py — live-instrument the NC2 client's app-layer
reliable receive path (ProcessGuaranteedMsg / AddMsgToOOOList) to see
WHY a contiguously-received reliable seq is not committed (the
apartment-idle re-request storm).

Reuses the harness Lifecycle + Session by import (does NOT modify them),
and injects reliable_probe.js as an extra script on the same session.

  probe_reliable.py <ceres|retail>
"""
from __future__ import annotations
import os
import sys
import time
import json

_HERE = os.path.dirname(os.path.abspath(__file__))
FRIDA_RE_DIR = os.path.dirname(_HERE)
SCRIPTS_DIR = os.path.join(FRIDA_RE_DIR, "scripts")
sys.path.insert(0, SCRIPTS_DIR)

from harness.session import Session       # noqa: E402
from harness.lifecycle import Lifecycle   # noqa: E402
from harness import keys as K             # noqa: E402

SERVERS = {"ceres": "172.18.0.3", "retail": "157.90.195.74"}
_JS_FILE = os.environ.get("PROBE_JS_FILE", "reliable_probe.js")
PROBE_JS = open(os.path.join(_HERE, _JS_FILE)).read()

events = []
_live_fh = None


def on_probe(msg, data):
    if msg.get("type") == "send":
        events.append(msg["payload"])
        if _live_fh:
            _live_fh.write(json.dumps(msg["payload"]) + "\n"); _live_fh.flush()
    elif msg.get("type") == "error":
        e = {"ev": "probe_error", "desc": msg.get("description")}
        events.append(e)
        if _live_fh:
            _live_fh.write(json.dumps(e) + "\n"); _live_fh.flush()


def enter_login(sess):
    sess.focus()
    sess.type_text("msn3wolf"); sess.key(K.TAB)
    sess.type_text("sprlnk2kk")
    sess.keys([K.TAB, K.TAB]); sess.key(K.ENTER); time.sleep(3)
    sess.keys([K.TAB, K.ENTER]); time.sleep(3)
    sess.key(K.ENTER); time.sleep(3)


def main():
    server = sys.argv[1] if len(sys.argv) > 1 else "ceres"
    server_ip = SERVERS[server]
    run_dir = os.path.join(FRIDA_RE_DIR, "runs",
                           f"probe_{server}_{time.strftime('%Y%m%d_%H%M%S')}")
    os.makedirs(run_dir, exist_ok=True)
    lc = Lifecycle(server_ip, run_dir, scripts_dir=SCRIPTS_DIR,
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
        # Live-write events so a killed/timed-out run still leaves data.
        global _live_fh
        _live_fh = open(os.path.join(run_dir, "probe_events.jsonl"), "w")
        # Add our reliable-handler probe as a 4th script on the session
        # BEFORE login so the login reliable burst flows through the hooks.
        probe = sess._load(PROBE_JS, on_probe)
        print(f"[probe] menu settle 12s ({server})"); time.sleep(12)
        print("[probe] login"); enter_login(sess)
        if not lc.wait_inworld(60):
            print("FATAL: never in-world"); return 1
        print("[probe] in-world; idling 6s to capture reliable handling")
        time.sleep(6)
        # Dump + summarize
        out = os.path.join(run_dir, "probe_events.jsonl")
        with open(out, "w") as f:
            for e in events:
                f.write(json.dumps(e) + "\n")
        summarize()
        print(f"[probe] events: {out}")
        return 0
    finally:
        if sess:
            sess.close()
        lc.teardown()


def summarize():
    from collections import Counter
    kinds = Counter(e.get("ev") for e in events)
    print("\n===== PROBE SUMMARY =====")
    print("event counts:", dict(kinds))
    pgm = [e for e in events if e.get("ev") == "pgm"]
    ooo = [e for e in events if e.get("ev") == "ooo_add"]
    apprx = [e for e in events if e.get("ev") == "apprx"]
    print(f"app-handler entries (apprx) = {len(apprx)}   "
          f"ProcessGuaranteedMsg (pgm) = {len(pgm)}   "
          f"AddMsgToOOOList (ooo_add) = {len(ooo)}")
    print("\n-- first 18 ProcessGuaranteedMsg decisions "
          "(a0/a1=stack args, expected_before -> expected_after, ret) --")
    for e in pgm[:18]:
        print(f"  ecx={e['ecx']} a0={e['a0']} a1={e['a1']} a2={e['a2']} "
              f"active={e['active']} exp={e['expected_before']}->{e['expected_after']} "
              f"muid_base={e['muid_base']} ret={e['ret']}")
    print("\n-- AddMsgToOOOList (seqs queued as MISSING -> re-requested) --")
    for e in ooo[:18]:
        print(f"  ecx={e['ecx']} a0={e['a0']} a1={e['a1']}")


if __name__ == "__main__":
    sys.exit(main())
