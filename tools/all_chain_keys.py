#!/usr/bin/env python3
"""Check what chain_key values are used across all retail captures."""
import sys
from pathlib import Path
from collections import Counter
import importlib.util
spec = importlib.util.spec_from_file_location("pb", "tools/parse-burst.py")
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

CAPTURES = [
    "strace/nc2_strace_RETAIL_ACC1_CHAR1.log",
    "strace/nc2_strace_RETAIL_ACC1_CHAR2.log",
    "strace/nc2_strace_RETAIL_ACC2_CHAR1.log",
    "strace/nc2_strace_RETAIL_ACC2_CHAR2.log",
]

for cap in CAPTURES:
    wire = pb.extract_udp(Path(cap), "recv")
    keys_fragcount = {}
    for w in wire:
        p = pb.decrypt_wire(w)
        if not p or p[0] != 0x13: continue
        parsed = pb.parse_gamedata(p)
        if not parsed: continue
        for s in parsed["subs"]:
            if s["outer"] == 0x03 and s.get("reliable_type") == 0x07:
                inner = s.get("inner_data", b"")
                if len(inner) < 5: continue
                frag_idx = inner[0] | (inner[1] << 8)
                total = inner[2] | (inner[3] << 8)
                chain_key = inner[4]
                key = (chain_key, total)
                keys_fragcount.setdefault(key, 0)
                keys_fragcount[key] += 1
    name = cap.split("_")[-1].replace(".log", "")
    print(f"{name}: {keys_fragcount}")
