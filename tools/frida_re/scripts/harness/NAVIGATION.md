# Waypoint Navigation (closed-loop NC2 client driving)

Drive the live NC2 client along a route by **waypoints with live position
feedback** instead of blind open-loop commands (press-W-for-10s,
mouselook-300px). Built for instrumenting the SAME route on **ceres** and
**retail** so the zoning handshake bursts can be byte-diffed (the splash +
reset-to-map-center bugs live in that handshake).

New, self-contained — adds nothing to the existing harness scripts:

| File | Purpose |
|------|---------|
| `harness/navigation.py` | the navigator: pose read, heading inference, turn calibration, `walk_to`, `follow_route` |
| `record_waypoints.py`   | human-in-the-loop recorder: walk the char, press Enter to drop points |
| `run_route.py`          | drive a recorded route, save trace + per-waypoint log + zone-cross windows |

## How it works (no orientation field needed)

The server never sends the player's own orientation (proven —
`spawn_facing_client_local.md`). So heading is **inferred from motion**:
tap `W` for ~250 ms, sample position before/after, the displacement vector
*is* the facing direction (`atan2(dy, dx)`).

Live position comes from two sources, used together:
- **wire**: raw `0x20` Movement sub-packets carry `[x:f32][z:f32][y:f32]`
  (the middle float `z` is **height**). Re-seeds the memory scan.
- **memory**: scan for those floats to pin the player struct, then re-read
  it every poll (fast, no wire dependency). Re-pins on drift / zone change.

Horizontal navigation plane is **(x, y)** = floats #0 and #2; height
(float #1) is ignored for distance/bearing.

Zone identity is tracked from TCP `0x83/0x0c` **Location** packets, whose
`bsp_path` (e.g. `plaza/plaza_p1`, `pepper/pepper_p1`) is the zone id.

Control loop in `walk_to`:
```
read pose -> within tol? done : bearing-to-wp -> turn_to_heading ->
burst W (scaled to remaining distance) -> repeat ; detect no-progress -> stuck
```

## 1. Record a route

```
bin/python record_waypoints.py ceres   routes/route_ceres.json
bin/python record_waypoints.py retail  routes/route_retail.json
```

It launches the client, logs in (ENTER long-login path), waits for
in-world, then:
- prints the **live pose** `(x, y, z, zone)` once a second to the terminal;
- watches **stdin** in the same loop.

To record the **Plaza1 -> Plaza3 -> PepperPark1** route:
1. Focus the **game** window, walk with `W/A/S/D` + mouselook.
2. Focus **this terminal**, press `<Enter>` to drop a waypoint at the
   current pose. (Type a name first, e.g. `plaza1_mid`, then `<Enter>`.)
3. Drop a point **just before** each zone border **and one just after**
   (in the new zone) — `run_route` keys the handshake byte-diff off the
   zone_id change between consecutive waypoints.
4. Suggested points: `plaza1_spawn`, `plaza1_to_p3_border`,
   `plaza3_arrival`, `plaza3_to_pepper_border`, `pepper1_arrival`.
5. Type `q` + `<Enter>` (or Ctrl-D) to stop. Route is saved incrementally.

Route JSON shape:
```json
{
  "server": "ceres",
  "waypoints": [
    {"name": "plaza1_spawn", "x": 282.0, "y": -355.0, "z": -271.9,
     "zone_id": "plaza/plaza_p1"}
  ]
}
```
A waypoint whose `zone_id` differs from the previous one is treated as a
**zone crossing**: after `walk_to` reaches the border, the runner waits
for the live `zone_id` to flip and re-pins position in the new zone.

## 2. Run a route

```
bin/python run_route.py ceres  routes/route_ceres.json
bin/python run_route.py retail routes/route_ceres.json   # same route, diff server
```
`--no-rmb` if mouselook works without holding right mouse button;
`--tol <units>` arrival tolerance; `--timeout <s>` per-waypoint cap.

Artifacts in `runs/route_<server>_<ts>/`:
- `trace.jsonl`        full decrypted wire (incl. every zoning burst)
- `route_result.json`  per-waypoint reached/failed, time, zone
- `calibration.json`   deg/px + units/sec measured this session
- `zone_windows/`      trace slice (±400 lines) around each crossing — diff
  the two servers' `cross_NN_<zone>.jsonl` side by side
- `summary.txt`        human summary

## Calibration constants

Measured at runtime each session (sensitivity/mouse accel can vary), saved
to `calibration.json`, and re-used for the rest of that run:

- **deg_per_px** — degrees of heading change per pixel of mouselook
  (`calibrate_turn` mouselooks 400 px and measures the inferred-heading
  delta). Fallback if calibration fails: `0.15`.
- **units_per_sec** — walking speed in world units/sec from a timed
  forward burst; used to scale the `W` burst toward the remaining
  distance so the navigator doesn't overshoot the waypoint.

## Smoke-test status (ceres, 2026-06-01)

End-to-end smoke on **ceres** (172.18.0.3), character Krafteo (`msn3wolf`):

| Stage | Result |
|-------|--------|
| launch + robust login (ENTER path, RawInput retry) | OK — in-world (380 udp_recv) |
| `TraceReader` wire seed | OK — `(x=1381.0, z=-703.9, y=38.0)` from Movement |
| `PositionPin.pin()` | OK — struct pinned; read-back `x=1381, y=38` matches wire |
| `read_pose` | OK — tracks the live position |
| **forward motion** (RawInput / SendInput / down+up hold) | **0.0 units — character does not move** |
| `infer_heading` / `calibrate_turn` | returned None (no displacement to measure) |

**The character could not walk**, so `deg_per_px` / `units_per_sec` could
not be measured live and `walk_to` convergence is unverified on Ceres.
This is a **server/spawn-state condition, not a navigator bug**: the C->S
Movement packets show `x=1381, y=38` **frozen** across every recorded
Ceres run of this character (the prior `equip_ceres_*` runs too) — only
the height float wobbles as the avatar settles. Three independent input
paths (orchestrator RawInput, Session SendInput, explicit key down/up
with a held sleep), each after window focus + a gameplay click + a 12 s
post-spawn settle, all produced zero horizontal displacement. The
inferred heading / calibration logic is therefore exercised but cannot
complete until the character can move.

What this means for the spawn bug: this is exactly the
**reset-to-map-center / spawn-freeze** class of issue this instrumentation
exists to diagnose. Once the character can walk on Ceres (or when running
against **retail**, where movement is known-good), re-run `run_route.py`
and the calibration constants + convergence will populate
`calibration.json` / `route_result.json` automatically.

The geometry, wire/zone decode, position pinning, and input-injector
wiring are all **unit-verified** (pure-function tests pass) and the live
pose read is **confirmed correct against the wire**. Only the
movement-dependent leg is blocked by the spawn condition.

> Tip: validate `walk_to` convergence first on **retail** (known-good
> movement), then bring the same route to Ceres to capture the divergent
> zoning bursts for the byte-diff.

## Tuning knobs (`navigation.py` top of file)
`W_TAP_MS` (heading tap), `W_BURST_MS` (walk burst), `CALIB_PX`
(calibration mouselook), `TURN_TOL_DEG`, `WALK_TOL`, `MIN_STEP_DISP`
(displacement below which a tap counts as blocked).
