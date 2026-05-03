#!/usr/bin/env python3
"""pcap-decode.py — Decrypt UDP and dump TCP from a Neocron 2 pcap.

Reads a pcap with scapy. For each UDP packet, applies the LFSR+CFB
cipher (identical math to tools/decrypt-retail.py) and prints the
decrypted plaintext. For each TCP segment, dumps the raw payload.

Use this to find packets that strace's recvmsg/sendmsg filter misses
— specifically TCP gameserver traffic, which uses recv/send (no
suffix) and is therefore absent from our existing strace logs.
"""

from __future__ import annotations
import argparse, struct, sys
from collections import Counter
from pathlib import Path

# Reuse the LFSR cipher math from decrypt-retail.py
sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")

try:
    from scapy.all import PcapReader, IP, UDP, TCP, Raw
except ImportError:
    sys.exit("scapy missing — install with: pip install --user --break-system-packages scapy")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", "-i", type=Path, required=True)
    ap.add_argument("--server-ip", default=None,
                    help="Filter to this peer IP (default: auto-detect "
                         "highest-traffic non-local peer)")
    ap.add_argument("--proto", choices=("udp", "tcp", "both"), default="both")
    ap.add_argument("--max-packets", type=int, default=0,
                    help="Stop after N packets (0 = no limit)")
    ap.add_argument("--show-bytes", type=int, default=80,
                    help="Hex bytes shown per packet")
    args = ap.parse_args()

    # First pass: discover the server peer if not specified
    if not args.server_ip:
        peer_count = Counter()
        with PcapReader(str(args.input)) as pr:
            for i, pkt in enumerate(pr):
                if i > 5000: break
                if IP not in pkt: continue
                src, dst = pkt[IP].src, pkt[IP].dst
                # The "peer" is whichever side isn't 127.0.0.1 / 192.168.* / 172.27.*
                for ip in (src, dst):
                    if not (ip.startswith("127.") or ip.startswith("192.168.")
                            or ip.startswith("172.")):
                        peer_count[ip] += 1
        if peer_count:
            args.server_ip = peer_count.most_common(1)[0][0]
            print(f"[auto] server IP = {args.server_ip} "
                  f"({peer_count[args.server_ip]} packets in first 5000)")
        else:
            sys.exit("no non-local peer found; pass --server-ip")

    counts = Counter()
    n = 0
    with PcapReader(str(args.input)) as pr:
        for pkt in pr:
            if args.max_packets and n >= args.max_packets: break
            if IP not in pkt: continue
            src, dst = pkt[IP].src, pkt[IP].dst
            if args.server_ip not in (src, dst): continue

            ts = float(pkt.time)
            # Direction relative to client
            direction = "S→C" if src == args.server_ip else "C→S"

            if UDP in pkt and args.proto in ("udp", "both"):
                payload = bytes(pkt[UDP].payload)
                if not payload: continue
                counts["udp_" + direction] += 1
                result = decrypt_mod.decrypt_wire_packet(payload)
                if result:
                    plain, dlen = result
                    head = plain[:args.show_bytes].hex()
                    print(f"{ts:.6f}  {direction}  UDP  wire={len(payload):4d}B "
                          f"plain={dlen:4d}B  byte0=0x{plain[0]:02x}  {head}")
                else:
                    print(f"{ts:.6f}  {direction}  UDP  wire={len(payload):4d}B "
                          f"DECRYPT-FAIL  raw: {payload[:args.show_bytes].hex()}")
                n += 1

            elif TCP in pkt and args.proto in ("tcp", "both"):
                payload = bytes(pkt[TCP].payload)
                if not payload: continue
                counts["tcp_" + direction] += 1
                head = payload[:args.show_bytes].hex()
                flags = pkt[TCP].flags
                print(f"{ts:.6f}  {direction}  TCP  len={len(payload):4d}B "
                      f"flags={flags}  {head}")
                n += 1

    print()
    print("=" * 60)
    print("Per-direction packet counts:")
    for k, v in sorted(counts.items()):
        print(f"  {k:8s}: {v}")


if __name__ == "__main__":
    main()
