#!/usr/bin/env python3
"""Unwrap the UDP 0x13 reliable channel into inner 0x03/<op>[/<subtag>]
sub-packets, and dump TCP frames, on one timeline. RE-only tool.

13 [octr LE2] [octr+sk LE2] ( [subLen LE2] [03] [seq LE2] [op] [data] )+
TCP: fe [len LE2] [subsystem] [op] [body...]
Usage: unwrap13.py <both_full.txt-from-pcap-decode>
"""
import sys, re

LINE = re.compile(r'^([\d.]+)\s+(C→S|S→C)\s+(UDP|TCP).*?\s([0-9a-f]+)\s*$')

def parse_13(hexs):
    b = bytes.fromhex(hexs)
    if len(b) < 5 or b[0] != 0x13:
        return None
    off = 5
    subs = []
    while off + 2 <= len(b):
        sublen = b[off] | (b[off+1] << 8)
        off += 2
        if sublen == 0 or off + sublen > len(b):
            break
        sub = b[off:off+sublen]
        off += sublen
        if len(sub) >= 4 and sub[0] == 0x03:
            seq = sub[1] | (sub[2] << 8)
            op = sub[3]
            data = sub[4:]
            subtag = data[0] if len(data) else None
            subs.append((seq, op, subtag, data.hex()))
    return subs

def main():
    with open(sys.argv[1]) as f:
        for line in f:
            m = LINE.match(line.rstrip())
            if not m:
                continue
            ts, dirn, proto, hexs = m.groups()
            if proto == 'TCP':
                b = bytes.fromhex(hexs)
                if len(b) >= 5 and b[0] == 0xfe:
                    ln = b[1] | (b[2] << 8)
                    sub = b[3]; op = b[4]
                    print(f"{ts} {dirn} TCP   sys=0x{sub:02x} op=0x{op:02x} len={ln:3d}  {hexs}")
                continue
            if not hexs.startswith('13'):
                continue
            subs = parse_13(hexs)
            if not subs:
                continue
            for seq, op, subtag, data in subs:
                st = f"/0x{subtag:02x}" if subtag is not None else ""
                # only show interesting ops (skip pure ack-ish noise filter optional)
                print(f"{ts} {dirn} U13 seq={seq:5d} 0x03/0x{op:02x}{st}  {data}")

if __name__ == '__main__':
    main()
