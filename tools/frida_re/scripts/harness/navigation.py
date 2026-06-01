"""Closed-loop WAYPOINT NAVIGATION for the NC2 client harness.

Routes are driven by waypoints (live position feedback) instead of blind
open-loop commands (press-W-for-10s, mouselook-300px). The control loop:

    read live pose -> compute bearing to the next waypoint ->
    mouselook to that heading -> burst forward -> re-read -> correct

Two facts about NC2 make this tractable WITHOUT any orientation field:

  * The server NEVER sends the player's own orientation (proven across
    pcaps — see spawn_facing_client_local.md). So we INFER heading from
    MOTION: tap forward, sample position before/after, the displacement
    vector IS the facing direction (atan2(dy, dx)).

  * Live world position is recoverable two ways and we use BOTH:
      - from the wire: raw 0x20 Movement sub-packets carry [x][z][y]
        floats (decode_pos.py). This re-SEEDS the memory scan.
      - from memory: scan for the position floats to PIN the player
        struct, then re-read it every poll (fast, no wire dependency).

Coordinate convention (verified by decode_pos.py / capture_pos.py):
  Movement sub-packet floats are ordered  [x:f32][z:f32][y:f32]  where
  the MIDDLE float (z) is HEIGHT. The horizontal navigation plane is
  therefore (x, y) = floats #0 and #2. We call that the "XZ distance"
  in the task spec for continuity, but it is the horizontal plane.

Everything here is read-only against the existing harness: it imports
Session/keys and the decode helpers, and adds nothing to them.
"""
from __future__ import annotations

import json
import math
import os
import struct
import time
from dataclasses import dataclass, field
from typing import Callable, Optional

from . import keys as K

# ── Tunable defaults (overridable per-call) ──────────────────────────
W_TAP_MS = 250        # forward tap for heading inference
W_BURST_MS = 600      # forward burst per walk_to iteration
CALIB_PX = 400        # mouselook delta used to calibrate deg/px
TURN_TOL_DEG = 5.0    # acceptable heading error after turning
WALK_TOL = 3.0        # default arrival tolerance (world units)
SETTLE = 0.35         # pause after a motion before re-reading position
MIN_STEP_DISP = 0.4   # below this displacement a tap counts as "blocked"


# ── Geometry helpers (pure, unit-testable) ───────────────────────────
def norm180(deg: float) -> float:
    """Normalize an angle to (-180, 180]."""
    d = (deg + 180.0) % 360.0 - 180.0
    return d + 360.0 if d <= -180.0 else d


def bearing_deg(x0: float, y0: float, x1: float, y1: float) -> float:
    """Compass-free heading (degrees) of the vector (x0,y0)->(x1,y1),
    same atan2(dy, dx) convention used by infer_heading so the two are
    directly comparable."""
    return math.degrees(math.atan2(y1 - y0, x1 - x0))


def plane_dist(x0: float, y0: float, x1: float, y1: float) -> float:
    """Horizontal (x,y) distance — height (the middle float) ignored."""
    return math.hypot(x1 - x0, y1 - y0)


# ── Pose: (x, y, z, zone_id) ─────────────────────────────────────────
@dataclass
class Pose:
    x: float
    y: float
    z: float           # height (middle Movement float)
    zone_id: Optional[str] = None

    def as_tuple(self):
        return (self.x, self.y, self.z, self.zone_id)


@dataclass
class Waypoint:
    x: float
    y: float
    z: float = 0.0
    zone_id: Optional[str] = None
    name: str = ""

    @staticmethod
    def from_dict(d: dict) -> "Waypoint":
        return Waypoint(
            x=float(d["x"]), y=float(d["y"]), z=float(d.get("z", 0.0)),
            zone_id=d.get("zone_id"), name=d.get("name", ""))

    def to_dict(self) -> dict:
        return {"name": self.name, "x": round(self.x, 3),
                "y": round(self.y, 3), "z": round(self.z, 3),
                "zone_id": self.zone_id}


