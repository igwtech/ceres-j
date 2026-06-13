#!/usr/bin/env python3
"""Launch the client with the Frida gadget + cipher trace, then IDLE while
the USER logs in and plays manually. Captures decrypted wire (trace.jsonl)
the whole time — for cases where login automation is flaky (retail) and a
human drives instead.

  ./manual_capture.py retail entry_retail_manual --idle 300
  ./manual_capture.py ceres  entry_ceres_manual  --idle 240

The gadget uses on_load=wait, so the client UI only appears once the
orchestrator attaches (this script does that). After "CLIENT READY", log
in (ENTER long-login) and walk to the target spot.
"""
from __future__ import annotations
import argparse, os, sys, time

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)
from harness.narrate import Narrator     # noqa: E402
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
    ap.add_argument("run_id", nargs="?")
    ap.add_argument("--idle", type=float, default=300.0)
    a = ap.parse_args()

    server_ip = SERVERS[a.server]
    run_id = a.run_id or f"manual_{a.server}_{time.strftime('%Y%m%d_%H%M%S')}"
    run_dir = os.path.join(RUNS_ROOT, run_id)
    os.makedirs(run_dir, exist_ok=True)
    nar = Narrator(os.path.join(run_dir, "marks.log"))
    lc = Lifecycle(server_ip, run_dir, scripts_dir=_HERE,
                   frida_re_dir=FRIDA_RE_DIR, py=PY, nc2_dir=NC2_DIR,
                   port=GADGET_PORT)
    try:
        nar.mark(f"manual_capture {run_id} on {server_ip}")
        lc.clean_slate(); lc.set_server(); lc.launch_client()
        if not lc.wait_gadget(120):
            nar.say("FATAL: gadget never came up"); return 1
        lc.start_orchestrator()
        nar.mark("CLIENT READY — LOG IN MANUALLY NOW (ENTER long-login) and walk to the spot")
        nar.say(f"capturing trace.jsonl for {a.idle:.0f}s. Trace: {run_dir}/trace.jsonl")
        end = time.time() + a.idle
        while time.time() < end:
            time.sleep(10)
            nar.say(f"…capturing ({int(end - time.time())}s left)")
        nar.mark("CAPTURE_END")
        return 0
    except Exception as e:
        nar.say(f"ERROR: {type(e).__name__}: {e}")
        return 1
    finally:
        lc.teardown(); nar.close()
        print(f"trace: {os.path.join(run_dir, 'trace.jsonl')}")


if __name__ == "__main__":
    sys.exit(main())
