#!/usr/bin/env python3
"""find-rare-subs.py — surface unusual sub-packets in a window.

Loads a strace file, decrypts both directions, parses each 0x13 GAMEDATA
into sub-packets, and lists:
  - Histogram per direction.
  - Sub-packets with payload >= MIN_INTERESTING bytes.
  - Sub-types whose total occurrences are <= RARE_THRESHOLD.

Each interesting sub-packet is printed with its packet index, timestamp
slot, and full hex.
"""

from __future__ import annotations
import argparse, sys
from collections import Counter, defaultdict
from pathlib import Path

# Reuse decrypt + parse from existing tools by importing as sibling scripts
sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")
burst_mod = import_module("parse-burst")

RELIABLE_SUBTYPES = burst_mod.RELIABLE_SUBTYPES
OUTER_SUBTYPES = burst_mod.OUTER_SUBTYPES


def label(sub):
    o = sub["outer"]
    if o == 0x03:
        rt = sub.get("reliable_type", -1)
        name = RELIABLE_SUBTYPES.get(rt, f"unk")
        return f"0x03→0x{rt:02x}/{name}"
    name = OUTER_SUBTYPES.get(o, "unk")
    return f"0x{o:02x}/{name}"


def label_with_inner(sub):
    """Like label() but for 0x03→0x1f, also pull the inner sub-opcode byte."""
    base = label(sub)
    if sub["outer"] == 0x03 and sub.get("reliable_type") == 0x1f:
        d = sub.get("inner_data", b"")
        if len(d) >= 2:
            # 0x1f body: [len][opcode][payload...]
            base += f"+0x{d[1]:02x}"
    return base


def analyze(path: Path, direction: str, min_size: int, rare_thr: int):
    pkts = decrypt_mod.extract_udp(path, direction)
    plains = []
    for w in pkts:
        r = decrypt_mod.decrypt_wire_packet(w)
        if r:
            plains.append(r[0])

    print(f"\n{'='*78}")
    print(f"  {direction.upper():4s} {path.name}: {len(plains)} packets decrypted")
    print(f"{'='*78}")

    histo = Counter()
    occ = defaultdict(list)  # type → [(pkt_idx, sub)]
    for i, p in enumerate(plains):
        parsed = burst_mod.parse_gamedata(p)
        if not parsed:
            continue
        for sub in parsed["subs"]:
            tag = label_with_inner(sub)
            histo[tag] += 1
            occ[tag].append((i, parsed["counter"], sub))

    print(f"\n── histogram ({direction}) ──")
    for tag, c in sorted(histo.items(), key=lambda x: -x[1]):
        marker = " ← RARE" if c <= rare_thr else ""
        print(f"  {c:5d}  {tag}{marker}")

    print(f"\n── rare sub-types (count ≤ {rare_thr}) ──")
    rares = [t for t, c in histo.items() if c <= rare_thr]
    for tag in sorted(rares, key=lambda t: histo[t]):
        for (pi, cnt, sub) in occ[tag]:
            print(f"  pkt#{pi+1:4d} cnt={cnt:5d}  {tag:30s}  "
                  f"len={sub['len']:3d}")
            print(f"        bytes: {sub['data'].hex()}")
            if sub["outer"] == 0x03:
                inner = sub.get("inner_data", b"")
                if inner:
                    print(f"        inner: {inner.hex()}")

    print(f"\n── large payloads (≥ {min_size}B regardless of count) ──")
    large = []
    for tag, items in occ.items():
        for (pi, cnt, sub) in items:
            if sub["len"] >= min_size:
                large.append((pi, cnt, tag, sub))
    large.sort(key=lambda x: -x[3]["len"])
    for (pi, cnt, tag, sub) in large[:50]:
        print(f"  pkt#{pi+1:4d} cnt={cnt:5d}  {tag:30s}  len={sub['len']:3d}")
        print(f"        bytes: {sub['data'].hex()}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", "-i", type=Path, required=True)
    ap.add_argument("--min-size", type=int, default=20)
    ap.add_argument("--rare-thr", type=int, default=2)
    args = ap.parse_args()
    analyze(args.input, "recv", args.min_size, args.rare_thr)
    analyze(args.input, "send", args.min_size, args.rare_thr)


if __name__ == "__main__":
    main()
