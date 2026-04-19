#!/usr/bin/env python3
"""Find the exact packets around the death event in the retail capture.
Death happened at 02:28:21 per console.log."""
import os; os.chdir('/home/javier/Documents/Projects/Neocron/ceres-j')
from pathlib import Path
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

path = sys.argv[1] if len(sys.argv) > 1 else 'strace/nc2_strace_RETAIL_DEATH.log'
# Death at 02:28:21 = 02*3600000 + 28*60000 + 21*1000 = 8901000
death_ms = 2*3600000 + 28*60000 + 21*1000

print(f"Searching for packets around death at {death_ms}ms...")

with open(path, 'r', errors='replace') as f: content = f.read()

packets = []
for m in _RE.finditer(content):
    ts, call = m.group(1), m.group(2)
    n = int(m.group(4))
    if n <= 0: continue
    ms = ts_ms(ts)
    # Only keep packets within 10 seconds of death
    if abs(ms - death_ms) > 10000: continue
    d = 'S2C' if call == 'recvmsg' else 'C2S'
    data = pb.decode_strace_bytes(m.group(3))
    if data: packets.append((ms, d, data))

print(f"Found {len(packets)} packets within 10s of death")

for ms, d, wire in packets:
    p = pb.decrypt_wire(wire)
    if not p: continue
    rel = ms - death_ms
    dir_str = '>>>' if d == 'S2C' else '<<<'

    if p[0] != 0x13:
        print(f"{rel:>+6}ms {dir_str} {len(p):>4}B  outer=0x{p[0]:02x}")
        continue

    parsed = pb.parse_gamedata(p, len_bytes=2)
    if not parsed: continue

    subs = []
    for s in parsed['subs']:
        if s['outer'] == 0x03:
            rt = s.get('reliable_type', 0)
            inner = s.get('inner_data', b'')
            name = pb.RELIABLE_SUBTYPES.get(rt, f'0x{rt:02x}')
            # Show first 20 bytes of inner data for death-related packets
            inner_hex = inner[:20].hex() if len(inner) <= 20 else inner[:20].hex() + '...'
            subs.append(f'R:0x{rt:02x} {name}({s["len"]}B) [{inner_hex}]')
        else:
            data_hex = s['data'][:16].hex()
            subs.append(f'0x{s["outer"]:02x}({s["len"]}B) [{data_hex}]')

    for sub in subs:
        print(f"{rel:>+6}ms {dir_str} {sub}")
