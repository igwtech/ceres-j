#!/usr/bin/env python3
"""Decode 0x00 raw sub-packets from the new capture to understand what they actually are."""
import os; os.chdir('/home/javier/Documents/Projects/Neocron/ceres-j')
from pathlib import Path
from collections import Counter
import importlib.util
spec = importlib.util.spec_from_file_location('pb', 'tools/parse-burst.py')
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

# Use the existing extract function from parse-burst
wire_pkts = pb.extract_udp(Path('/tmp/nc2_strace.log'), 'recv')
print(f"Loaded {len(wire_pkts)} S->C wire packets")

zero_samples = []
raw_1b_count = 0
for w in wire_pkts:
    p = pb.decrypt_wire(w)
    if not p or p[0] != 0x13: continue
    parsed = pb.parse_gamedata(p)
    if not parsed: continue
    for s in parsed['subs']:
        if s['outer'] == 0x1b:
            raw_1b_count += 1
        if s['outer'] == 0x00 and len(zero_samples) < 10:
            d = s['data']
            zero_samples.append((s['len'], d))

print(f"raw 0x1b sub-packets: {raw_1b_count}")
print(f"raw 0x00 sub-packets: found {len(zero_samples)} samples")
for i, (sz, d) in enumerate(zero_samples):
    print(f"  #{i} size={sz} data={d[:20].hex()}")

# Also dump a full 0x13 gamedata packet that contains a 0x00 sub to see framing
for w in wire_pkts[:100]:
    p = pb.decrypt_wire(w)
    if not p or p[0] != 0x13: continue
    parsed = pb.parse_gamedata(p)
    if not parsed: continue
    has_zero = False
    for s in parsed['subs']:
        if s['outer'] == 0x00:
            has_zero = True
            break
    if has_zero:
        print(f"\nFull 0x13 packet with 0x00 sub ({len(p)}B plaintext):")
        print(f"  hex: {p[:60].hex()}")
        print(f"  counter: {p[1] | (p[2]<<8)}")
        print(f"  subs: {len(parsed['subs'])}")
        for s in parsed['subs']:
            print(f"    outer=0x{s['outer']:02x} size={s['len']} data={s['data'][:10].hex()}")
        break
