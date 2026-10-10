#!/usr/bin/env python3
"""login_pass.py — the shortest real-client check: ENTER long-login, wait
in-world, record what a fix needs, tear down.

  ./login_pass.py ceres  <run_id> [--seconds N] [--scan-uid UID]
  ./login_pass.py retail <run_id>

Unlike run_pass.py it takes no screenshots before world entry (a capture
right after the char-select -> world device reset crashes DXVK about half
the time) and only one after. Artifacts in frida_re/runs/<run_id>/:
trace.jsonl, marks.log, 01_inworld.png, charsys.json (with --scan-uid).

--scan-uid finds the client's CHARSYS object by its section-1 image
(`fa ?? 11 00` at +0x30, UID LE32 at +0x50) and records the kind at +0x54.
Run with the project-root venv (it has frida) and pin PROTON_BIN to
GE-Proton10-34.
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

SERVERS = {"ceres": "172.18.0.3", "retail": "157.90.195.74"}
FRIDA_RE_DIR = os.path.dirname(_HERE)
RUNS_ROOT = os.path.join(FRIDA_RE_DIR, "runs")
NC2_DIR = os.environ.get("NC2_DIR", "/home/javier/Neocron2")
GADGET_PORT = 27042

T_GADGET_WAIT = 90
T_AGENT_WAIT = 60
T_MENU_SETTLE = 10
T_LOGIN_WAIT = 60
T_SETTLE = 2


def scan_charsys(sess, uid):
    pat = "fa ?? 11 00 " + "?? " * 28 + " ".join(
        f"{b:02x}" for b in uid.to_bytes(4, "little"))
    hits = []
    for h in sess.state.exports_sync.scan(pat, 40):
        d = sess.state.exports_sync.dump_at(h, 0, 0x30)
        hits.append({"at_0x30": h, "kind_0x54": d["u32"][9] if d else None})
    return {"uid": uid, "pattern": pat, "hits": hits}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("server", choices=list(SERVERS))
    ap.add_argument("run_id")
    ap.add_argument("--user", default="msn3wolf")
    ap.add_argument("--password", default="sprlnk2kk")
    ap.add_argument("--seconds", type=int, default=10,
                    help="stay in world this long before teardown")
    ap.add_argument("--scan-uid", type=int)
    args = ap.parse_args()

    run_dir = os.path.join(RUNS_ROOT, args.run_id)
    os.makedirs(run_dir, exist_ok=True)
    nar = Narrator(os.path.join(run_dir, "marks.log"))
    lc = Lifecycle(SERVERS[args.server], run_dir, scripts_dir=_HERE,
                   frida_re_dir=FRIDA_RE_DIR, py=sys.executable,
                   nc2_dir=NC2_DIR, port=GADGET_PORT)
    sess = None
    try:
        nar.mark(f"run {args.run_id}  server={args.server}")
        lc.clean_slate()
        lc.set_server()
        lc.launch_client()
        if not lc.wait_gadget(T_GADGET_WAIT):
            nar.say("FATAL: gadget never came up"); return 1
        lc.start_orchestrator()
        device = lc.wait_device(T_AGENT_WAIT)
        if not device:
            nar.say("FATAL: no device candidates in trace"); return 1
        sess = Session(port=GADGET_PORT)

        time.sleep(T_MENU_SETTLE)
        sess.focus()
        sess.type_text(args.user)
        sess.key(K.TAB)
        sess.type_text(args.password)
        sess.keys([K.TAB, K.TAB])      # RESUME -> ENTER
        sess.key(K.ENTER)
        time.sleep(3)
        sess.keys([K.TAB, K.ENTER])    # confirm server
        time.sleep(3)
        sess.key(K.ENTER)              # enter world with the selected char
        if not lc.wait_inworld(T_LOGIN_WAIT):
            nar.say("FATAL: never reached in-world (no sustained UDP)")
            return 1
        nar.say("in world")
        time.sleep(T_SETTLE)

        if args.scan_uid is not None:
            res = scan_charsys(sess, args.scan_uid)
            with open(os.path.join(run_dir, "charsys.json"), "w") as f:
                json.dump(res, f, indent=1)
            nar.say(f"charsys scan: {res['hits']}")

        time.sleep(max(0, args.seconds - T_SETTLE))
        ok = sess.screenshot(os.path.join(run_dir, "01_inworld.png"), device)
        nar.say(f"screenshot 01_inworld.png{'' if ok else ' [FAILED]'}")
        nar.mark("pass_complete")
        return 0
    except Exception as e:
        nar.say(f"PASS ERROR: {type(e).__name__}: {e}")
        return 1
    finally:
        if sess:
            sess.close()
        lc.teardown()
        nar.close()


if __name__ == "__main__":
    sys.exit(main())
