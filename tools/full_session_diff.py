#!/usr/bin/env python3
"""Full packet-for-packet session comparison: retail vs Ceres-J.
Shows EVERY packet in both sessions side by side with timestamps,
directions, sizes, and decoded sub-types. Not a histogram — the
actual chronological flow."""
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

def extract(path):
    with open(path,'r',errors='replace') as f: content = f.read()
    out = []
    for m in _RE.finditer(content):
        ts,call = m.group(1),m.group(2)
        n = int(m.group(4))
        if n<=0: continue
        d = 'S2C' if call=='recvmsg' else 'C2S'
        data = pb.decode_strace_bytes(m.group(3))
        if data: out.append((ts,d,data))
    return out

def decode_packet(wire):
    p = pb.decrypt_wire(wire)
    if not p: return None, []
    if p[0] != 0x13:
        return p, [('outer', p[0], None, len(p))]
    parsed = pb.parse_gamedata(p, len_bytes=2)
    if not parsed: return p, []
    subs = []
    for s in parsed['subs']:
        if s['outer'] == 0x03:
            rt = s.get('reliable_type',0)
            inner = s.get('inner_data',b'')
            subs.append(('rel', rt, inner, s['len']))
        elif s['outer'] == 0x02:
            d = s['data']
            rt2 = d[3] if len(d)>=4 else 0
            subs.append(('rel02', rt2, d[4:] if len(d)>=4 else b'', s['len']))
        else:
            subs.append(('raw', s['outer'], s['data'], s['len']))
    return p, subs

def session_flow(name, path, max_secs=40):
    pkts = extract(path)
    print(f"\n{'='*80}")
    print(f"SESSION: {name} ({len(pkts)} UDP packets)")
    print(f"{'='*80}")

    start_ms = None
    for ts,d,w in pkts:
        p = pb.decrypt_wire(w)
        if p and p[0] == 0x13:
            start_ms = ts_ms(ts)
            break
    if not start_ms:
        start_ms = ts_ms(pkts[0][0]) if pkts else 0

    for ts,d,wire in pkts:
        ms = ts_ms(ts) - start_ms
        if ms > max_secs*1000: break
        if ms < -2000: continue

        p, subs = decode_packet(wire)
        if not p: continue

        dir_str = '>>>' if d == 'S2C' else '<<<'

        if not subs:
            print(f"{ms:>8}ms {dir_str} {len(wire):>4}B wire/{len(p):>4}B plain  0x{p[0]:02x}")
            continue

        sub_strs = []
        for kind, typ, inner, sz in subs:
            if kind == 'outer':
                sub_strs.append(f"0x{typ:02x}({sz}B)")
            elif kind == 'rel':
                name_s = pb.RELIABLE_SUBTYPES.get(typ, f'0x{typ:02x}')
                extra = ''
                if typ == 0x1f and len(inner) >= 3:
                    extra = f'/0x{inner[2]:02x}'
                elif typ == 0x07:
                    extra = f'/{sz}B'
                sub_strs.append(f"R:{name_s}{extra}")
            elif kind == 'rel02':
                name_s = pb.RELIABLE_SUBTYPES.get(typ, f'0x{typ:02x}')
                sub_strs.append(f"U:{name_s}")
            elif kind == 'raw':
                name_s = pb.OUTER_SUBTYPES.get(typ, f'0x{typ:02x}')
                sub_strs.append(f"{name_s}({sz}B)")

        summary = ', '.join(sub_strs[:6])
        if len(sub_strs) > 6:
            summary += f' +{len(sub_strs)-6}more'
        print(f"{ms:>8}ms {dir_str} {len(wire):>4}B/{len(p):>4}B  [{len(subs):>2}] {summary}")

ceresj_path = sys.argv[1] if len(sys.argv) > 1 else '/tmp/nc2_strace.log'
retail_path = sys.argv[2] if len(sys.argv) > 2 else 'strace/nc2_strace_RETAIL_ACC1_CHAR1.log'

session_flow("RETAIL", retail_path, max_secs=25)
session_flow("CERES-J", ceresj_path, max_secs=40)
