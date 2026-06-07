#!/usr/bin/env python3
"""equip_probe.py — isolate the equip/unequip (draw/holster) wire for
quickbelt weapons, with one marker per action so the decrypted trace
brackets each event cleanly.

Login is IDENTICAL to run_pass.py (ENTER long-login, enter world with
the slot-0 char) so a `ceres` run and a `retail` run diff apples-to-
apples via scripts/trace_diff.py. After spawn it:
  1. records a quiet idle baseline (so equip packets stand out),
  2. for each quickbelt slot: presses the slot number (DRAW), settles,
     presses it again (HOLSTER), settles — each wrapped in nar.mark(),
  3. snapshots client state + a frame at each step.

  ./equip_probe.py retail                       # vs retail (source of truth)
  ./equip_probe.py ceres  --slots 1,2 --reps 2  # vs Ceres-J, A/B
  ./equip_probe.py retail --user msn3wolf --password sprlnk2kk --slot-char 0

Decode target (see equip_weapon_draw_triad / c2s_1f_02_1f memories):
  C->S 0x03/0x1f act_tag 0x1f EquipHolster (1B slot bitmask: bit0 primary,
  bit2 quickslot) + the draw triad 0x1f/0x10 + 0x1f/0x30 + 0x03/0x2f.
Artifacts: frida_re/runs/<run_id>/  (trace.jsonl, NN_*.png, NN_state.json,
marks.log). Grep marks.log for DRAW/HOLSTER, then window trace.jsonl by ts.

NOTE: the slot-0 char must actually have weapons in its quickbelt or no
equip packets fire. On retail use a played char; on Ceres seed the
quickbelt first (inventory_view / DB).
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
PY = sys.executable
NC2_DIR = os.environ.get("NC2_DIR", "/home/javier/Neocron2")
GADGET_PORT = 27042

T_GADGET_WAIT = 90
T_AGENT_WAIT = 60
T_MENU_SETTLE = 10
T_LOGIN_WAIT = 60
T_ACTION = 2.0          # settle after each equip/holster (capture window)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("server", choices=list(SERVERS))
    ap.add_argument("run_id", nargs="?")
    ap.add_argument("--user", default="msn3wolf")
    ap.add_argument("--password", default="sprlnk2kk")
    ap.add_argument("--slot-char", type=int, default=0,
                    help="char-select slot to enter world with (ENTER presses)")
    ap.add_argument("--slots", default="1,2,3,4,5",
                    help="quickbelt slot numbers to draw/holster (comma list)")
    ap.add_argument("--reps", type=int, default=2,
                    help="draw/holster cycles per slot (repeatability check)")
    args = ap.parse_args()

    slots = [int(s) for s in args.slots.split(",") if s.strip()]
    server_ip = SERVERS[args.server]
    run_id = args.run_id or f"equip_{args.server}_{time.strftime('%Y%m%d_%H%M%S')}"
    run_dir = os.path.join(RUNS_ROOT, run_id)
    os.makedirs(run_dir, exist_ok=True)

    nar = Narrator(os.path.join(run_dir, "marks.log"))
    lc = Lifecycle(server_ip, run_dir, scripts_dir=_HERE,
                   frida_re_dir=FRIDA_RE_DIR, py=PY, nc2_dir=NC2_DIR,
                   port=GADGET_PORT)

    # epoch-precise markers (same clock domain as trace.jsonl ts_ns) so
    # equip_slice.py can window the decrypted wire exactly per action.
    marks_tsv = open(os.path.join(run_dir, "equip_marks.tsv"), "a")

    def emark(label):
        marks_tsv.write(f"{time.time():.3f}\t{label}\n")
        marks_tsv.flush()
        nar.mark(label)

    def shot(name, why=""):
        ok = sess.screenshot(os.path.join(run_dir, name), device)
        nar.say(f"screenshot {name}{(' — ' + why) if why else ''}"
                f"{'' if ok else ' [FAILED]'}")

    def snapshot(name, label=""):
        if not sess.arm_state(secs=6):
            nar.say(f"STATE ({label}) | CHARSYS base unresolved")
            return
        with open(os.path.join(run_dir, name), "w") as f:
            json.dump(sess.read_state(), f, indent=1)
        nar.state(sess.read_state(), label)

    sess = None
    try:
        nar.mark(f"equip probe {run_id} server={args.server} ({server_ip}) "
                 f"user={args.user} slots={slots} reps={args.reps}")
        lc.clean_slate()
        lc.set_server()
        lc.launch_client()
        if not lc.wait_gadget(T_GADGET_WAIT):
            nar.say("FATAL: gadget never came up"); return 1
        lc.start_orchestrator()
        device = lc.wait_device(T_AGENT_WAIT)
        if not device:
            nar.say("FATAL: no device candidates in trace"); return 1
        nar.say(f"live D3D9 device: {device}")
        sess = Session(port=GADGET_PORT)

        # ── Login: IDENTICAL to run_pass.py (keep in sync for diffing) ──
        time.sleep(T_MENU_SETTLE)
        shot("00_menu.png", "main menu")
        nar.say(f"ENTER-path login as {args.user}.")
        sess.focus()
        sess.type_text(args.user)
        sess.key(K.TAB)
        sess.type_text(args.password)
        sess.keys([K.TAB, K.TAB])
        sess.key(K.ENTER)
        time.sleep(3)
        sess.keys([K.TAB, K.ENTER])               # confirm server
        time.sleep(4)
        shot("login_charselect.png", "char-select")
        for _ in range(args.slot_char):           # move to chosen slot
            sess.key(K.RIGHT)
        sess.key(K.ENTER)                          # enter world
        if not lc.wait_inworld(T_LOGIN_WAIT):
            nar.say("FATAL: never reached in-world (no sustained UDP)")
            return 1
        time.sleep(T_ACTION)
        emark("IN_WORLD")
        shot("01_inworld.png", "spawned in-world")
        snapshot("01_state.json", "inworld")

        # ── Idle baseline (no input) so equip packets stand out ──
        emark("IDLE_BASELINE_START")
        time.sleep(5)
        emark("IDLE_BASELINE_END")
        shot("02_idle.png", "idle baseline")

        # ── Equip / unequip sequence — one marker per action ──
        step = 3
        for n in slots:
            vk = K.digit(n)
            for rep in range(1, args.reps + 1):
                emark(f"DRAW slot{n} rep{rep}")
                sess.key(vk)
                time.sleep(T_ACTION)
                shot(f"{step:02d}_draw_s{n}_r{rep}.png", f"draw quickbelt {n}")
                snapshot(f"{step:02d}_state_draw_s{n}_r{rep}.json", f"draw_s{n}_r{rep}")
                step += 1

                emark(f"HOLSTER slot{n} rep{rep}")
                sess.key(vk)                       # same slot again = holster
                time.sleep(T_ACTION)
                shot(f"{step:02d}_holster_s{n}_r{rep}.png", f"holster quickbelt {n}")
                snapshot(f"{step:02d}_state_holster_s{n}_r{rep}.json", f"holster_s{n}_r{rep}")
                step += 1

        emark("EQUIP_SEQUENCE_DONE")
        nar.say(f"Pass complete. Artifacts in {run_dir}.")
        return 0
    except Exception as e:
        nar.say(f"PASS ERROR: {type(e).__name__}: {e}")
        return 1
    finally:
        if sess is not None:
            try:
                sess.close()
            except Exception:
                pass
        try:
            marks_tsv.close()
        except Exception:
            pass
        lc.teardown()
        nar.close()


if __name__ == "__main__":
    sys.exit(main())
