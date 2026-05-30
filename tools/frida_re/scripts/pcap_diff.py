#!/usr/bin/env python3
"""pcap_diff.py — Compare two NC2 retail-or-Ceres-J pcaps and
report byte-level divergences in matching opcodes.

Use case: after implementing a fix in Ceres-J, capture a new pcap
and run `pcap_diff.py <retail.pcap> <ceresj.pcap>` to verify the
fix lands at the wire level.

What it compares:
* Per-opcode S→C/C→S volume (massive diff = missing emission)
* Per-opcode size variant distribution (missing variants = bug)
* Sample byte body for each (opcode, size) pair (first ~3 from each)
* Embedded ASCII strings (script names, paths, char names)

Doesn't try to be semantically smart — just surfaces what's
different. Operator inspects to decide if the diff is intentional
(different captured scenarios) or a real bug.

Usage:
    pcap_diff.py <pcap_a> <pcap_b> [--top N]
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Iterable, Optional

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import pcap_extract_zoning as base  # noqa: E402


ASCII_RE = re.compile(rb'([A-Za-z][A-Za-z0-9_/\\.\- ]{2,40})\x00')


def opcode_label(sub: bytes) -> str:
    """Return a string label for an opcode, including envelope depth."""
    if len(sub) < 1:
        return '<empty>'
    if sub[0] == 0x03 and len(sub) >= 4:
        op = sub[3]
        if op == 0x1f and len(sub) >= 7:
            return f"0x03/0x1f/0x{sub[6]:02x}"
        if op == 0x22 and len(sub) >= 5:
            return f"0x03/0x22/0x{sub[4]:02x}"
        return f"0x03/0x{op:02x}"
    if sub[0] == 0x02 and len(sub) >= 4:
        return f"0x02-rewrap/0x{sub[3]:02x}"
    return f"raw 0x{sub[0]:02x}"


def inventory(pcap: str) -> dict:
    """Return a dict of pcap statistics for diffing."""
    op_dir_size_hits = Counter()  # (op_label, dir, size) -> count
    op_samples = defaultdict(list)  # op_label -> [(dir, body), ...]
    transport_hits = Counter()  # (top_byte, dir, size) -> count
    transport_samples = defaultdict(list)
    ascii_strings = Counter()
    total_udp = 0
    decryptable = 0

    for ts, sip, sp, dip, dp, body in base.parse_pcap(pcap):
        total_udp += 1
        plain = base.decrypt_one(body)
        if plain is None or len(plain) < 1:
            continue
        decryptable += 1
        is_c2s = dp in {5001, 5002, 5003, 5004, 5005, 5006,
                        5007, 5008, 12000}
        d = 'C->S' if is_c2s else 'S->C'

        if plain[0] != 0x13:
            # Transport-layer
            key = (plain[0], d, len(plain))
            transport_hits[key] += 1
            tkey = (plain[0], d)
            if len(transport_samples[tkey]) < 3:
                transport_samples[tkey].append(plain)
            continue

        # 0x13-wrapped — walk sub-packets
        for sub in base.walk_subpackets(plain):
            if len(sub) < 1:
                continue
            label = opcode_label(sub)
            key = (label, d, len(sub))
            op_dir_size_hits[key] += 1
            skey = (label, d)
            if len(op_samples[skey]) < 3:
                op_samples[skey].append(sub)
            # Scrape ASCII names
            for m in ASCII_RE.finditer(sub):
                try:
                    s = m.group(1).decode('latin1')
                    if not s.isdigit() and len(s) >= 3:
                        ascii_strings[s] += 1
                except Exception:
                    pass

    return {
        'total_udp': total_udp,
        'decryptable': decryptable,
        'op_dir_size_hits': op_dir_size_hits,
        'op_samples': op_samples,
        'transport_hits': transport_hits,
        'transport_samples': transport_samples,
        'ascii_strings': ascii_strings,
    }


def diff_inventory(a: dict, b: dict, top: int = 30) -> None:
    """Print a human-readable diff between two pcap inventories."""
    # 1. Top-level stats
    print("=" * 70)
    print(f"{'metric':<32} {'pcap_a':>15} {'pcap_b':>15}")
    print("-" * 70)
    print(f"{'total UDP packets':<32} {a['total_udp']:>15} "
          f"{b['total_udp']:>15}")
    print(f"{'decryptable':<32} {a['decryptable']:>15} "
          f"{b['decryptable']:>15}")
    print()

    # 2. Per-opcode volume diff (both directions)
    all_ops_a = set((label, d)
                    for (label, d, _) in a['op_dir_size_hits'].keys())
    all_ops_b = set((label, d)
                    for (label, d, _) in b['op_dir_size_hits'].keys())

    def hits(inv, label, d):
        return sum(c for (l, dd, _), c
                   in inv['op_dir_size_hits'].items()
                   if l == label and dd == d)

    all_keys = sorted(all_ops_a | all_ops_b)
    print("=" * 70)
    print(f"{'OPCODE / DIR VOLUME':<28} {'pcap_a':>10}  {'pcap_b':>10}  "
          f"diff")
    print("-" * 70)
    asymmetric = []
    for (label, d) in all_keys:
        na = hits(a, label, d)
        nb = hits(b, label, d)
        # Surface opcodes present only on one side OR with >5× volume diff
        only_one = (na == 0) ^ (nb == 0)
        big_ratio = (na > 0 and nb > 0 and
                     (na / nb > 5 or nb / na > 5))
        if only_one or big_ratio:
            asymmetric.append((label, d, na, nb))

    for (label, d, na, nb) in sorted(asymmetric,
                                     key=lambda x: -max(x[2], x[3])):
        flag = ' << ONE-SIDED' if na == 0 or nb == 0 else ''
        print(f"{label:<22} {d:<5} {na:>10}  {nb:>10}  {flag}")
    print()

    # 3. Per-opcode size variant diff (where same opcode has different
    #    size distributions)
    op_pairs = sorted(all_ops_a & all_ops_b)
    print("=" * 70)
    print(f"{'OPCODE SIZE-VARIANT MISMATCH':<60}")
    print("-" * 70)
    for (label, d) in op_pairs[:top]:
        sizes_a = {sz: c for (l, dd, sz), c
                   in a['op_dir_size_hits'].items()
                   if l == label and dd == d}
        sizes_b = {sz: c for (l, dd, sz), c
                   in b['op_dir_size_hits'].items()
                   if l == label and dd == d}
        only_a = set(sizes_a) - set(sizes_b)
        only_b = set(sizes_b) - set(sizes_a)
        if only_a or only_b:
            print(f"{label} {d}:")
            if only_a:
                print(f"  pcap_a only sizes: "
                      f"{sorted({s: sizes_a[s] for s in only_a}.items())}")
            if only_b:
                print(f"  pcap_b only sizes: "
                      f"{sorted({s: sizes_b[s] for s in only_b}.items())}")
    print()

    # 4. Transport-layer diff
    transport_keys_a = set((op, d)
                           for (op, d, _) in a['transport_hits'])
    transport_keys_b = set((op, d)
                           for (op, d, _) in b['transport_hits'])
    print("=" * 70)
    print(f"{'TRANSPORT-LAYER OPCODE DIFF':<60}")
    print("-" * 70)
    for (op, d) in sorted(transport_keys_a | transport_keys_b):
        na = sum(c for (o, dd, _), c in a['transport_hits'].items()
                 if o == op and dd == d)
        nb = sum(c for (o, dd, _), c in b['transport_hits'].items()
                 if o == op and dd == d)
        only_one = (na == 0) ^ (nb == 0)
        if only_one or abs(na - nb) > max(na, nb) // 2:
            flag = ' << ONE-SIDED' if na == 0 or nb == 0 else ''
            print(f"  transport 0x{op:02x} {d}: a={na:>5} b={nb:>5}{flag}")
    print()

    # 5. ASCII string diff (script names, paths, char names)
    a_strs = set(a['ascii_strings'])
    b_strs = set(b['ascii_strings'])
    print("=" * 70)
    print(f"{'ASCII STRING DIFF (script names, paths, etc.)':<60}")
    print("-" * 70)
    only_a = a_strs - b_strs
    only_b = b_strs - a_strs
    if only_a:
        print(f"pcap_a only ({len(only_a)} strings):")
        for s in sorted(only_a, key=lambda x: -a['ascii_strings'][x])[:top]:
            print(f"  {a['ascii_strings'][s]:>4}× {s!r}")
    if only_b:
        print(f"pcap_b only ({len(only_b)} strings):")
        for s in sorted(only_b, key=lambda x: -b['ascii_strings'][x])[:top]:
            print(f"  {b['ascii_strings'][s]:>4}× {s!r}")
    shared = a_strs & b_strs
    if shared:
        print(f"shared ({len(shared)} strings, top {min(top, len(shared))}):")
        for s in sorted(shared,
                        key=lambda x: -(a['ascii_strings'][x]
                                        + b['ascii_strings'][x]))[:top]:
            print(f"  {a['ascii_strings'][s]:>4}/{b['ascii_strings'][s]:<4} "
                  f"{s!r}")


def main(argv: Optional[list[str]] = None) -> int:
    p = argparse.ArgumentParser(
        prog="pcap_diff",
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("pcap_a")
    p.add_argument("pcap_b")
    p.add_argument("--top", type=int, default=30,
                   help="max rows per diff section")
    args = p.parse_args(argv)

    print(f"inventorying {args.pcap_a} ...", file=sys.stderr)
    a = inventory(args.pcap_a)
    print(f"inventorying {args.pcap_b} ...", file=sys.stderr)
    b = inventory(args.pcap_b)
    diff_inventory(a, b, top=args.top)
    return 0


if __name__ == "__main__":
    sys.exit(main())