# ── Input injection (pluggable: SendInput vs orchestrator RawInput) ──
class Injector:
    """Abstracts the two motion primitives the navigator needs —
    forward-walk and mouselook-turn — so callers can pick the most
    reliable input path. NC2's engine reads keys via RawInput/DirectInput
    and SendInput key-downs are only ~50% reliable under Wine, so the
    runner wires `forward` to the orchestrator's RawInput hold and keeps
    `look` on SendInput mouse motion (which the camera DOES read)."""

    def __init__(self, session, *, forward_fn=None, look_fn=None,
                 fwd_vk: int = K.W):
        self.sess = session
        self.fwd_vk = fwd_vk
        self._forward = forward_fn
        self._look = look_fn

    def forward(self, ms: int):
        if self._forward is not None:
            self._forward(ms)
        else:
            self.sess.hold(self.fwd_vk, ms)

    def look(self, dx: int, *, hold_rmb: bool = False):
        if self._look is not None:
            self._look(dx, hold_rmb)
        else:
            self.sess.look(dx, hold_rmb=hold_rmb)


def session_injector(session) -> "Injector":
    """Default injector: everything via the Session (SendInput)."""
    return Injector(session)


def rawinput_injector(session, lifecycle) -> "Injector":
    """Movement via the orchestrator's RawInput (`ri_key`, reliable under
    Wine); mouselook via Session mouse motion."""
    return Injector(session, forward_fn=lambda ms: lifecycle.ri_key(K.W, ms))


@dataclass
class Calibration:
    deg_per_px: float
    units_per_sec: float = 0.0          # walking speed, measured
    seeded_at: float = field(default_factory=time.time)

    def to_dict(self):
        return {"deg_per_px": self.deg_per_px,
                "units_per_sec": self.units_per_sec}


# ── Wire / trace decoding (re-seed the memory scan, track zone) ───────
def _iter_subpackets(plain: bytes):
    """Yield each sub-packet body from a 0x13 application frame."""
    if not plain or plain[0] != 0x13:
        return
    i, n = 5, len(plain)
    while i + 2 <= n:
        sub_len = plain[i] | (plain[i + 1] << 8)
        i += 2
        if sub_len <= 0 or i + sub_len > n:
            return
        yield plain[i:i + sub_len]
        i += sub_len


def _movement_pos(sub: bytes):
    """raw 0x20 Movement -> (x, z, y) floats, or None.  z is height."""
    if sub and sub[0] == 0x20 and len(sub) >= 16:
        try:
            x = struct.unpack_from("<f", sub, 4)[0]
            z = struct.unpack_from("<f", sub, 8)[0]
            y = struct.unpack_from("<f", sub, 12)[0]
        except struct.error:
            return None
        if all(abs(v) < 1e6 for v in (x, y, z)):
            return (x, z, y)
    return None


def _bsp_zone(plain: bytes):
    """If `plain` is a TCP 0x83/0x0c Location packet, return its bsp_path
    (the zone identity, e.g. 'plaza/plaza_p1'). Layout per
    tcp_830c_bsp_path_decoded.md: door_id:1 pad:3 ?:4 spawn_flag:4 then a
    null-terminated ASCII bsp_path. We just scan for the printable path
    token, which is robust to the exact header length."""
    if len(plain) < 6:
        return None
    # find a run of printable chars containing a '/' (the bsp path)
    s = bytes(b if 32 <= b < 127 else 0 for b in plain)
    for token in s.split(b"\x00"):
        t = token.decode("latin1", "ignore").strip()
        if "/" in t and len(t) >= 5 and all(
                c.isalnum() or c in "/_-" for c in t):
            return t
    return None


class TraceReader:
    """Tails a trace.jsonl, surfacing the latest wire position and the
    current zone (from TCP Location packets). Position is the primary
    SEED for the memory scan; zone is tracked across crossings."""

    def __init__(self, trace_path: str):
        self.trace_path = trace_path
        self._off = 0
        self.last_pos = None          # (x, z, y) — z is height
        self.zone_id = None
        self.zone_history = []        # [(ts, zone_id)]

    def poll(self) -> None:
        if not os.path.isfile(self.trace_path):
            return
        with open(self.trace_path, "r", encoding="utf-8",
                  errors="ignore") as f:
            f.seek(self._off)
            chunk = f.read()
            self._off = f.tell()
        for line in chunk.splitlines():
            if '"plain_hex"' not in line:
                continue
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                continue
            ph = e.get("plain_hex")
            if not ph:
                continue
            try:
                plain = bytes.fromhex(ph)
            except ValueError:
                continue
            ev = e.get("ev", "")
            if ev in ("udp_send", "udp_recv"):
                for sub in _iter_subpackets(plain):
                    p = _movement_pos(sub)
                    if p:
                        self.last_pos = p
            elif ev in ("tcp_send", "tcp_recv"):
                z = _bsp_zone(plain)
                if z and z != self.zone_id:
                    self.zone_id = z
                    self.zone_history.append((e.get("ts_ns"), z))


