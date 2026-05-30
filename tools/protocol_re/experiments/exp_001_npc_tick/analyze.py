#!/usr/bin/env python3
"""analyze.py — phase-aware diff for HYP-001 NPC tick experiment.

Reads the labeled pcap + markers from capture.sh and produces a
byte-by-byte report showing which offsets of the `0x03/0x2d`
S->C record vary with which observable game state (idle, walking,
attacking, dying, dead).

Algorithm:
  1. Parse markers → phase windows by timestamp.
  2. Walk pcap, decrypt UDP, frame reliable subs.
  3. Extract every `0x03/0x2d` S->C inner body.
  4. Tag each sample with the phase it falls into.
  5. Group samples by entity-id heuristic (the LE16/LE32 we
     suspect carries it — auto-detected by "most stable bytes
     within the session").
  6. For each byte offset, compute:
       - distinct values seen per phase
       - mean / variance per phase
       - "phase-discriminator score" (offsets where idle vs combat
         distributions differ the most)
  7. Print a markdown report sorted by discriminator strength.

This gives us a ranked list of offsets to focus on: "byte at
offset N changes only between phase A and C → it's the state
flag" / "bytes 12..15 are monotonic-decreasing only in phase D
→ HP".
"""
from __future__ import annotations

import argparse
import statistics
import struct
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple

sys.path.insert(
    0,
    str(Path(__file__).resolve().parents[2]),  # tools/
)
from importlib import import_module
burst_mod = import_module("parse-burst")


# ─── Markers ───────────────────────────────────────────────────────────

@dataclass
class PhaseWindow:
    label: str
    start: float
    end: float


def parse_markers(path: Path) -> List[Tuple[float, str]]:
    out = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(None, 1)
        if len(parts) != 2:
            continue
        try:
            ts = float(parts[0])
        except ValueError:
            continue
        out.append((ts, parts[1]))
    return out


def build_phases(markers: List[Tuple[float, str]]) -> List[PhaseWindow]:
    """Pair START/END markers into phase windows."""
    # We care about the experimental phases only:
    phases = [
        ("idle",     "P1_IDLE_START",     "P1_IDLE_END"),
        ("walking",  "P2_WALK_START",    "P2_WALK_END"),
        ("aggro",    "P3_AGGRO_START",   "P3_AGGRO_END"),
        ("killing",  "P4_KILL_START",    "P4_KILL_END"),
        ("postdead", "P5_POSTDEATH_START", "P5_POSTDEATH_END"),
    ]
    by_label = {l: t for (t, l) in markers}
    out = []
    for name, sl, el in phases:
        if sl in by_label and el in by_label:
            out.append(PhaseWindow(name, by_label[sl], by_label[el]))
        else:
            print(f"  WARN: missing markers for phase {name} "
                  f"({sl}/{el})", file=sys.stderr)
    return out


def phase_for_ts(ts: float, phases: List[PhaseWindow]
                  ) -> Optional[str]:
    for p in phases:
        if p.start <= ts <= p.end:
            return p.label
    return None


# ─── Pcap walk + 0x03/0x2d extraction ─────────────────────────────────

@dataclass
class TickSample:
    ts: float
    phase: str
    inner: bytes  # the bytes AFTER [03][seq:LE16][2d]


def extract_samples(pcap: Path, phases: List[PhaseWindow],
                     server_ip: Optional[str]) -> List[TickSample]:
    try:
        from scapy.all import PcapReader, IP, UDP, Raw
    except ImportError:
        sys.exit("scapy missing — pip install scapy")

    # Autodetect server if not given.
    if server_ip is None:
        c = Counter()
        with PcapReader(str(pcap)) as pr:
            for i, p in enumerate(pr):
                if i > 5000:
                    break
                if IP not in p or Raw not in p:
                    continue
                for cand in (p[IP].src, p[IP].dst):
                    if cand.startswith(("10.", "192.168.",
                                        "127.", "169.254.")):
                        continue
                    c[cand] += len(bytes(p[Raw]))
        if not c:
            sys.exit("could not autodetect server IP")
        server_ip = c.most_common(1)[0][0]
        print(f"[autodetect] server_ip = {server_ip}",
              file=sys.stderr)

    samples: List[TickSample] = []
    with PcapReader(str(pcap)) as pr:
        for p in pr:
            if IP not in p or UDP not in p or Raw not in p:
                continue
            if p[IP].src != server_ip:
                continue
            ts = float(getattr(p, "time", 0.0) or 0.0)
            phase = phase_for_ts(ts, phases)
            if phase is None:
                continue
            wire = bytes(p[Raw])
            plain = burst_mod.decrypt_wire(wire)
            if plain is None or not plain or plain[0] != 0x13:
                continue
            parsed = burst_mod.parse_gamedata(plain)
            if parsed is None:
                continue
            for sub in parsed["subs"]:
                if sub.get("outer") != 0x03:
                    continue
                if sub.get("reliable_type") != 0x2d:
                    continue
                inner = bytes(sub["inner_data"])
                samples.append(TickSample(ts, phase, inner))
    return samples


