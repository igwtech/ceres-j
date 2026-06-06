#!/usr/bin/env python3
"""charselect_probe.py — fast login→char-select probe (no world entry).

A trimmed sibling of run_pass.py for CharList / char-select experiments
(e.g. slot-count, char_count caps, per-slot field changes). It launches
the instrumented client, logs in via the ENTER path with the given
account, screenshots the char-select screen, and reports any new client
error-log lines — then tears down. Skips the in-world wait + step list,
so a pass is ~30-40s instead of ~4 min.

  ./charselect_probe.py ceres                         # msn3wolf vs Ceres-J
  ./charselect_probe.py ceres --user testbot --password testpw
  ./charselect_probe.py retail --run-id rt_charsel

It also auto-detects a likely crash: if the client process dies before
char-select (as a count>4 CharList does), that's reported with the new
error-log tail. Pair with Ceres-J `CharsPerAccount` to drive the count.

Artifacts: frida_re/runs/<run_id>/  (00_menu, login_1_creds,
login_4_charselect[.b], errorlog_delta.txt, marks.log).
"""
from __future__ import annotations
import argparse
import glob
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
ERRLOG_GLOB = os.path.join(NC2_DIR, "logs", "error_*.log")

T_GADGET_WAIT = 90
T_AGENT_WAIT = 60
T_MENU_SETTLE = 10


def newest_errlog() -> str | None:
    files = sorted(glob.glob(ERRLOG_GLOB), key=os.path.getmtime, reverse=True)
    return files[0] if files else None


def errlog_size(path: str | None) -> int:
    try:
        return os.path.getsize(path) if path else 0
    except OSError:
        return 0


def errlog_delta(path: str | None, since: int) -> str:
    if not path:
        return ""
    try:
        with open(path, "rb") as f:
            f.seek(since)
            return f.read().decode("latin-1", "replace")
    except OSError:
        return ""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("server", choices=list(SERVERS))
    ap.add_argument("--user", default="msn3wolf")
    ap.add_argument("--password", default="sprlnk2kk")
    ap.add_argument("--run-id")
    args = ap.parse_args()

    server_ip = SERVERS[args.server]
    run_id = args.run_id or f"charsel_{args.server}_{time.strftime('%Y%m%d_%H%M%S')}"
    run_dir = os.path.join(RUNS_ROOT, run_id)
    os.makedirs(run_dir, exist_ok=True)

    nar = Narrator(os.path.join(run_dir, "marks.log"))
    lc = Lifecycle(server_ip, run_dir, scripts_dir=_HERE,
                   frida_re_dir=FRIDA_RE_DIR, py=PY, nc2_dir=NC2_DIR,
                   port=GADGET_PORT)

    def shot(name, why=""):
        ok = sess.screenshot(os.path.join(run_dir, name), device)
        nar.say(f"screenshot {name}{(' — ' + why) if why else ''}"
                f"{'' if ok else ' [FAILED]'}")
        return ok

    sess = None
    try:
        nar.mark(f"charselect probe {run_id}  server={args.server} "
                 f"({server_ip})  user={args.user}")
        lc.clean_slate()
        lc.set_server()
        # baseline the client error log so we can report only NEW lines
        err_path = newest_errlog()
        err_base = errlog_size(err_path)
        lc.launch_client()
        if not lc.wait_gadget(T_GADGET_WAIT):
            nar.say("FATAL: gadget never came up"); return 1
        lc.start_orchestrator()
        device = lc.wait_device(T_AGENT_WAIT)
        if not device:
            nar.say("FATAL: no device candidates in trace"); return 1
        nar.say(f"live D3D9 device: {device}")
        sess = Session(port=GADGET_PORT)
        client_proc = getattr(lc, "_client", None)

        time.sleep(T_MENU_SETTLE)
        shot("00_menu.png", "main menu before login")

        nar.say(f"ENTER-path login as {args.user}.")
        sess.focus()
        sess.type_text(args.user)
        sess.key(K.TAB)
        sess.type_text(args.password)
        shot("login_1_creds.png", "credentials typed")
        sess.keys([K.TAB, K.TAB])
        sess.key(K.ENTER)
        time.sleep(3)
        shot("login_3_after_enter.png", "after ENTER (server select?)")
        sess.keys([K.TAB, K.ENTER])              # confirm server
        time.sleep(4)
        shot("login_4_charselect.png", "CHAR-SELECT — count the slot panels")
        time.sleep(2)
        shot("login_4b_charselect.png", "char-select (second frame)")

        # crash detection + error-log delta (catches count>4 CharList kill)
        # newest error log may have rotated to a new file post-launch:
        cur_path = newest_errlog()
        if cur_path != err_path:
            err_path, err_base = cur_path, 0
        delta = errlog_delta(err_path, err_base)
        with open(os.path.join(run_dir, "errorlog_delta.txt"), "w") as f:
            f.write(f"# {err_path}\n{delta}")
        crashed = client_proc is not None and client_proc.poll() is not None
        tail = "\n".join(delta.strip().splitlines()[-8:])
        if crashed:
            nar.say(f"CLIENT DIED before/at char-select (exit "
                    f"{client_proc.returncode}) — likely rejected the "
                    f"CharList. New error-log tail:\n{tail}")
            return 2
        nar.say(f"reached char-select; client alive. New error-log tail:\n"
                f"{tail or '(none)'}")
        return 0
    except Exception as e:
        nar.say(f"FATAL: {type(e).__name__}: {e}")
        return 1
    finally:
        if sess is not None:
            try:
                sess.close()
            except Exception:
                pass
        lc.teardown()


if __name__ == "__main__":
    sys.exit(main())
