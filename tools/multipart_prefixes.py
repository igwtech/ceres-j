#!/usr/bin/env python3
"""Check if the 6-byte per-fragment prefix is actually constant across captures
or just appears constant. Also determine whether each fragment has a header
that gets STRIPPED during reassembly or whether it's real data."""
from pathlib import Path
import importlib.util
spec = importlib.util.spec_from_file_location("pb", "tools/parse-burst.py")
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)
from collections import Counter

CAPTURES = [
    "strace/nc2_strace_RETAIL_ACC1_CHAR1.log",
    "strace/nc2_strace_RETAIL_ACC1_CHAR2.log",
    "strace/nc2_strace_RETAIL_ACC2_CHAR1.log",
    "strace/nc2_strace_RETAIL_ACC2_CHAR2.log",
]

for cap in CAPTURES:
    print(f"\n=== {cap} ===")
    wire = pb.extract_udp(Path(cap), "recv")
    prefixes = Counter()
    payloads_by_size = Counter()
    for w in wire:
        p = pb.decrypt_wire(w)
        if not p or p[0] != 0x13: continue
        parsed = pb.parse_gamedata(p)
        if not parsed: continue
        for s in parsed["subs"]:
            if s["outer"] == 0x03 and s.get("reliable_type") == 0x07:
                inner = s.get("inner_data", b"")
                if len(inner) < 11: continue
                # inner: [frag_idx LE2][total LE2][chain_key 1][payload...]
                payload = inner[5:]
                if len(payload) >= 6:
                    prefixes[payload[:6].hex()] += 1
                payloads_by_size[len(payload)] += 1
    print(f"  first-6-bytes of payload histogram:")
    for px, n in prefixes.most_common(10):
        print(f"    {n:3d}x  {px}")
    print(f"  payload size histogram:")
    for sz, n in sorted(payloads_by_size.items()):
        print(f"    {n:3d}x  {sz}B")