# ─── Analysis ─────────────────────────────────────────────────────────

def per_offset_stats(samples: List[TickSample]) -> dict:
    """For each byte offset, compute per-phase value distributions
    and a phase-discriminator score.

    Score = how well a single byte separates phases. Computed as
    `1 - (mean within-phase variance / total variance)` on the
    byte's value treated as unsigned int. Higher = better
    discriminator.

    We only consider offsets present in EVERY sample (min length).
    """
    if not samples:
        return {"min_len": 0, "max_len": 0, "offsets": []}
    min_len = min(len(s.inner) for s in samples)
    max_len = max(len(s.inner) for s in samples)
    phase_groups: Dict[str, List[bytes]] = defaultdict(list)
    for s in samples:
        phase_groups[s.phase].append(s.inner)

    offsets = []
    for off in range(min_len):
        per_phase = {}
        all_vals = []
        for ph, blobs in phase_groups.items():
            vals = [b[off] for b in blobs if off < len(b)]
            per_phase[ph] = vals
            all_vals.extend(vals)
        if not all_vals:
            continue
        total_var = (statistics.pvariance(all_vals)
                     if len(all_vals) > 1 else 0)
        within_var = statistics.mean([
            statistics.pvariance(v) if len(v) > 1 else 0
            for v in per_phase.values() if v
        ]) if per_phase else 0
        score = (1 - within_var / total_var) if total_var > 0 else 0
        offsets.append({
            "offset": off,
            "distinct_all": len(set(all_vals)),
            "per_phase_distinct": {
                ph: len(set(v)) for ph, v in per_phase.items()},
            "per_phase_min_max": {
                ph: (min(v), max(v)) if v else (None, None)
                for ph, v in per_phase.items()
            },
            "score": score,
            "constant_across_session": len(set(all_vals)) == 1,
            "constant_value": (all_vals[0]
                                if len(set(all_vals)) == 1
                                else None),
        })
    return {"min_len": min_len, "max_len": max_len,
            "offsets": offsets, "phase_groups": {
                ph: len(blobs) for ph, blobs in phase_groups.items()}}


def find_entity_id_candidates(samples: List[TickSample], width: int
                                ) -> List[Tuple[int, int]]:
    """Look for LE32/LE16 fields that are constant within a sample
    block but vary across distinct entities. We use byte-stability
    across consecutive packets in the IDLE phase as a proxy.
    """
    idle = [s.inner for s in samples if s.phase == "idle"]
    if not idle:
        return []
    min_len = min(len(b) for b in idle)
    candidates = []
    for off in range(min_len - width + 1):
        vals = [int.from_bytes(b[off:off+width], "little")
                for b in idle]
        if not vals:
            continue
        distinct = len(set(vals))
        if distinct == 1:
            candidates.append((off, vals[0]))
    return candidates


# ─── Reporting ────────────────────────────────────────────────────────

