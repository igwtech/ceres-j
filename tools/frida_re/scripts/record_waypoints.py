#!/usr/bin/env python3
"""record_waypoints.py — launch the NC2 client, log in, then RECORD a
route by snapshotting the live player pose whenever the human presses
Enter in this controlling terminal.

    record_waypoints.py <ceres|retail> [route.json]

How to record (the human-in-the-loop part):
  1. Run this. It launches Wine+Frida, logs in (ENTER long-login path),
     and waits for the player to be in-world.
  2. It then prints the LIVE pose (x, y, z, zone) once a second to this
     terminal, AND it watches stdin.
  3. Switch focus to the GAME window and WALK the character around with
     the keyboard (W/A/S/D + mouselook) to where you want a waypoint.
  4. Switch back to THIS terminal and press <Enter> to DROP a waypoint
     at the current pose. Optionally type a name first, then Enter:
         > pepperpark_gate
     appends a named waypoint.
  5. Type  q  then Enter (or Ctrl-D / Ctrl-C) to STOP. The route is
     written to route.json as you go (append-safe) and finalized on exit.

Goal route for the zoning byte-diff work:
     Plaza1  ->  Plaza3  ->  PepperPark1
  Walk to each border, drop a waypoint just BEFORE the transition and one
  just AFTER (in the new zone) so run_route can detect the crossing and
  the trace captures the handshake burst on both sides.

The recorder reads pose exactly like the navigator (pinned memory struct
+ wire fallback) so recorded points are in the same coordinate frame the
runner drives in.
"""
from __future__ import annotations
import json
import os
import select
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
    """ENTER long-login path (same as capture_pos.py)."""
    sess.focus()
    sess.type_text("msn3wolf"); sess.key(K.TAB)
    sess.type_text("sprlnk2kk")
    sess.keys([K.TAB, K.TAB]); sess.key(K.ENTER); time.sleep(3)
    sess.keys([K.TAB, K.ENTER]); time.sleep(3)
    sess.key(K.ENTER); time.sleep(3)


def _load_route(path):
    if os.path.isfile(path):
        try:
            with open(path) as f:
                data = json.load(f)
            return data.get("waypoints", []), data.get("server")
        except (json.JSONDecodeError, OSError):
            pass
    return [], None


def _save_route(path, server, waypoints):
    with open(path, "w") as f:
        json.dump({"server": server, "recorded": time.strftime("%Y-%m-%d %H:%M:%S"),
                   "waypoints": waypoints}, f, indent=2)


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in SERVERS:
        print(__doc__)
        return 2
    server = sys.argv[1]
    route_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        FRIDA_RE_DIR, "routes", f"route_{server}.json")
    os.makedirs(os.path.dirname(route_path), exist_ok=True)
    server_ip = SERVERS[server]

    run_dir = os.path.join(FRIDA_RE_DIR, "runs",
                           f"record_{server}_{time.strftime('%Y%m%d_%H%M%S')}")
    os.makedirs(run_dir, exist_ok=True)
    lc = Lifecycle(server_ip, run_dir, scripts_dir=_HERE,
                   frida_re_dir=FRIDA_RE_DIR, py=sys.executable)

    waypoints, _ = _load_route(route_path)
    if waypoints:
        print(f"[record] appending to existing route ({len(waypoints)} pts) "
              f"-> {route_path}")

    sess = None
    try:
        lc.clean_slate(); lc.set_server(); lc.launch_client()
        if not lc.wait_gadget(90):
            print("FATAL: no gadget"); return 1
        lc.start_orchestrator()
        if not lc.wait_device(60):
            print("FATAL: no device"); return 1
        sess = Session()
        print(f"[record] menu settle 12s ({server})"); time.sleep(12)
        print("[record] login (ENTER path)"); enter_login(sess)
        if not lc.wait_inworld(60):
            print("FATAL: never in-world"); return 1
        print("[record] in-world; settling 4s for Movement seed"); time.sleep(4)

        trace = nav.TraceReader(lc.trace)
        pin = nav.PositionPin(sess, trace)
        trace.poll()
        if not pin.pin():
            print("[record] WARNING: could not pin position struct yet — "
                  "walk a step in-game to emit a Movement packet, it will "
                  "pin on the next pose read.")

        print("\n" + "=" * 64)
        print("RECORDING. Walk the character in the GAME window.")
        print("Press <Enter> here to DROP a waypoint at the current pose.")
        print("Type a name then <Enter> to name it. Type 'q'+<Enter> to stop.")
        print("=" * 64 + "\n")

        last_print = 0.0
        while True:
            # 1s live pose print + non-blocking stdin watch
            rlist, _, _ = select.select([sys.stdin], [], [], 0.25)
            now = time.time()
            pose = nav.read_pose(sess, pin, trace)
            if now - last_print >= 1.0:
                last_print = now
                if pose:
                    print(f"  pose  x={pose.x:9.2f}  y={pose.y:9.2f}  "
                          f"z={pose.z:9.2f}  zone={pose.zone_id}   "
                          f"[{len(waypoints)} wp]   "
                          f"(Enter=drop, q=quit)", flush=True)
                else:
                    print("  pose  <no read yet — walk a step in-game>",
                          flush=True)
            if rlist:
                line = sys.stdin.readline()
                if line == "":               # EOF (Ctrl-D)
                    break
                cmd = line.strip()
                if cmd.lower() in ("q", "quit", "exit"):
                    break
                if pose is None:
                    print("  [skip] no pose to record yet")
                    continue
                name = cmd or f"wp{len(waypoints)}"
                wp = {"name": name, "x": round(pose.x, 3),
                      "y": round(pose.y, 3), "z": round(pose.z, 3),
                      "zone_id": pose.zone_id}
                waypoints.append(wp)
                _save_route(route_path, server, waypoints)
                print(f"  >> DROPPED waypoint '{name}'  "
                      f"({wp['x']},{wp['y']},{wp['z']}) zone={wp['zone_id']} "
                      f"-> saved ({len(waypoints)} total)", flush=True)

        _save_route(route_path, server, waypoints)
        print(f"\n[record] saved {len(waypoints)} waypoints -> {route_path}")
        return 0
    except KeyboardInterrupt:
        _save_route(route_path, server, waypoints)
        print(f"\n[record] interrupted; saved {len(waypoints)} waypoints "
              f"-> {route_path}")
        return 0
    finally:
        if sess:
            sess.close()
        lc.teardown()


if __name__ == "__main__":
    sys.exit(main())
