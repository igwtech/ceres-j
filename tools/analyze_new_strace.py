#!/usr/bin/env python3
"""Analyze the new strace capture with Phase 2 broadcast triad active."""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'home', 'javier', 'Documents', 'Projects', 'Neocron', 'ceres-j'))
os.chdir('/home/javier/Documents/Projects/Neocron/ceres-j')

from pathlib import Path
from collections import Counter
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
        direction = 'S2C' if call == 'recvmsg' else 'C2S'
        data = pb.decode_strace_bytes(m.group(3))
        if data: out.append((ts, direction, data))
    return out

def ts_to_ms(ts):
    h, m, s = ts.split(':')
    s2, us = s.split('.')
    return int(h)*3600000 + int(m)*60000 + int(s2)*1000 + int(us)//1000

def classify(p):
    """Return list of type keys for a decrypted packet."""
    if not p: return []
    if p[0] != 0x13:
        return [f'0x{p[0]:02x} raw']
    parsed = pb.parse_gamedata(p)
    if not parsed: return []
    keys = []
    for s in parsed['subs']:
        if s['outer'] == 0x03:
            rt = s.get('reliable_type', 0)
            inner = s.get('inner_data', b'')
            opc = inner[2] if rt == 0x1f and len(inner) >= 3 else None
            key = f'0x03->0x{rt:02x}'
            if opc is not None: key += f' opc=0x{opc:02x}'
            keys.append(key)
        else:
            keys.append(f'0x{s["outer"]:02x} raw')
    return keys

# Load new capture
print('Loading new Ceres-J capture (/tmp/nc2_strace.log)...')
ceresj = extract('/tmp/nc2_strace.log')
print(f'  {len(ceresj)} UDP packets extracted')

s2c_types = Counter()
c2s_types = Counter()
s2c_count = c2s_count = 0

first_ts = None
last_ts = None

for ts, d, wire in ceresj:
    p = pb.decrypt_wire(wire)
    if not p: continue
    if first_ts is None: first_ts = ts
    last_ts = ts
    keys = classify(p)
    if d == 'S2C':
        s2c_count += 1
        for k in keys: s2c_types[k] += 1
    else:
        c2s_count += 1
        for k in keys: c2s_types[k] += 1

dur_s = (ts_to_ms(last_ts) - ts_to_ms(first_ts)) / 1000.0 if first_ts and last_ts else 0
print(f'Session duration: {dur_s:.1f}s')

print(f'\n=== S2C (server sends) — {s2c_count} pkts ===')
for k, v in s2c_types.most_common(25):
    rate = v/dur_s if dur_s > 0 else 0
    print(f'  {v:5d}  ({rate:5.1f}/s)  {k}')

print(f'\n=== C2S (client sends) — {c2s_count} pkts ===')
for k, v in c2s_types.most_common(25):
    rate = v/dur_s if dur_s > 0 else 0
    print(f'  {v:5d}  ({rate:5.1f}/s)  {k}')

# Compare to retail
print('\n\n=== RETAIL ACC1_CHAR1 (for comparison) ===')
retail = extract('strace/nc2_strace_RETAIL_ACC1_CHAR1.log')
print(f'  {len(retail)} UDP packets extracted')

rs2c = Counter()
rc2s = Counter()
for ts, d, wire in retail:
    p = pb.decrypt_wire(wire)
    if not p: continue
    keys = classify(p)
    if d == 'S2C':
        for k in keys: rs2c[k] += 1
    else:
        for k in keys: rc2s[k] += 1

print('\nRetail S2C:')
for k, v in rs2c.most_common(20):
    print(f'  {v:5d}  {k}')
print('\nRetail C2S:')
for k, v in rc2s.most_common(15):
    print(f'  {v:5d}  {k}')
