#!/usr/bin/env python3
"""Decode 0x32 raw sub-packets and S→C 0x20 Movement from the long retail capture."""
import os; os.chdir('/home/javier/Documents/Projects/Neocron/ceres-j')
from pathlib import Path
from collections import Counter
import importlib.util
spec = importlib.util.spec_from_file_location('pb', 'tools/parse-burst.py')
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

wire = pb.extract_udp(Path('strace/nc2_strace_RETAIL_ACC2_CHAR2_LONGCAPTURE.log'), 'recv')
print(f"Loaded {len(wire)} S→C packets")

raw_32 = []
raw_20 = []
raw_1f = []
rel_32 = []

for w in wire:
    p = pb.decrypt_wire(w)
    if not p or p[0] != 0x13: continue
    parsed = pb.parse_gamedata(p, len_bytes=2)
    if not parsed: continue
    for s in parsed['subs']:
        if s['outer'] == 0x32 and len(raw_32) < 10:
            raw_32.append(s['data'])
        if s['outer'] == 0x20 and len(raw_20) < 10:
            raw_20.append(s['data'])
        if s['outer'] == 0x1f and len(raw_1f) < 10:
            raw_1f.append(s['data'])
        if s['outer'] == 0x03 and s.get('reliable_type') == 0x32 and len(rel_32) < 10:
            rel_32.append(s.get('inner_data', b''))

print(f"\n=== 0x32 RAW sub-packets (first 10 of 182) ===")
print(f"Size histogram: {Counter(len(s) for s in raw_32)}")
for i, d in enumerate(raw_32):
    print(f"  {i}: ({len(d)}B) {d.hex()}")

print(f"\n=== 0x20 Movement S→C (first 10 of 78) ===")
for i, d in enumerate(raw_20):
    print(f"  {i}: ({len(d)}B) {d.hex()}")
    # Try to decode as movement: [0x20][type][position data...]
    if len(d) >= 4:
        move_type = d[1]
        print(f"      type=0x{move_type:02x} rest={d[2:].hex()}")

print(f"\n=== 0x1f RAW sub-packets (first 10 of 54) ===")
for i, d in enumerate(raw_1f):
    print(f"  {i}: ({len(d)}B) {d.hex()}")

print(f"\n=== 0x03→0x32 RELIABLE (first 10 of 41) ===")
for i, d in enumerate(rel_32):
    print(f"  {i}: ({len(d)}B) {d.hex()}")
