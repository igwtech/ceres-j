#!/usr/bin/env python3
"""Compare JUST the initial burst (first 2 seconds after zone handoff)
between retail and Ceres-J. Focus on: exact order, sizes, and data
content of every packet — what does the client receive to initialize?"""
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

def analyze_burst(name, path, burst_window_ms=3000):
    """Show all S→C packets in the first burst_window_ms after the first
    0x13 gamedata packet (zone entry). This is the initialization data."""
    pkts = extract(path)
    print(f"\n{'='*70}\n{name}\n{'='*70}")

    # Find the first S→C 0x13 packet (start of zone data)
    burst_start = None
    total_s2c_bytes = 0
    total_s2c_pkts = 0
    sub_types = Counter()
    sub_bytes = Counter()

    for ts, d, wire in pkts:
        if d != 'S2C': continue
        p = pb.decrypt_wire(wire)
        if not p: continue
        if p[0] == 0x13 and burst_start is None:
            burst_start = ts_ms(ts)

        if burst_start is None: continue
        elapsed = ts_ms(ts) - burst_start
        if elapsed > burst_window_ms: break

        total_s2c_bytes += len(p)
        total_s2c_pkts += 1

        if p[0] != 0x13:
            key = f'outer 0x{p[0]:02x}'
            sub_types[key] += 1
            sub_bytes[key] += len(p)
            continue

        parsed = pb.parse_gamedata(p, len_bytes=2)
        if not parsed: continue

        for s in parsed['subs']:
            if s['outer'] == 0x03:
                rt = s.get('reliable_type', 0)
                rname = pb.RELIABLE_SUBTYPES.get(rt, f'0x{rt:02x}')
                key = f'0x03→0x{rt:02x} {rname}'
            elif s['outer'] == 0x02:
                inner = s['data']
                if len(inner) >= 4:
                    rt2 = inner[3]
                    rname = pb.RELIABLE_SUBTYPES.get(rt2, f'0x{rt2:02x}')
                    key = f'0x02→0x{rt2:02x} {rname}'
                else:
                    key = f'0x02 raw'
            else:
                key = f'0x{s["outer"]:02x} raw'
            sub_types[key] += 1
            sub_bytes[key] += s['len']

    print(f"Burst window: {burst_window_ms}ms")
    print(f"Total S→C packets: {total_s2c_pkts}")
    print(f"Total S→C bytes (plaintext): {total_s2c_bytes}")
    print(f"\n{'Sub-packet type':<45} {'Count':>6} {'Bytes':>8}")
    print("-" * 65)
    for k, v in sorted(sub_types.items(), key=lambda x: -sub_bytes[x[0]]):
        print(f"{k:<45} {v:>6} {sub_bytes[k]:>8}")
    return sub_types, sub_bytes

# Analyze all captures
for name, path in [
    ("RETAIL ACC1_CHAR1", "strace/nc2_strace_RETAIL_ACC1_CHAR1.log"),
    ("RETAIL ACC2_CHAR1", "strace/nc2_strace_RETAIL_ACC2_CHAR1.log"),
    ("CERES-J (latest)", "/tmp/nc2_strace.log"),
]:
    analyze_burst(name, path)
