"""Extract TCP segments from a NC2 retail pcap (no scapy).

NC2 TCP is FE-framed: [fe][size:LE2][opcode_hi][opcode_lo][body...]
A single TCP segment may carry multiple FE frames OR a fragment of one.

Usage: python pcap_extract_tcp.py <pcap> [--limit N]
"""
from __future__ import annotations

import sys
import argparse
import struct
from pathlib import Path
from collections import Counter, defaultdict

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import pcap_extract_zoning as base  # noqa: E402


def parse_tcp(path):
    """Yield (ts, src_ip, src_port, dst_ip, dst_port, payload) for each
    TCP segment with non-empty payload."""
    with open(path, 'rb') as f:
        gh = f.read(24)
        if len(gh) < 24: return
        magic = struct.unpack('<I', gh[:4])[0]
        if magic == 0xa1b2c3d4:
            bo = '<'
        elif magic == 0xd4c3b2a1:
            bo = '>'
        else:
            return
        link_type = struct.unpack(f'{bo}I', gh[20:24])[0]
        while True:
            rec = f.read(16)
            if len(rec) < 16: break
            ts_sec, ts_usec, incl_len, _ = struct.unpack(
                f'{bo}IIII', rec)
            payload = f.read(incl_len)
            if len(payload) < incl_len: break
            # Strip link layer.
            if link_type == 1:
                if len(payload) < 14: continue
                if struct.unpack('>H', payload[12:14])[0] != 0x0800:
                    continue
                ip_pkt = payload[14:]
            elif link_type == 113:
                if len(payload) < 16: continue
                if struct.unpack('>H', payload[14:16])[0] != 0x0800:
                    continue
                ip_pkt = payload[16:]
            elif link_type == 276:
                if len(payload) < 20: continue
                if struct.unpack('>H', payload[0:2])[0] != 0x0800:
                    continue
                ip_pkt = payload[20:]
            elif link_type == 101:
                ip_pkt = payload
            elif link_type == 0:
                if len(payload) < 4: continue
                ip_pkt = payload[4:]
            else:
                continue
            # IPv4 → TCP
            if len(ip_pkt) < 20: continue
            ihl = (ip_pkt[0] & 0x0f) * 4
            if ip_pkt[9] != 6: continue  # TCP only
            src_ip = '.'.join(str(b) for b in ip_pkt[12:16])
            dst_ip = '.'.join(str(b) for b in ip_pkt[16:20])
            tcp_off = ihl
            if len(ip_pkt) < tcp_off + 20: continue
            sport = struct.unpack('>H', ip_pkt[tcp_off:tcp_off+2])[0]
            dport = struct.unpack('>H', ip_pkt[tcp_off+2:tcp_off+4])[0]
            data_off = (ip_pkt[tcp_off+12] >> 4) * 4
            tcp_payload = ip_pkt[tcp_off+data_off:]
            if not tcp_payload: continue
            yield (ts_sec + ts_usec/1e6, src_ip, sport,
                   dst_ip, dport, tcp_payload)


def walk_fe_frames(stream):
    """Yield (opcode_hi, opcode_lo, body_bytes) from a buffer that
    may contain multiple `fe`-framed messages. Stops at end or
    malformed frame."""
    pos = 0
    while pos + 5 <= len(stream):
        if stream[pos] != 0xfe:
            # Some segments don't start with fe — could be a chunk
            # continuation. Skip 1 byte and keep looking.
            pos += 1
            continue
        size = stream[pos+1] | (stream[pos+2] << 8)
        if size < 2 or pos + 3 + size > len(stream):
            break
        op_hi = stream[pos+3]
        op_lo = stream[pos+4]
        body = stream[pos+5:pos+3+size]
        yield (op_hi, op_lo, body)
        pos += 3 + size


def main(argv=None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("pcap")
    p.add_argument("--limit", type=int, default=8,
                   help="dump N samples per opcode")
    p.add_argument("--focus", default=None,
                   help="comma-sep list of `hi/lo` opcodes to dump in "
                        "full (e.g. 83/0c,83/0d,a0/02)")
    args = p.parse_args(argv)

    focus = set()
    if args.focus:
        for tok in args.focus.split(','):
            hi, lo = tok.strip().split('/')
            focus.add((int(hi, 16), int(lo, 16)))

    opcode_counter = Counter()
    samples = defaultdict(list)
    focus_dump = []
    total_segs = 0
    total_payload = 0

    for ts, sip, sp, dip, dp, payload in parse_tcp(args.pcap):
        total_segs += 1
        total_payload += len(payload)
        is_c2s = dp in {5002, 5003, 5004, 5005, 5006, 5007, 5008, 12000}
        direction = 'C→S' if is_c2s else 'S→C'
        for op_hi, op_lo, body in walk_fe_frames(payload):
            key = (op_hi, op_lo, direction)
            opcode_counter[key] += 1
            if len(samples[key]) < args.limit:
                samples[key].append((ts, len(body), body[:32]))
            if (op_hi, op_lo) in focus:
                focus_dump.append((ts, direction, op_hi, op_lo, body))

    print(f"=== TCP stats ===")
    print(f"  segments: {total_segs}")
    print(f"  total payload bytes: {total_payload}")

    print(f"\n=== TCP opcode distribution (FE-framed) ===")
    print(f"{'opcode':<12} {'dir':<5} {'count':>6}")
    for (hi, lo, d), n in sorted(opcode_counter.items(),
                                  key=lambda x: -x[1]):
        print(f"  0x{hi:02x}/0x{lo:02x}    {d}    {n}")

    if focus_dump:
        print(f"\n=== focus opcodes (full body) ===")
        for ts, d, hi, lo, body in focus_dump[:args.limit]:
            print(f"  T={ts:.3f}  {d}  0x{hi:02x}/0x{lo:02x}  "
                  f"len={len(body)}")
            print(f"    body: {body.hex(' ')}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
