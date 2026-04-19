#!/usr/bin/env python3
"""Decode ALL combat-related packets from the retail death capture.
Focus on the 30 seconds of combat before death (02:27:50 - 02:28:21).
Extract every S→C packet that could be damage/HP/combat related."""
import os, struct; os.chdir('/home/javier/Documents/Projects/Neocron/ceres-j')
from pathlib import Path
from collections import Counter
import importlib.util, re, sys
spec = importlib.util.spec_from_file_location('pb', 'tools/parse-burst.py')
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

_RE = re.compile(
    r'^\d+\s+(\d+:\d+:\d+\.\d+)\s+(recvmsg|sendmsg)\(\d+<UDP[^>]*>.*?iov_base="((?:[^"\\]|\\.)*)".*?= (\d+)$',
    re.MULTILINE,
)

def ts_ms(ts):
    h,m,s = ts.split(':'); s2,us = s.split('.')
    return int(h)*3600000+int(m)*60000+int(s2)*1000+int(us)//1000

path = 'strace/nc2_strace_RETAIL_DEATH.log'
death_ms = 2*3600000 + 28*60000 + 21*1000  # 02:28:21

with open(path, 'r', errors='replace') as f: content = f.read()

# Collect S→C packets in the 30 seconds before death
packets = []
for m in _RE.finditer(content):
    ts, call = m.group(1), m.group(2)
    n = int(m.group(4))
    if n <= 0: continue
    ms = ts_ms(ts)
    if ms < death_ms - 30000 or ms > death_ms + 5000: continue
    if call != 'recvmsg': continue  # S→C only
    data = pb.decode_strace_bytes(m.group(3))
    if data: packets.append((ms, data))

print(f"S→C packets in combat window: {len(packets)}")

# Decode and find combat-relevant sub-packets
combat_opcodes = Counter()
for ms, wire in packets:
    p = pb.decrypt_wire(wire)
    if not p or p[0] != 0x13: continue
    parsed = pb.parse_gamedata(p, len_bytes=2)
    if not parsed: continue
    for s in parsed['subs']:
        rel = ms - death_ms
        if s['outer'] == 0x03 and s.get('reliable_type') == 0x1f:
            inner = s.get('inner_data', b'')
            if len(inner) >= 3:
                opc = inner[2]
                # Show ALL unique opcodes inside 0x03→0x1f
                combat_opcodes[f'R:0x1f opc=0x{opc:02x}'] += 1
                # Dump anything that's NOT TimeSync (0x25) with inner[3:4] == 0x23
                if opc != 0x25 or (len(inner) >= 4 and inner[3] != 0x23):
                    if opc == 0x16 or opc == 0x06 or opc == 0x25:
                        print(f'{rel:>+6}ms R:0x1f opc=0x{opc:02x} ({len(inner)}B): {inner.hex()}')
        elif s['outer'] == 0x1f:
            d = s['data']
            if len(d) >= 4:
                opc = d[3]
                combat_opcodes[f'raw:0x1f opc=0x{opc:02x}'] += 1
                # Show 0x50 (HP update) and any non-standard
                if opc == 0x50 or opc == 0x30:
                    print(f'{rel:>+6}ms raw:0x1f opc=0x{opc:02x} ({len(d)}B): {d.hex()}')

print(f"\n=== S→C sub-opcode histogram (inside 0x1f) ===")
for k, v in combat_opcodes.most_common():
    print(f"  {v:4d}x  {k}")

# Also show the FIRST damage event in full detail
print(f"\n=== First R:0x1f 0x25 0x06 packet (damage event) ===")
for ms, wire in packets:
    p = pb.decrypt_wire(wire)
    if not p or p[0] != 0x13: continue
    parsed = pb.parse_gamedata(p, len_bytes=2)
    if not parsed: continue
    for s in parsed['subs']:
        if s['outer'] == 0x03 and s.get('reliable_type') == 0x1f:
            inner = s.get('inner_data', b'')
            if len(inner) >= 4 and inner[2] == 0x25 and inner[3] == 0x06:
                print(f'  full inner ({len(inner)}B): {inner.hex()}')
                print(f'  offset 0-1: mapId = {inner[0] | (inner[1]<<8)}')
                print(f'  offset 2-3: opcode = 0x{inner[2]:02x} 0x{inner[3]:02x}')
                for i in range(4, len(inner)):
                    print(f'  offset {i}: 0x{inner[i]:02x} ({inner[i]})')
                break
    else: continue
    break
