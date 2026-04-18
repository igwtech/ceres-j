#!/usr/bin/env python3
"""Find the raw (non-multipart, non-reliable) 0x22 sub-packets in retail S→C."""
from pathlib import Path
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
    print(f"\n=== {cap} ===")
    wire = pb.extract_udp(Path(cap), "recv")
    n_raw_22 = 0
    for w in wire:
        p = pb.decrypt_wire(w)
        if not p or p[0] != 0x13: continue
        parsed = pb.parse_gamedata(p)
        if not parsed: continue
        for s in parsed["subs"]:
            if s["outer"] == 0x22:
                n_raw_22 += 1
                print(f"  RAW 0x22 sub, size={s['len']}, data: {s['data'].hex()}")
            if s["outer"] == 0x02:
                # 0x02 is the "simplified reliable wrapper" per retail_burst_analysis.md
                inner = s["data"]
                if len(inner) >= 4 and inner[3] == 0x22:
                    print(f"  0x02-WRAPPED-0x22 sub, size={s['len']}, data: {s['data'].hex()}")
    print(f"  total raw 0x22 sub-packets: {n_raw_22}")
