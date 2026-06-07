#!/usr/bin/env python3
"""Live Frida test of the server-authoritative weapon gate (+0x334).

Launches the client, ENTER-logs-in, enters world, loads
probes/weapon_gate_probe.js, then idles while YOU draw/holster on the
client. Prints every PlayerAction weapon-change with its ACCEPT/DENY
verdict and the holster-clear path.

  ./weapon_gate_probe.py ceres  --user msn3wolf --slot-char 0 --idle 120
  ./weapon_gate_probe.py retail --user msn3wolf --slot-char 0 --idle 120

Krafteo (msn3wolf slot 0) has a Lazar pistol + flashlight in the QB.
Run ceres and retail back-to-back and diff the verdicts.
"""
from __future__ import annotations
import argparse, json, os, sys, time

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)
from harness import keys as K            # noqa: E402
from harness.narrate import Narrator     # noqa: E402
from harness.session import Session      # noqa: E402
from harness.lifecycle import Lifecycle  # noqa: E402

SERVERS = {"ceres": "172.18.0.3", "retail": "157.90.195.74"}
FRIDA_RE_DIR = os.path.dirname(_HERE)
RUNS_ROOT = os.path.join(FRIDA_RE_DIR, "runs")
PROBE_JS = os.path.join(FRIDA_RE_DIR, "probes", "weapon_gate_probe.js")
PY = sys.executable
NC2_DIR = os.environ.get("NC2_DIR", "/home/javier/Neocron2")
GADGET_PORT = 27042


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("server", choices=list(SERVERS))
    ap.add_argument("--user", default="msn3wolf")
    ap.add_argument("--password", default="sprlnk2kk")
    ap.add_argument("--slot-char", type=int, default=0)
    ap.add_argument("--idle", type=float, default=120.0)
    ap.add_argument("--run-id", default=None)
    a = ap.parse_args()

    server_ip = SERVERS[a.server]
    run_id = a.run_id or f"wgate_{a.server}_{time.strftime('%Y%m%d_%H%M%S')}"
    run_dir = os.path.join(RUNS_ROOT, run_id)
    os.makedirs(run_dir, exist_ok=True)
    nar = Narrator(os.path.join(run_dir, "marks.log"))
    plog = open(os.path.join(run_dir, "weapon_gate.jsonl"), "w")
    counts = {"weapon_change": 0, "ACCEPT": 0, "DENY": 0, "holster_clear": 0}

    def on_msg(msg, _data):
        if msg.get("type") != "send":
            return
        p = msg.get("payload") or {}
        ev = p.get("ev")
        plog.write(json.dumps(p) + "\n"); plog.flush()
        if ev in ("wg_ready", "hook_ok", "hook_fail"):
            nar.say(f"[probe] {ev} {p.get('name','')} {p.get('err','')}")
        elif ev == "weapon_change":
            counts["weapon_change"] += 1
            counts[p["verdict"]] = counts.get(p["verdict"], 0) + 1
            nar.say(f"[WEAPON_CHANGE] incoming={p['incoming']} "
                    f"cur(+0x334)={p['cur']} -> {p['verdict']}")
        elif ev == "holster_clear":
            counts["holster_clear"] += 1
            nar.say(f"[HOLSTER_CLEAR] cur_before={p.get('cur_before')}")

    lc = Lifecycle(server_ip, run_dir, scripts_dir=_HERE,
                   frida_re_dir=FRIDA_RE_DIR, py=PY, nc2_dir=NC2_DIR,
                   port=GADGET_PORT)
    sess = None
    try:
        nar.mark(f"wgate {run_id} {a.user} on {server_ip}")
        lc.clean_slate(); lc.set_server(); lc.launch_client()
        if not lc.wait_gadget(120):
            nar.say("FATAL: gadget never came up"); return 1
        lc.start_orchestrator()
        if not lc.wait_device(70):
            nar.say("FATAL: no device"); return 1
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
        # load the weapon-gate probe now that the client image is mapped
        with open(PROBE_JS) as f:
            probe_src = f.read()
        sess._load(probe_src, on_msg)
        nar.mark("IN_WORLD — START DRAWING (press QB keys 1/2/3, holster)")
        nar.say(f"IN WORLD. Idling {a.idle:.0f}s. Draw & holster now — "
                f"watch for WEAPON_CHANGE ACCEPT/DENY lines.")
        end = time.time() + a.idle
        while time.time() < end:
            time.sleep(8)
            nar.say(f"…{int(end - time.time())}s left | "
                    f"changes={counts['weapon_change']} "
                    f"ACCEPT={counts['ACCEPT']} DENY={counts['DENY']} "
                    f"holster={counts['holster_clear']}")
        nar.mark("IDLE_END")
        nar.say(f"SUMMARY {a.server}: {counts}")
        return 0
    except Exception as e:
        nar.say(f"PASS ERROR: {type(e).__name__}: {e}")
        return 1
    finally:
        plog.close()
        if sess:
            try: sess.close()
            except Exception: pass
        lc.teardown(); nar.close()
        print(f"probe log: {os.path.join(run_dir, 'weapon_gate.jsonl')}")
        print(f"SUMMARY: {counts}")


if __name__ == "__main__":
    sys.exit(main())
