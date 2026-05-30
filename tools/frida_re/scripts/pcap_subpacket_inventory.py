"""Inventory every reliable/raw sub-packet in a pcap by direction.

For each sub-packet inside the 0x13 wrapper, classifies as:
  - reliable 0x03/<op>[/<sub-tag>] — op-sub-tag pair counted
  - raw <op> — outer op counted

Outputs a single summary table sorted by C→S then S→C count.

Usage:
  pcap_subpacket_inventory.py <pcap>
"""
import struct, sys
from collections import Counter
from pathlib import Path

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
            yield (sp, dp, ip[uo+8:uo+ul])


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


SERVER_PORTS = {5002, 5003, 5004, 5005, 5006, 5007, 5008, 12000}


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__.strip())
    pcap = sys.argv[1]
    cs = Counter()  # (channel, op, subtag) -> count
    sc = Counter()
    n_dec = 0
    n_subs = 0
    for sp, dp, body in parse_pcap(pcap):
        plain = decrypt_one(body)
        if plain is None: continue
        n_dec += 1
        is_cs = dp in SERVER_PORTS
        for sub in walk_subpackets(plain):
            n_subs += 1
            if len(sub) < 1: continue
            w = sub[0]
            if w in (0x03, 0x02):
                # Reliable: sub[3] = op, then sub[6] = sub-tag for op 0x1f
                if len(sub) < 4:
                    key = (f'0x{w:02x}', 'short', '-')
                else:
                    op = sub[3]
                    if op == 0x1f and len(sub) >= 7:
                        st = sub[6]
                        key = ('reliable',
                               f'0x{op:02x}', f'0x{st:02x}')
                    elif op == 0x22 and len(sub) >= 5:
                        st = sub[4]
                        key = ('reliable',
                               f'0x{op:02x}', f'0x{st:02x}')
                    elif op == 0x25 and len(sub) >= 5:
                        st = sub[4]
                        key = ('reliable',
                               f'0x{op:02x}', f'0x{st:02x}')
                    else:
                        key = ('reliable', f'0x{op:02x}', '-')
            else:
                key = ('raw', f'0x{w:02x}', '-')
            if is_cs:
                cs[key] += 1
            else:
                sc[key] += 1

    print(f'=== decoded {n_dec} udp packets, {n_subs} sub-packets ===\n')
    all_keys = sorted(set(cs) | set(sc),
                      key=lambda k: -(cs[k] + sc[k]))
    print(f'{"channel":10} {"op":>6} {"sub-tag":>9}'
          f' {"C->S":>7} {"S->C":>7}')
    print('-' * 50)
    for k in all_keys:
        ch, op, st = k
        print(f'{ch:10} {op:>6} {st:>9} {cs[k]:>7} {sc[k]:>7}')


if __name__ == '__main__':
    main()
