#!/usr/bin/env python3
"""Dump first N occurrences of 0x00 raw and 0x1b raw sub-packets across captures."""
import sys
from pathlib import Path
import importlib.util
spec = importlib.util.spec_from_file_location("pb", "tools/parse-burst.py")
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

CAPTURES = [
    ("ACC1_CHAR1", "strace/nc2_strace_RETAIL_ACC1_CHAR1.log"),
    ("ACC1_CHAR2", "strace/nc2_strace_RETAIL_ACC1_CHAR2.log"),
    ("ACC2_CHAR1", "strace/nc2_strace_RETAIL_ACC2_CHAR1.log"),
    ("ACC2_CHAR2", "strace/nc2_strace_RETAIL_ACC2_CHAR2.log"),
]

TARGETS_RAW = {0x00, 0x1b, 0x02, 0x13, 0x32, 0x09}
MAX_PER = 5

for name, path in CAPTURES:
    print(f"\n======= {name} =======")
    wire = pb.extract_udp(Path(path), "recv")
    samples = {t: [] for t in TARGETS_RAW}
    rel_24 = []
    inner_1f_samples = []
    for w in wire:
        p = pb.decrypt_wire(w)
        if not p or p[0] != 0x13: continue
        parsed = pb.parse_gamedata(p)
        if not parsed: continue
        for s in parsed["subs"]:
            if s["outer"] in TARGETS_RAW and len(samples[s["outer"]]) < MAX_PER:
                samples[s["outer"]].append(s["data"].hex())
            if s["outer"] == 0x03 and s.get("reliable_type") == 0x1f:
                if len(inner_1f_samples) < 8:
                    inner_1f_samples.append(s["data"].hex())
    for t in sorted(TARGETS_RAW):
        print(f"  0x{t:02x} raw samples:")
        for s in samples[t][:MAX_PER]:
            print(f"    {s}")
    print(f"  0x03→0x1f (GamePackets) samples:")
    for s in inner_1f_samples:
        print(f"    {s}")
