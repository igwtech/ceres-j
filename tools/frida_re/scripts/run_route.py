#!/usr/bin/env python3
"""run_route.py — launch the NC2 client, log in, then DRIVE a recorded
waypoint route closed-loop (follow_route), saving the run artifacts so
the SAME route can be run on ceres AND retail and the zoning bursts
byte-diffed.

    run_route.py <ceres|retail> <route.json> [--no-rmb]

Artifacts land in  frida_re/runs/route_<server>_<ts>/ :
    trace.jsonl          decrypted wire (the whole run, incl. zoning bursts)
    route_result.json    per-waypoint log (reached/failed, time, zone)
    calibration.json     deg/px + units/sec measured this session
    zone_windows/        trace slice around each zone crossing (for diff)
    summary.txt          human summary

The end goal: instrument the same Plaza1->Plaza3->PepperPark1 route on
ceres and retail, then byte-diff the 0x83/0x0c Location + 0x03/0x22/0x0d
Zoning1 + start-ack burst at each crossing (the splash + reset-to-center
bugs live there).
"""
from __future__ import annotations
import argparse
import json
import os
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)

from harness.session import Session          # noqa: E402
from harness.lifecycle import Lifecycle      # noqa: E402
from harness import navigation as nav        # noqa: E402
from harness import keys as K                # noqa: E402

SERVERS = {"ceres": "172.18.0.3", "retail": "157.90.195.74"}
FRIDA_RE_DIR = os.path.dirname(_HERE)


def enter_login(sess):
    sess.focus()
    sess.type_text("msn3wolf"); sess.key(K.TAB)
    sess.type_text("sprlnk2kk")
    sess.keys([K.TAB, K.TAB]); sess.key(K.ENTER); time.sleep(3)
    sess.keys([K.TAB, K.ENTER]); time.sleep(3)
    sess.key(K.ENTER); time.sleep(3)


def load_waypoints(path):
    with open(path) as f:
        data = json.load(f)
    wps = [nav.Waypoint.from_dict(d) for d in data.get("waypoints", [])]
    return wps


def slice_zone_windows(trace_path, crossings, out_dir, pad_lines=400):
    """For each zone crossing, dump a slice of the trace around it so the
    two servers' handshake bursts can be diffed side by side. We key off
    the TCP Location (0x83/0x0c) line index for each new zone."""
    os.makedirs(out_dir, exist_ok=True)
    if not os.path.isfile(trace_path):
        return []
    with open(trace_path, encoding="utf-8", errors="ignore") as f:
        lines = f.readlines()
    written = []
    for c in crossings:
        new_zone = c.get("new_zone")
        if not new_zone:
            continue
        # find the line index where this zone's bsp_path first appears
        hit = None
        for i, ln in enumerate(lines):
            if new_zone.encode().hex() in ln or new_zone in ln:
                hit = i
                break
        if hit is None:
            continue
        lo = max(0, hit - pad_lines)
        hi = min(len(lines), hit + pad_lines)
        safe = new_zone.replace("/", "_")
        path = os.path.join(out_dir, f"cross_{c['idx']:02d}_{safe}.jsonl")
        with open(path, "w") as f:
            f.writelines(lines[lo:hi])
        written.append(path)
    return written


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("server", choices=list(SERVERS))
    ap.add_argument("route")
    ap.add_argument("--no-rmb", action="store_true",
                    help="don't hold right mouse button while mouselooking")
    ap.add_argument("--tol", type=float, default=nav.WALK_TOL)
    ap.add_argument("--timeout", type=float, default=45.0)
    args = ap.parse_args()

    server_ip = SERVERS[args.server]
    waypoints = load_waypoints(args.route)
    if not waypoints:
        print(f"FATAL: no waypoints in {args.route}"); return 2
    hold_rmb = not args.no_rmb

    run_dir = os.path.join(FRIDA_RE_DIR, "runs",
                           f"route_{args.server}_{time.strftime('%Y%m%d_%H%M%S')}")
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
        print(f"[route] menu settle 12s ({args.server})"); time.sleep(12)
        print("[route] login (ENTER path)"); enter_login(sess)
        if not lc.wait_inworld(60):
            print("FATAL: never in-world"); return 1
        print("[route] in-world; settling 4s for Movement seed"); time.sleep(4)

        trace = nav.TraceReader(lc.trace)
        pin = nav.PositionPin(sess, trace)
        trace.poll(); pin.pin()
        # Movement via the orchestrator's RawInput (reliable under Wine);
        # mouselook via Session mouse motion.
        inj = nav.rawinput_injector(sess, lc)

        print("[route] calibrating turn (deg/px) + walk speed ...")
        calib = nav.calibrate_turn(sess, pin, trace, inj, hold_rmb=hold_rmb)
        if calib is None:
            print("[route] WARNING: calibration failed (player may be "
                  "blocked). Falling back to deg_per_px=0.15.")
            calib = nav.Calibration(deg_per_px=0.15)
        print(f"[route] calibration: deg/px={calib.deg_per_px:.4f}  "
              f"units/sec={calib.units_per_sec:.2f}")
        with open(os.path.join(run_dir, "calibration.json"), "w") as f:
            json.dump(calib.to_dict(), f, indent=2)

        print(f"[route] following {len(waypoints)} waypoints ...")
        results = nav.follow_route(sess, pin, trace, inj, waypoints, calib,
                                   lifecycle=lc, tol=args.tol,
                                   timeout_s=args.timeout)

        crossings = [r for r in results if r.get("zone_crossed")
                     or r.get("from_zone") != r.get("new_zone")]
        windows = slice_zone_windows(
            lc.trace, [r for r in results if r.get("new_zone")],
            os.path.join(run_dir, "zone_windows"))

        with open(os.path.join(run_dir, "route_result.json"), "w") as f:
            json.dump({"server": args.server, "route": args.route,
                       "calibration": calib.to_dict(),
                       "results": results}, f, indent=2)

        reached = sum(1 for r in results if r["reached"])
        zone_xs = sum(1 for r in results if r.get("zone_crossed"))
        summary = [
            f"route run: {args.server} ({server_ip})",
            f"route file: {args.route}",
            f"waypoints reached: {reached}/{len(results)}",
            f"zone crossings: {zone_xs}",
            f"calibration: deg/px={calib.deg_per_px:.4f} "
            f"units/sec={calib.units_per_sec:.2f}",
            f"trace: {lc.trace}",
            f"zone windows: {len(windows)} -> {os.path.join(run_dir, 'zone_windows')}",
            "",
            "per-waypoint:",
        ]
        for r in results:
            summary.append(
                f"  {r['idx']:>2} {r['name']:<16} reached={r['reached']!s:<5} "
                f"reason={r['reason']:<8} dist={r['dist']:<6} "
                f"t={r['seconds']}s  zone:{r['from_zone']}->{r['new_zone']}")
        txt = "\n".join(summary)
        with open(os.path.join(run_dir, "summary.txt"), "w") as f:
            f.write(txt + "\n")
        print("\n" + txt)
        print(f"\n[route] artifacts in {run_dir}")
        return 0
    except Exception as e:
        print(f"ROUTE ERROR: {type(e).__name__}: {e}")
        return 1
    finally:
        if sess:
            sess.close()
        lc.teardown()


if __name__ == "__main__":
    sys.exit(main())
