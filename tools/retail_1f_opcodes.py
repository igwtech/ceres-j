#!/usr/bin/env python3
"""Histogram of inner opcodes in retail C→S 0x03→0x1f GamePackets.
The format is: [0x03][seq LE2][0x1f][2 bytes][inner_opcode][sub_opcode]..."""
from pathlib import Path
from collections import Counter
import importlib.util
spec = importlib.util.spec_from_file_location("pb", "tools/parse-burst.py")
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

CAPTURES = [
    ("ACC1_CHAR1", "strace/nc2_strace_RETAIL_ACC1_CHAR1.log"),
    ("ACC1_CHAR2", "strace/nc2_strace_RETAIL_ACC1_CHAR2.log"),
    ("ACC2_CHAR1", "strace/nc2_strace_RETAIL_ACC2_CHAR1.log"),
    ("ACC2_CHAR2", "strace/nc2_strace_RETAIL_ACC2_CHAR2.log"),
]

for name, path in CAPTURES:
    print(f"\n=== {name} (C→S) ===")
    wire = pb.extract_udp(Path(path), "send")
    opcodes = Counter()
    sizes = Counter()
    for w in wire:
        p = pb.decrypt_wire(w)
        if not p or p[0] != 0x13: continue
        parsed = pb.parse_gamedata(p)
        if not parsed: continue
        for s in parsed["subs"]:
            if s["outer"] == 0x03 and s.get("reliable_type") == 0x1f:
                inner = s.get("inner_data", b"")
                # inner starts right after the 0x1f byte, so the full
                # 0x03→0x1f payload layout is [2 unknown bytes][opcode][...]
                if len(inner) >= 3:
                    opc = inner[2]
                    sub = inner[3] if len(inner) >= 4 else -1
                    opcodes[(opc, sub)] += 1
                sizes[s["len"]] += 1
    print("  inner opcode histogram (opcode, sub-opcode):")
    for (opc, sub), n in opcodes.most_common(15):
        sub_str = f"0x{sub:02x}" if sub >= 0 else "?"
        print(f"    {n:4d}x  opcode=0x{opc:02x} sub={sub_str}")
    print("  sub-packet size histogram:")
    for sz, n in sizes.most_common(10):
        print(f"    {n:4d}x  {sz}B")
