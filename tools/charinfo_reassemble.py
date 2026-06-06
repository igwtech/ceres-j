#!/usr/bin/env python3
"""Decrypt a NC2 pcap, reassemble the 0x03/0x07 (and 0x2c) CharInfo blob(s),
then parse the section TLV stream and dump Section 5 (F2 inventory) item
records field-by-field. Authoritative retail reference for the F2 bug."""
from __future__ import annotations
import argparse, struct, sys
from collections import Counter, defaultdict
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
dec = import_module("decrypt-retail")
from scapy.all import PcapReader, IP, UDP

def hx(b): return b.hex(' ')

def reassemble(plains):
    """plains: list of decrypted UDP plaintexts (S->C). Find 0x13 wrappers,
    pull reliable 0x03 sub-packets, collect 0x07 multipart frags by (disc),
    reassemble each CharInfo body. Also catch single 0x2c."""
    # Map: frag streams keyed by discriminator -> dict frag_idx->chunk, plus total
    streams = defaultdict(dict)
    totals = {}
    singles = []
    for p in plains:
        if not p or p[0] != 0x13: continue
        # outer wrapper: 13 [seq2] [??] ... then inner 03 reliable.
        # find '03' reliable sub-packet(s). Inner format: 03 [seq2] [type] [body...]
        # The 7-byte outer header then 03 ...
        # Scan for 0x03 reliable opcode after the 7-byte outer.
        body = p[7:]
        if len(body) < 3: continue
        if body[0] != 0x03:
            continue
        rtype = body[3]
        inner = body[4:]
        if rtype == 0x07:
            # frag_idx LE2, total_frags LE4, disc 1B, total_size LE4, chunk...
            if len(inner) < 11: continue
            frag_idx = inner[0] | (inner[1]<<8)
            total_frags = inner[2] | (inner[3]<<8) | (inner[4]<<16) | (inner[5]<<24)
            disc = inner[6]
            total_size = inner[7] | (inner[8]<<8) | (inner[9]<<16) | (inner[10]<<24)
            chunk = inner[11:]
            streams[disc][frag_idx] = chunk
            totals[disc] = (total_frags, total_size)
        elif rtype == 0x2c:
            singles.append(inner)
    out = {}
    for disc, frags in streams.items():
        nf, ts = totals[disc]
        blob = b''.join(frags[i] for i in sorted(frags))
        out[('multi', disc)] = (blob, nf, ts)
    for i, s in enumerate(singles):
        out[('single', i)] = (s, 1, len(s))
    return out

def parse_sections(blob):
    """blob starts with 22 02 01 then sections; or 02 01 then sections (single).
    Section format: [id u8][size LE2][body]."""
    # normalize: skip leading 22 if present
    off = 0
    if len(blob) >= 3 and blob[0]==0x22 and blob[1]==0x02 and blob[2]==0x01:
        off = 3
    elif len(blob) >= 2 and blob[0]==0x02 and blob[1]==0x01:
        off = 2
    secs = []
    while off + 3 <= len(blob):
        sid = blob[off]
        size = blob[off+1] | (blob[off+2]<<8)
        bodystart = off+3
        body = blob[bodystart:bodystart+size]
        secs.append((sid, size, body))
        off = bodystart + size
        if sid == 0x0d:  # footer
            break
    return secs

def dump_inventory(body):
    """Section 5 body: [num LE2] then per item:
    [reclen LE2][00][posX][posY][type LE2][infobyte][...]."""
    if len(body) < 2:
        print("  (empty)"); return
    num = body[0] | (body[1]<<8)
    print(f"  num_items = {num}")
    off = 2
    idx = 0
    while off + 2 <= len(body) and idx < num:
        reclen = body[off] | (body[off+1]<<8)
        rec = body[off+2:off+2+reclen]
        print(f"  [item {idx}] reclen={reclen} rec={hx(rec)}")
        off += 2 + reclen
        idx += 1
    if off < len(body):
        print(f"  trailing {len(body)-off} bytes: {hx(body[off:])}")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input","-i",type=Path,required=True)
    ap.add_argument("--server-ip",default=None)
    args = ap.parse_args()
    if not args.server_ip:
        pc = Counter()
        with PcapReader(str(args.input)) as pr:
            for i,pkt in enumerate(pr):
                if i>5000: break
                if IP not in pkt: continue
                for ip in (pkt[IP].src,pkt[IP].dst):
                    if not (ip.startswith("127.") or ip.startswith("192.168.")):
                        pc[ip]+=1
        # prefer non-172 if available else most common
        args.server_ip = pc.most_common(1)[0][0]
        print(f"[auto] server IP guess = {args.server_ip}")
    s2c=[]
    with PcapReader(str(args.input)) as pr:
        for pkt in pr:
            if IP not in pkt or UDP not in pkt: continue
            if pkt[IP].src != args.server_ip: continue
            pl = bytes(pkt[UDP].payload)
            if not pl: continue
            r = dec.decrypt_wire_packet(pl)
            if r: s2c.append(r[0])
    print(f"decrypted {len(s2c)} S->C UDP")
    out = reassemble(s2c)
    for key,(blob,nf,ts) in out.items():
        if len(blob) < 50: continue
        # Only interested in CharInfo-looking blobs
        norm = blob
        if not (blob[:1]==b'\x22' or blob[:2]==b'\x02\x01'):
            continue
        print(f"\n===== {key} reassembled {len(blob)}B (frags={nf} declared_total={ts}) =====")
        print("head:", hx(blob[:40]))
        secs = parse_sections(blob)
        for sid,size,sbody in secs:
            print(f" SECTION 0x{sid:02x} size={size}")
            if sid == 0x05:
                dump_inventory(sbody)

if __name__=="__main__":
    main()
