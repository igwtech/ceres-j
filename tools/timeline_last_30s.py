#!/usr/bin/env python3
"""Show a detailed timeline of the last 30 seconds of a session — every
packet both directions with timestamps and decoded sub-types."""
import os; os.chdir('/home/javier/Documents/Projects/Neocron/ceres-j')
from pathlib import Path
from collections import Counter
import importlib.util, re, sys
spec = importlib.util.spec_from_file_location('pb', 'tools/parse-burst.py')
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

_RE_TS = re.compile(
    r'^\d+\s+(\d+:\d+:\d+\.\d+)\s+(recvmsg|sendmsg)\(\d+<UDP[^>]*>.*?iov_base="((?:[^"\\]|\\.)*)".*?= (\d+)$',
    re.MULTILINE,
)

def ts_ms(ts):
    h, m, s = ts.split(':')
    s2, us = s.split('.')
    return int(h)*3600000 + int(m)*60000 + int(s2)*1000 + int(us)//1000

path = sys.argv[1] if len(sys.argv) > 1 else '/tmp/nc2_strace.log'
with open(path, 'r', errors='replace') as f: content = f.read()

packets = []
for m in _RE_TS.finditer(content):
    ts, call = m.group(1), m.group(2)
    n = int(m.group(4))
    if n <= 0: continue
    d = 'S→C' if call == 'recvmsg' else 'C→S'
    data = pb.decode_strace_bytes(m.group(3))
    if data: packets.append((ts, d, data))

if not packets:
    print("No packets found"); sys.exit(1)

# Find the abort packet (0x08) to anchor the timeline
abort_ms = None
for ts, d, wire in reversed(packets):
    p = pb.decrypt_wire(wire)
    if p and p[0] == 0x08:
        abort_ms = ts_ms(ts)
        break

if abort_ms is None:
    abort_ms = ts_ms(packets[-1][0])

# Show last 35 seconds
cutoff_ms = abort_ms - 35000

print(f"Timeline: last 35 seconds before abort at {abort_ms}ms")
print(f"{'ms':>8} {'Dir':<4} {'Wire':>5} {'Plain':>5}  Sub-packets")
print("-" * 80)

for ts, d, wire in packets:
    ms = ts_ms(ts)
    if ms < cutoff_ms: continue

    p = pb.decrypt_wire(wire)
    if not p: continue

    rel_ms = ms - abort_ms

    if p[0] != 0x13:
        print(f"{rel_ms:>+8} {d:<4} {len(wire):>5} {len(p):>5}  outer=0x{p[0]:02x}")
        continue

    parsed = pb.parse_gamedata(p)
    if not parsed: continue

    subs = []
    for s in parsed['subs']:
        if s['outer'] == 0x03:
            rt = s.get('reliable_type', 0)
            inner = s.get('inner_data', b'')
            name = pb.RELIABLE_SUBTYPES.get(rt, f'0x{rt:02x}')
            extra = ''
            if rt == 0x1f and len(inner) >= 3:
                extra = f' opc=0x{inner[2]:02x}'
            subs.append(f'0x03→{name}({s["len"]}B){extra}')
        else:
            name = pb.OUTER_SUBTYPES.get(s['outer'], f'0x{s["outer"]:02x}')
            subs.append(f'{name}({s["len"]}B)')

    print(f"{rel_ms:>+8} {d:<4} {len(wire):>5} {len(p):>5}  [{len(parsed['subs'])} sub] {", ".join(subs[:5])}")
    if len(subs) > 5:
        print(f"{'':>30}  + {len(subs)-5} more...")
