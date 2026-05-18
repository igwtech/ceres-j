#!/usr/bin/env python3
"""npc-lifecycle.py — extract per-NPC S->C lifecycle from a Neocron 2 pcap.

Frame format (verified 2026-05-17):
  13 [outerctr LE2] [ackkey LE2]
  then repeating sub-packets:
    [sublen LE2] [wrapper 1B: 0x03 reliable | 0x02 ack-chan]
    [seq LE2] [opcode 1B] [data: sublen-4 bytes]
"""
from __future__ import annotations
import argparse, sys
from pathlib import Path
from importlib import import_module

sys.path.insert(0, str(Path(__file__).parent))
decrypt_mod = import_module("decrypt-retail")
from scapy.all import PcapReader, IP, UDP


def u16(b, o):
    return b[o] | (b[o + 1] << 8)


def parse_frame(plain):
    """Yield (wrapper, seq, opcode, payload_incl_op)."""
    i = 5
    n = len(plain)
    while i + 2 <= n:
        sl = u16(plain, i)
        if sl == 0 or i + 2 + sl > n:
            return
        sp = plain[i + 2:i + 2 + sl]
        if len(sp) < 4:
            i += 2 + sl
            continue
        w = sp[0]
        seq = u16(sp, 1)
        op = sp[3]
        yield (w, seq, op, sp[3:])
        i += 2 + sl


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", "-i", type=Path, required=True)
    ap.add_argument("--server-ip", required=True)
    ap.add_argument("--dir", choices=("s2c", "c2s", "both"), default="s2c")
    ap.add_argument("--entity", default=None,
                    help="hex entity id (LE16) to filter, e.g. 0a01")
    ap.add_argument("--opcodes", default=None,
                    help="comma list of hex opcodes, e.g. 28,2d,26,1b,07")
    ap.add_argument("--max", type=int, default=0)
    args = ap.parse_args()

    want_ops = None
    if args.opcodes:
        want_ops = set(int(x, 16) for x in args.opcodes.split(","))
    ent = bytes.fromhex(args.entity) if args.entity else None

    n = 0
    with PcapReader(str(args.input)) as pr:
        for pkt in pr:
            if args.max and n >= args.max:
                break
            if IP not in pkt or UDP not in pkt:
                continue
            src, dst = pkt[IP].src, pkt[IP].dst
            if args.server_ip not in (src, dst):
                continue
            direction = "S2C" if src == args.server_ip else "C2S"
            if args.dir == "s2c" and direction != "S2C":
                continue
            if args.dir == "c2s" and direction != "C2S":
                continue
            payload = bytes(pkt[UDP].payload)
            if not payload:
                continue
            res = decrypt_mod.decrypt_wire_packet(payload)
            if not res:
                continue
            plain, dlen = res
            if not plain or plain[0] != 0x13 or len(plain) < 7:
                continue
            ts = float(pkt.time)
            octr = u16(plain, 1)
            for w, seq, op, pl in parse_frame(plain):
                if want_ops is not None and op not in want_ops:
                    continue
                if ent is not None and ent not in pl:
                    continue
                print(f"{ts:.6f} {direction} octr={octr:#06x} w=0x{w:02x} "
                      f"seq={seq:#06x} op=0x{op:02x} len={len(pl):3d} {pl.hex()}")
            n += 1


if __name__ == "__main__":
    main()