def print_report(samples, stats, args):
    if not samples:
        print("No 0x03/0x2d S→C samples in the captured phases.")
        print("Possibilities:")
        print("  - markers didn't fire (re-run capture.sh)")
        print("  - server IP wrong (--server-ip)")
        print("  - no mob in view during any phase")
        return

    print(f"# HYP-001 NPC tick analysis — {args.pcap.name}\n")
    print(f"**Total `0x03/0x2d` S→C inner bodies captured:** "
          f"{len(samples)}")
    print(f"**Inner body length:** min={stats['min_len']}, "
          f"max={stats['max_len']}")
    print(f"**Per-phase sample counts:**")
    for ph, n in stats.get("phase_groups", {}).items():
        print(f"  - {ph}: {n}")
    print()

    print("## Constant bytes across whole session")
    print(f"_These bytes never change. Likely identity, packet-type "
          f"tag, or `magic_const`._\n")
    consts = [o for o in stats["offsets"]
              if o["constant_across_session"]]
    if not consts:
        print("(none)\n")
    else:
        for o in consts:
            print(f"  - offset {o['offset']:>3}: "
                  f"0x{o['constant_value']:02x} "
                  f"({o['constant_value']})")
        print()

    print("## Top phase-discriminator bytes")
    print(f"_Bytes whose value tracks the phase. Score = "
          f"1 - within-phase variance / total variance. "
          f"Higher = better discriminator._\n")
    print("| Offset | Score | Distinct | Per-phase distinct counts | Per-phase min..max |")
    print("|---:|---:|---:|---|---|")
    discrim = sorted(
        [o for o in stats["offsets"]
         if not o["constant_across_session"]],
        key=lambda x: x["score"], reverse=True)
    for o in discrim[:30]:
        ppd = ",".join(f"{ph}={n}" for ph, n in
                        o["per_phase_distinct"].items())
        ppm = ",".join(
            f"{ph}=[{mm[0]}..{mm[1]}]"
            for ph, mm in o["per_phase_min_max"].items()
            if mm[0] is not None)
        print(f"| {o['offset']:>3} | {o['score']:.3f} | "
              f"{o['distinct_all']} | {ppd} | {ppm} |")
    print()

    print("## Variable bytes by total distinct values")
    print(f"_High-distinct bytes are likely position floats / "
          f"per-tick counters. Low-distinct bytes are state enums._\n")
    print("| Offset | Distinct values |")
    print("|---:|---:|")
    by_distinct = sorted(
        [o for o in stats["offsets"]
         if not o["constant_across_session"]],
        key=lambda x: x["distinct_all"], reverse=True)
    for o in by_distinct[:20]:
        print(f"| {o['offset']:>3} | {o['distinct_all']} |")
    print()

    print("## Entity-id candidates (constant LE32 in idle phase)")
    for w in (2, 4):
        cands = find_entity_id_candidates(samples, w)
        for off, val in cands[:10]:
            print(f"  - LE{w*8} @ offset {off:>3}: 0x{val:0{w*2}x} "
                  f"({val})")
    print()

    print("## Suggested next decoders")
    print()
    print("Look at the top discriminator bytes above. Patterns to "
          "watch for:")
    print()
    print("  - Score > 0.8 + 2-5 distinct values + each phase has a "
          "different majority value → STATE ENUM byte. Promote to "
          "`mob_state` field, enumerate values.")
    print()
    print("  - Score > 0.5 + monotonic-decreasing in `killing` phase "
          "→ HP byte / pair / quad. Cross-reference with on-screen "
          "HP bar.")
    print()
    print("  - 4-byte runs with ~3000+ distinct values and uniform "
          "distribution → float32 LE position (x/y/z). Confirm by "
          "interpreting as f32 and checking the value range matches "
          "the reactor room coords.")
    print()
    print("  - Constant in idle, changes ONLY in `aggro`/`killing` "
          "→ target / aggressor entity id.")


# ─── Main ─────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pcap", type=Path, required=True)
    ap.add_argument("--markers", type=Path, required=True)
    ap.add_argument("--server-ip", default=None)
    ap.add_argument("--dump-bodies", type=Path, default=None,
                    help="Optional: write every inner body as hex "
                         "+ phase label to this file (for manual "
                         "inspection)")
    args = ap.parse_args()

    if not args.pcap.is_file():
        sys.exit(f"no such file: {args.pcap}")
    if not args.markers.is_file():
        sys.exit(f"no such file: {args.markers}")

    markers = parse_markers(args.markers)
    phases = build_phases(markers)
    if not phases:
        sys.exit("no usable phase windows found in markers")

    samples = extract_samples(args.pcap, phases, args.server_ip)

    if args.dump_bodies:
        with args.dump_bodies.open("w") as f:
            for s in samples:
                f.write(f"{s.ts:.6f} {s.phase:>9} {s.inner.hex()}\n")
        print(f"wrote {len(samples)} bodies to {args.dump_bodies}",
              file=sys.stderr)

    stats = per_offset_stats(samples)
    print_report(samples, stats, args)


if __name__ == "__main__":
    main()
