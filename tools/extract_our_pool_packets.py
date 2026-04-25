#!/usr/bin/env python3
"""Extract our (Ceres-J) outgoing PoolStatus and PoolUpdate packets from
/tmp/nc2_strace.log so we can verify what the server actually sends to
the client during a live test."""
import os, sys, struct
os.chdir('/home/javier/Documents/Projects/Neocron/ceres-j')
import importlib.util
spec = importlib.util.spec_from_file_location('pb', 'tools/parse-burst.py')
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

import re

_RE = re.compile(
    r'^\d+\s+(\d+:\d+:\d+\.\d+)\s+(recvmsg|sendmsg)\(\d+<UDP[^>]*>.*?iov_base="((?:[^"\\]|\\.)*)".*?= (\d+)$',
    re.MULTILINE,
)


def ts_ms(ts):
    h,m,s = ts.split(':'); s2,us = s.split('.')
    return int(h)*3600000+int(m)*60000+int(s2)*1000+int(us)//1000


path = sys.argv[1] if len(sys.argv) > 1 else '/tmp/nc2_strace.log'
with open(path, 'r', errors='replace') as f:
    content = f.read()

# Find chat events (e.g. "!hp", "!damage 50", "!heal") to know when in
# the trace to focus our search. We'll scan all S2C 0x1f packets within
# 5 seconds of each user-initiated chat.
events = []
for m in _RE.finditer(content):
    ts, call = m.group(1), m.group(2)
    n = int(m.group(4))
    if n <= 0: continue
    ms = ts_ms(ts)
    data = pb.decode_strace_bytes(m.group(3))
    if not data: continue
    events.append((ms, call, data))

print(f'parsed {len(events)} packets')

# Walk in time order, emit any 0x1f-related decoded payload from S2C.
samples = 0
last_pool_status = None
for ms, call, wire in events:
    if call != 'recvmsg':  # client receives = server sent
        continue
    p = pb.decrypt_wire(wire)
    if not p or p[0] != 0x13:
        continue
    parsed = pb.parse_gamedata(p, len_bytes=2)
    if not parsed:
        continue
    for s in parsed['subs']:
        outer = s.get('outer')
        rt = s.get('reliable_type')
        inner = s.get('inner_data', b'') or s.get('data', b'')

        # Build a synthetic packet that starts with the type byte
        if outer == 0x03 and rt is not None:
            packet = bytes([rt]) + inner
        elif outer is not None:
            # raw 0x?? sub-packet — outer IS the type byte already in inner
            packet = inner if inner and inner[0] == outer else bytes([outer]) + inner
        else:
            continue

        # Filter to 0x1f variants
        if packet[0] != 0x1f or len(packet) < 4:
            continue
        if packet[1] != 0x01 or packet[2] != 0x00:
            continue
        sub = packet[3]
        if sub == 0x30 and len(packet) >= 14:
            hp = struct.unpack('<H', packet[4:6])[0]
            psi = struct.unpack('<H', packet[6:8])[0]
            sta = struct.unpack('<H', packet[8:10])[0]
            ma = struct.unpack('<H', packet[10:12])[0]
            mb = struct.unpack('<H', packet[12:14])[0]
            line = f'POOL_STATUS  HP={hp:>4} PSI={psi:>4} STA={sta:>4} maxA={ma:>4} maxB={mb:>4}'
            if line == last_pool_status:
                continue
            last_pool_status = line
            print(f'{ms/1000:>8.3f}s  {line}  hex={packet.hex()}')
            samples += 1
        elif sub == 0x50 and len(packet) >= 16:
            delta = struct.unpack('<i', packet[4:8])[0]
            pool = packet[11]
            mx = struct.unpack('<H', packet[12:14])[0]
            print(f'{ms/1000:>8.3f}s  POOL_DELTA   delta={delta:+5}  pool=0x{pool:02x}  '
                  f'max={mx}  hex={packet.hex()}')
            samples += 1
        elif sub == 0x25:
            # damage event
            print(f'{ms/1000:>8.3f}s  DAMAGE_EVENT hex={packet.hex()}')
            samples += 1

print(f'\n{samples} pool-related events emitted')