# ── Memory position pinning (fast per-poll reads) ────────────────────
class PositionPin:
    """Pins the player position struct in client memory by scanning for
    the live wire position floats, then re-reads X/Y/Z from that address
    on every poll. Re-pins automatically if the read drifts implausibly
    (struct moved / wrong candidate)."""

    def __init__(self, session, trace: "TraceReader"):
        self.sess = session
        self.trace = trace
        self.addr = None        # base address (hex str) of the X float
        self._layout = None     # (dx_off, dz_off, dy_off) byte offsets

    def _candidates_from_seed(self, x, z, y):
        """Find struct addresses where X is followed by Z (+4) and Y
        (+8) — the Movement [x][z][y] layout in memory."""
        ax = [int(a, 16) for a in self.sess.scan(x, "f32", cap=80)]
        az = set(int(a, 16) for a in self.sess.scan(z, "f32", cap=80))
        ay = set(int(a, 16) for a in self.sess.scan(y, "f32", cap=80))
        hits = []
        for a in ax:
            # primary: contiguous [x][z][y] at +0/+4/+8
            if a + 4 in az and a + 8 in ay:
                hits.append((a, (0, 4, 8)))
            # fallback: [x]..[y] 8 apart with z somewhere between
            elif a + 8 in ay:
                hits.append((a, (0, 4, 8)))
        return hits

    def pin(self, seed=None) -> bool:
        """Pin the struct from a seed (x,z,y). Seed defaults to the last
        wire position. Returns True on success."""
        self.trace.poll()
        seed = seed or self.trace.last_pos
        if not seed:
            return False
        x, z, y = seed
        cands = self._candidates_from_seed(x, z, y)
        if not cands:
            return False
        # Prefer the candidate whose re-read matches the seed best.
        best, best_err = None, 1e18
        for a, layout in cands[:16]:
            rd = self._read_at(a, layout)
            if rd is None:
                continue
            err = abs(rd[0] - x) + abs(rd[2] - y)
            if err < best_err:
                best, best_err, self._layout = a, err, layout
        if best is None:
            best, self._layout = cands[0]
        self.addr = hex(best)
        return True

    def _read_at(self, addr, layout):
        d = self.sess.dump_at(hex(addr) if isinstance(addr, int) else addr,
                              0, 16)
        if not d or not d.get("f32"):
            return None
        f = d["f32"]
        try:
            dx, dz, dy = layout
            x = f[dx // 4]
            z = f[dz // 4]
            y = f[dy // 4]
        except (IndexError, TypeError):
            return None
        if any(v is None for v in (x, y, z)):
            return None
        return (x, z, y)

    def read(self):
        """Return (x, z, y) from the pinned struct, re-pinning on drift.
        z is height."""
        if self.addr is None and not self.pin():
            return None
        rd = self._read_at(self.addr, self._layout)
        if rd is None or any(abs(v) > 1e6 for v in rd):
            # struct moved or bad candidate — re-pin from fresh wire pos
            if self.pin():
                rd = self._read_at(self.addr, self._layout)
        return rd


# ── Pose read ────────────────────────────────────────────────────────
def read_pose(session, pin: "PositionPin", trace: "TraceReader") -> Optional[Pose]:
    """(x, y, z, zone_id) from the live read. Prefers the pinned memory
    struct (fast); falls back to the latest wire Movement position."""
    trace.poll()
    rd = pin.read()
    if rd is None:
        rd = trace.last_pos          # (x, z, y)
    if rd is None:
        return None
    x, z, y = rd
    return Pose(x=x, y=y, z=z, zone_id=trace.zone_id)


# ── Heading inference (motion vector == facing) ──────────────────────
def infer_heading(session, pin, trace, inj: "Injector", *,
                  tap_ms: int = W_TAP_MS,
                  settle: float = SETTLE) -> Optional[float]:
    """Tap forward, measure the displacement, return its heading in
    degrees (atan2(dy, dx)). None if the player didn't move enough
    (blocked / no position read)."""
    before = read_pose(session, pin, trace)
    if before is None:
        return None
    inj.forward(tap_ms)
    time.sleep(settle)
    after = read_pose(session, pin, trace)
    if after is None:
        return None
    disp = plane_dist(before.x, before.y, after.x, after.y)
    if disp < MIN_STEP_DISP:
        return None
    return bearing_deg(before.x, before.y, after.x, after.y)


# ── Mouse calibration ────────────────────────────────────────────────
def calibrate_turn(session, pin, trace, inj: "Injector", *,
                   dx: int = CALIB_PX,
                   hold_rmb: bool = False) -> Optional[Calibration]:
    """Mouselook a known `dx` px and measure how many DEGREES the
    inferred heading changed -> deg/px. Also measures walking speed
    (units/sec) from the heading taps. Cached value should be reused for
    the rest of the route (sensitivity is constant per session)."""
    h0 = infer_heading(session, pin, trace, inj)
    if h0 is None:
        return None
    # measure walk speed off a longer, timed forward burst
    p0 = read_pose(session, pin, trace)
    t0 = time.time()
    inj.forward(800)
    time.sleep(SETTLE)
    p1 = read_pose(session, pin, trace)
    dt = max(1e-3, time.time() - t0 - SETTLE)
    ups = (plane_dist(p0.x, p0.y, p1.x, p1.y) / dt) if (p0 and p1) else 0.0

    inj.look(dx, hold_rmb=hold_rmb)
    time.sleep(SETTLE)
    h1 = infer_heading(session, pin, trace, inj)
    if h1 is None:
        return None
    dd = abs(norm180(h1 - h0))
    if dd < 1.0:
        return None
    return Calibration(deg_per_px=dd / abs(dx), units_per_sec=ups)


# ── Turn to an absolute heading ──────────────────────────────────────
def turn_to_heading(session, pin, trace, inj: "Injector", target_deg: float,
                    calib: "Calibration", *, tol: float = TURN_TOL_DEG,
                    max_iters: int = 5, hold_rmb: bool = False) -> bool:
    """Mouselook until the inferred heading is within `tol` of
    target_deg. Re-infers and corrects each iteration. deg/px from
    calib turns a heading error into a pixel delta."""
    dpp = calib.deg_per_px or 0.1
    for _ in range(max_iters):
        cur = infer_heading(session, pin, trace, inj)
        if cur is None:
            return False               # blocked — caller handles
        err = norm180(target_deg - cur)
        if abs(err) <= tol:
            return True
        # +dx turns right; sign maps a +heading error to a right turn.
        px = int(round(err / dpp))
        px = max(-1500, min(1500, px))
        if px == 0:
            return True
        inj.look(px, hold_rmb=hold_rmb)
        time.sleep(SETTLE)
    cur = infer_heading(session, pin, trace, inj)
    return cur is not None and abs(norm180(target_deg - cur)) <= tol * 2


# ── Walk to a single waypoint ────────────────────────────────────────
@dataclass
class WalkResult:
    reached: bool
    reason: str
    start: Optional[Pose]
    end: Optional[Pose]
    dist: float
    iters: int
    seconds: float


def walk_to(session, pin, trace, inj: "Injector", wp: "Waypoint",
            calib: "Calibration", *,
            tol: float = WALK_TOL, timeout_s: float = 30.0,
            burst_ms: int = W_BURST_MS, stuck_iters: int = 4,
            hold_rmb: bool = False, log=print) -> WalkResult:
    """Closed-loop approach: read pose -> bearing to wp -> turn -> burst
    forward -> repeat until within tol or timeout/stuck."""
    t_start = time.time()
    start = read_pose(session, pin, trace)
    if start is None:
        return WalkResult(False, "no_pose", None, None, 0.0, 0, 0.0)
    last_dist = plane_dist(start.x, start.y, wp.x, wp.y)
    no_progress = 0
    iters = 0
    pose = start
    while time.time() - t_start < timeout_s:
        iters += 1
        pose = read_pose(session, pin, trace) or pose
        dist = plane_dist(pose.x, pose.y, wp.x, wp.y)
        if dist <= tol:
            return WalkResult(True, "reached", start, pose, dist, iters,
                              time.time() - t_start)
        # progress check
        if dist < last_dist - 0.5:
            no_progress = 0
        else:
            no_progress += 1
        last_dist = min(last_dist, dist)
        if no_progress >= stuck_iters:
            return WalkResult(False, "stuck", start, pose, dist, iters,
                              time.time() - t_start)
        # steer
        target = bearing_deg(pose.x, pose.y, wp.x, wp.y)
        ok_turn = turn_to_heading(session, pin, trace, inj, target, calib,
                                  hold_rmb=hold_rmb)
        if not ok_turn:
            # heading inference blocked (likely against a wall) — nudge
            log(f"  [walk] turn/infer blocked at dist={dist:.1f}; nudging")
        # scale burst toward the remaining distance (avoid overshoot)
        ms = burst_ms
        if calib.units_per_sec > 1.0:
            need_s = dist / calib.units_per_sec
            ms = int(max(120, min(burst_ms, need_s * 1000.0)))
        inj.forward(ms)
        time.sleep(SETTLE)
    pose = read_pose(session, pin, trace) or pose
    dist = plane_dist(pose.x, pose.y, wp.x, wp.y)
    return WalkResult(dist <= tol, "timeout" if dist > tol else "reached",
                      start, pose, dist, iters, time.time() - t_start)


# ── Follow a multi-waypoint route (handles zone crossings) ───────────
def follow_route(session, pin, trace, inj, waypoints, calib, *,
                 lifecycle=None, on_zone_change: Optional[Callable] = None,
                 tol: float = WALK_TOL, timeout_s: float = 45.0,
                 zone_wait_s: float = 30.0, log=print) -> list:
    """walk_to each waypoint in order. When a waypoint's zone_id differs
    from the current zone, walking into the border triggers the zoning
    handshake: after the walk, wait for the live zone_id to change (and,
    if a Lifecycle is given, for sustained UDP / in-world), log the
    crossing, then continue.

    Returns a per-waypoint result log."""
    results = []
    for idx, wp in enumerate(waypoints):
        trace.poll()
        cur_zone = trace.zone_id
        crossing = wp.zone_id is not None and wp.zone_id != cur_zone
        label = wp.name or f"wp{idx}"
        log(f"[route] -> {label} ({wp.x:.1f},{wp.y:.1f}) "
            f"zone={wp.zone_id or cur_zone}"
            f"{'  [ZONE CROSS]' if crossing else ''}")

        wr = walk_to(session, pin, trace, inj, wp, calib, tol=tol,
                     timeout_s=timeout_s, log=log)
        entry = {
            "idx": idx, "name": label, "target": wp.to_dict(),
            "reached": wr.reached, "reason": wr.reason, "dist": round(wr.dist, 2),
            "iters": wr.iters, "seconds": round(wr.seconds, 1),
            "from_zone": cur_zone, "zone_crossed": False, "new_zone": None,
        }

        if crossing:
            # The act of walking into the border fires the handshake.
            crossed = _await_zone_change(trace, cur_zone, zone_wait_s,
                                         lifecycle)
            entry["zone_crossed"] = crossed
            entry["new_zone"] = trace.zone_id
            if crossed:
                log(f"[route] zone crossed: {cur_zone} -> {trace.zone_id}")
                if on_zone_change:
                    on_zone_change(cur_zone, trace.zone_id, idx)
                # re-pin the position struct in the new zone's memory
                pin.pin()
            else:
                log(f"[route] WARNING: zone did not change from {cur_zone}")
        results.append(entry)
        log(f"[route]    {label}: reached={wr.reached} reason={wr.reason} "
            f"dist={wr.dist:.1f} t={wr.seconds:.1f}s")
    return results


def _await_zone_change(trace, from_zone, wait_s, lifecycle) -> bool:
    end = time.time() + wait_s
    while time.time() < end:
        trace.poll()
        if trace.zone_id and trace.zone_id != from_zone:
            if lifecycle is not None:
                lifecycle.wait_inworld(min(10, wait_s), min_recv=20)
            return True
        time.sleep(0.5)
    return False
