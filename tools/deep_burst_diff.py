#!/usr/bin/env python3
"""Deep comparison of the initial S→C burst (first 30 seconds) between
retail and Ceres-J. Focus on: what reliable sub-types retail sends that
we don't, total data volume per sub-type, and timing."""
import os; os.chdir('/home/javier/Documents/Projects/Neocron/ceres-j')
from pathlib import Path
from collections import Counter, defaultdict
import importlib.util
spec = importlib.util.spec_from_file_location('pb', 'tools/parse-burst.py')
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

import re
_RE_TS = re.compile(
    r'^\d+\s+(\d+:\d+:\d+\.\d+)\s+(recvmsg|sendmsg)\(\d+<UDP[^>]*>.*?iov_base="((?:[^"\\]|\\.)*)".*?= (\d+)$',
    re.MULTILINE,
)

def extract(path):
    with open(path, 'r', errors='replace') as f: content = f.read()
    out = []
    for m in _RE_TS.finditer(content):
        ts, call = m.group(1), m.group(2)
        n = int(m.group(4))
        if n <= 0: continue
        d = 'S2C' if call == 'recvmsg' else 'C2S'
        data = pb.decode_strace_bytes(m.group(3))
        if data: out.append((ts, d, data))
    return out

def ts_ms(ts):
    h, m, s = ts.split(':')
    s2, us = s.split('.')
    return int(h)*3600000 + int(m)*60000 + int(s2)*1000 + int(us)//1000

def analyze(name, path):
    pkts = extract(path)
    print(f"\n{'='*60}\n{name}: {len(pkts)} UDP packets\n{'='*60}")

    types = Counter()
    bytes_per_type = defaultdict(int)

    for ts, d, wire in pkts:
        if d != 'S2C': continue
        p = pb.decrypt_wire(wire)
        if not p: continue

        if p[0] != 0x13:
            types[f'0x{p[0]:02x} raw'] += 1
            bytes_per_type[f'0x{p[0]:02x} raw'] += len(p)
            continue
        parsed = pb.parse_gamedata(p)
        if not parsed: continue
        for s in parsed['subs']:
            if s['outer'] == 0x03:
                rt = s.get('reliable_type', 0)
                key = f'0x03->0x{rt:02x} {pb.RELIABLE_SUBTYPES.get(rt, "?")}'
                types[key] += 1
                bytes_per_type[key] += s['len']
            elif s['outer'] == 0x02:
                inner = s['data']
                if len(inner) >= 4:
                    rt2 = inner[3]
                    key = f'0x02->0x{rt2:02x} {pb.RELIABLE_SUBTYPES.get(rt2, "?")}'
                else:
                    key = f'0x02 raw'
                types[key] += 1
                bytes_per_type[key] += s['len']
            else:
                key = f'0x{s["outer"]:02x} {pb.OUTER_SUBTYPES.get(s["outer"], "?")} raw'
                types[key] += 1
                bytes_per_type[key] += s['len']

    print(f"{'Sub-packet type':<45} {'Count':>6} {'Bytes':>8}")
    print("-" * 65)
    for k, v in sorted(types.items(), key=lambda x: -x[1]):
        print(f"{k:<45} {v:>6} {bytes_per_type[k]:>8}")

    return types, bytes_per_type

import sys
ceresj_path = sys.argv[1] if len(sys.argv) > 1 else '/tmp/nc2_strace.log'
retail_path = sys.argv[2] if len(sys.argv) > 2 else 'strace/nc2_strace_RETAIL_ACC1_CHAR1.log'

rt, rb = analyze("RETAIL", retail_path)
ct, cb = analyze("CERES-J", ceresj_path)

print(f"\n{'='*60}\nDIFF: what retail has that Ceres-J doesn't\n{'='*60}")
print(f"{'Sub-packet type':<45} {'Retail':>7} {'CeresJ':>7} {'Delta':>7}")
print("-" * 70)
all_keys = sorted(set(rt.keys()) | set(ct.keys()))
for k in all_keys:
    r = rt.get(k, 0)
    c = ct.get(k, 0)
    if r == 0 and c == 0: continue
    marker = " <<<" if r > 0 and c == 0 else ""
    print(f"{k:<45} {r:>7} {c:>7} {c-r:>+7}{marker}")
