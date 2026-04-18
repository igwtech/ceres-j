#!/usr/bin/env python3
"""Dump many raw 0x1b S→C samples + tabulate byte patterns."""
from pathlib import Path
from collections import Counter
import importlib.util
spec = importlib.util.spec_from_file_location("pb", "tools/parse-burst.py")
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

wire = pb.extract_udp(Path("strace/nc2_strace_RETAIL_ACC1_CHAR1.log"), "recv")
samples = []
for w in wire:
    p = pb.decrypt_wire(w)
    if not p or p[0] != 0x13: continue
    parsed = pb.parse_gamedata(p)
    if not parsed: continue
    for s in parsed["subs"]:
        if s["outer"] == 0x1b:
            samples.append(s["data"])

print(f"Total raw 0x1b S→C sub-packets: {len(samples)}")
print(f"Size histogram: {Counter(len(s) for s in samples).most_common()}")
print()

# Show 20 samples aligned
print("First 20 samples (hex):")
for i, s in enumerate(samples[:20]):
    print(f"  {i:3d}: {s.hex()}")

print()
print("19-byte samples, aligned by offset:")
print("       " + " ".join(f"{i:02d}" for i in range(19)))
for i, s in enumerate(samples[:15]):
    if len(s) != 19: continue
    print(f"  {i:3d}: " + " ".join(f"{b:02x}" for b in s))

# Also look at counts per unique byte position to find constant bytes
print("\nBytes-per-offset histogram (for 19-byte samples):")
pos_hist = [Counter() for _ in range(19)]
for s in samples:
    if len(s) != 19: continue
    for i, b in enumerate(s):
        pos_hist[i][b] += 1

for i, h in enumerate(pos_hist):
    most = h.most_common(5)
    total = sum(h.values())
    if len(h) == 1:
        print(f"  offset {i:2d}: CONSTANT 0x{list(h.keys())[0]:02x}")
    elif len(h) <= 5:
        vals = ", ".join(f"0x{b:02x}×{n}" for b, n in most)
        print(f"  offset {i:2d}: ({len(h)} unique) {vals}")
    else:
        top5 = ", ".join(f"0x{b:02x}×{n}" for b, n in most)
        print(f"  offset {i:2d}: ({len(h)} unique, {total} total) top={top5}")
