#!/usr/bin/env python3
"""Extract the CharInfo body from a frida_re trace.jsonl (S->C).

Handles both delivery paths:
  - single   0x03/0x2c : body = bytes after the 2c opcode
  - multipart 0x03/0x07 disc=0x01 : reassembled from fragments

Prints the preamble + section breakdown so retail and Ceres runs diff
apples-to-apples. Usage: charinfo_from_trace.py <trace.jsonl>
"""
import json, sys, collections

def s2c(path):
    for ln in open(path):
        try: d = json.loads(ln)
        except Exception: continue
        if d.get('ev') == 'udp_recv' and d.get('direction') == 's2c' and d.get('decrypt_ok'):
            b = bytes.fromhex(d['plain_hex'])
            if len(b) >= 11 and b[0] == 0x13:
                yield b

def extract(path):
    single = None
    frags = {}            # frag_idx -> data (disc 01)
    tot = None
    for b in s2c(path):
        op, sub = b[7], b[10]
        body_off = 11
        if sub == 0x2c:                       # single CharInfo (skip pos/state 2c: must look like sections)
            cand = b[body_off:]
            # CharInfo single body looks like 02 XX 01 0a 00 ...  (section1 hdr 01 0a 00)
            if len(cand) >= 6 and cand[2] == 0x01 and cand[3] == 0x0a and cand[4] == 0x00:
                single = cand
        elif sub == 0x07:                     # multipart
            pl = b[body_off:]
            if len(pl) >= 11 and pl[6] == 0x01:   # disc 01 = CharInfo
                fidx = pl[0] | (pl[1] << 8)
                frags[fidx] = pl[11:]
                tot = pl[7] | (pl[8] << 8) | (pl[9] << 16) | (pl[10] << 24)
    if frags:
        body = b''.join(frags[k] for k in sorted(frags))
        return ('multipart', body, tot, len(frags))
    if single is not None:
        return ('single', single, len(single), 1)
    return (None, b'', 0, 0)

def show(label, path):
    kind, body, tot, nfr = extract(path)
    print(f'\n===== {label} =====  path={path}')
    if not kind:
        print('  NO CharInfo found'); return
    print(f'  delivery={kind}  body_len={len(body)}  total_size={tot}  frags={nfr}')
    print(f'  first 12 bytes: {body[:12].hex(" ")}')
    # preamble = bytes before the first 01 0a 00 section header
    i = body.find(bytes([0x01, 0x0a, 0x00]))
    if i >= 0:
        print(f'  preamble ({i}B): {body[:i].hex(" ")}    sections start @ {i}')
        off = i
        while off + 3 <= len(body):
            sid = body[off]; size = body[off+1] | (body[off+2] << 8)
            data = body[off+3:off+3+size]
            head = data[:20].hex(" ")
            print(f'    sec id=0x{sid:02x} size={size:4d}  {head}{" ..." if size>20 else ""}')
            off += 3 + size
            if size == 0 and sid == 0: break
    else:
        print('  (could not locate 01 0a 00 section-1 header)')

if __name__ == '__main__':
    for p in sys.argv[1:]:
        show(p.split('/')[-2] if '/' in p else p, p)
