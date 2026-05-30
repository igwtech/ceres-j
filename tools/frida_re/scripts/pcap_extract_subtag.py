"""Extract every 0x03/<op>/<sub-tag> sub-packet from a pcap, both
C→S and S→C, with their wire bytes. Used to byte-compare retail
behaviour against what Ceres-J emits for the same opcode triple.

Usage:
    pcap_extract_subtag.py <pcap> <opcode-hex> <sub-tag-hex> [...]

  opcode  = the inner-op byte (sub[3]) e.g. 1f for GamePackets, 22 for Zoning
  sub-tag = the byte AT THE EXPECTED SUB-TAG OFFSET (varies by opcode).
            For 0x1f reliable, the sub-tag lives at sub[4]+sub[5]+sub[6]
            (mapID LE2 then sub-tag byte). We scan sub[6] (post-mapID).
            For 0x22 reliable, sub-tag is sub[4].
            Multiple sub-tags can be passed (filter is OR).

Examples:
    # retail sit (0x03/0x1f/[mapID]/0x17) + posture (0x21)
    pcap_extract_subtag.py RETAIL_LIVE_p1p3_sit_npc.pcap 1f 17 21
    # retail equip (0x03/0x1f/[mapID]/0x1f)
    pcap_extract_subtag.py RETAIL_LIVE_p1p3_sit_npc.pcap 1f 1f
"""
import struct, sys
from pathlib import Path
from collections import Counter

sys.path.insert(0,
    '/home/javier/Documents/Projects/Neocron/ceres-j/tools')
from importlib import import_module
cipher = import_module('decrypt-retail')


def parse_pcap(path):
    with open(path, 'rb') as f:
        gh = f.read(24)
        if len(gh) < 24:
            return
        magic = struct.unpack('<I', gh[:4])[0]
        bo = '<' if magic == 0xa1b2c3d4 else '>'
        link_type = struct.unpack(f'{bo}I', gh[20:24])[0]
        while True:
            rec = f.read(16)
            if len(rec) < 16: break
            ts_s, ts_us, incl, _ = struct.unpack(f'{bo}IIII', rec)
            payload = f.read(incl)
            if len(payload) < incl: break
            if link_type == 1:
                if len(payload) < 14: continue
                if struct.unpack('>H', payload[12:14])[0] != 0x0800:
                    continue
                ip = payload[14:]
            elif link_type == 113:
                if len(payload) < 16: continue
                if struct.unpack('>H', payload[14:16])[0] != 0x0800:
                    continue
                ip = payload[16:]
            elif link_type == 276:
                if len(payload) < 20: continue
                if struct.unpack('>H', payload[0:2])[0] != 0x0800:
                    continue
                ip = payload[20:]
            elif link_type == 101:
                ip = payload
            else:
                continue
            if len(ip) < 20: continue
            ihl = (ip[0] & 0x0f) * 4
            if ip[9] != 17: continue
            sip = '.'.join(str(b) for b in ip[12:16])
            dip = '.'.join(str(b) for b in ip[16:20])
            uo = ihl
            if len(ip) < uo + 8: continue
            sp = struct.unpack('>H', ip[uo:uo+2])[0]
            dp = struct.unpack('>H', ip[uo+2:uo+4])[0]
            ul = struct.unpack('>H', ip[uo+4:uo+6])[0]
            yield (ts_s + ts_us/1e6, sip, sp, dip, dp,
                   ip[uo+8:uo+ul])


def decrypt_one(wire):
    if len(wire) < 4: return None
    try:
        r = cipher.decrypt_wire_packet(wire)
        return r[0] if r else None
    except Exception:
        return None


def walk_subpackets(plain):
    if len(plain) < 5 or plain[0] != 0x13:
        return
    pos = 5
    while pos + 2 <= len(plain):
        sub_len = plain[pos] | (plain[pos+1] << 8)
        pos += 2
        if sub_len == 0 or pos + sub_len > len(plain):
            break
        yield plain[pos:pos+sub_len]
        pos += sub_len


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__.strip())
    pcap, op_hex = sys.argv[1], sys.argv[2]
    op = int(op_hex, 16)
    wanted_subtags = {int(s, 16) for s in sys.argv[3:]}

    # Server ports for direction inference (NC2 uses 5002-5008, 12000)
    SERVER_PORTS = {5002, 5003, 5004, 5005, 5006, 5007, 5008, 12000}

    hits = []
    n_dec = 0
    for ts, sip, sp, dip, dp, body in parse_pcap(pcap):
        plain = decrypt_one(body)
        if plain is None: continue
        n_dec += 1
        for sub in walk_subpackets(plain):
            if len(sub) < 7: continue
            if sub[0] != 0x03: continue
            if sub[3] != op: continue
            # For 0x1f the sub-tag byte sits AFTER [mapID LE2] at
            # sub[4..5], i.e. at sub[6]. For 0x22 the sub-tag is at
            # sub[4]. Other ops not yet generalised — extend as needed.
            if op == 0x1f:
                subtag_off = 6
            elif op == 0x22:
                subtag_off = 4
            else:
                subtag_off = 4
            if subtag_off >= len(sub): continue
            subtag = sub[subtag_off]
            if wanted_subtags and subtag not in wanted_subtags:
                continue
            direction = 'C→S' if dp in SERVER_PORTS else 'S→C'
            hits.append((ts, direction, sip, sp, dip, dp, sub, subtag))

    print(f'=== decoded: {n_dec} udp packets ===')
    print(f'=== 0x03/0x{op:02x} sub-tag hits in '
          f'{{{",".join(f"0x{s:02x}" for s in sorted(wanted_subtags))}}}'
          f': {len(hits)} ===\n')

    by_subtag = Counter()
    by_subtag_dir = Counter()
    by_subtag_lengths = {}
    for ts, dirn, sip, sp, dip, dp, sub, st in hits:
        by_subtag[st] += 1
        by_subtag_dir[(st, dirn)] += 1
        by_subtag_lengths.setdefault(st, Counter())[len(sub)] += 1
        # Don't dump every packet; print first 8 of each (sub-tag, direction)
        # pair to give shape examples.
        key_seen = (st, dirn)

    # Now do a second pass: print first N of each (subtag, direction)
    SHOW_PER_PAIR = 8
    shown = Counter()
    for ts, dirn, sip, sp, dip, dp, sub, st in hits:
        key = (st, dirn)
        if shown[key] >= SHOW_PER_PAIR:
            continue
        shown[key] += 1
        print(f'  T={ts:.3f} {dirn} {sip}:{sp}->{dip}:{dp} '
              f'sub-tag=0x{st:02x} len={len(sub)}')
        print(f'    {sub.hex(" ")}')

    print('\n=== summary by sub-tag ===')
    for st, n in by_subtag.most_common():
        lens = ' '.join(f'{l}:{c}'
                        for l, c in by_subtag_lengths[st].most_common())
        cs = by_subtag_dir.get((st, 'C→S'), 0)
        sc = by_subtag_dir.get((st, 'S→C'), 0)
        print(f'  0x{st:02x}: {n:4d}  C→S={cs:3d} S→C={sc:3d}  '
              f'lengths(byteCount:count)={lens}')


if __name__ == '__main__':
    main()
