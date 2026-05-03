#!/usr/bin/env python3
"""mine_zone_edges.py — extract empirical zone-boundary crossing coords
from a retail capture by correlating the last C->S Movement (0x20) packet
before each TCP `0x83 0x0c` Location packet.

For each transition, prints:
  - source zone (last known zone before the Location packet fired)
  - destination zone_id + bsp_path (from the Location packet body)
  - last Y/Z/X coords from the C->S 0x20 burst leading up to the transition

This gives empirical "zone edge crossing" coordinates without needing
any geometry parsing from BSP files.
"""
from __future__ import annotations
import sys, struct
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")
burst_mod = import_module("parse-burst")

from scapy.all import PcapReader, IP, UDP, TCP


def main():
    if len(sys.argv) < 2:
        sys.exit(f"usage: {sys.argv[0]} <pcap>")
    pcap = Path(sys.argv[1])

    # Pull (rel_ts, type, payload_or_coords) for both TCP 0x830c and C->S 0x20.
    capture_start = None
    events = []  # tuple-of-mixed: ('move', rel, x, y, z) or ('loc', rel, zone_id, path)

    # State to decode partial movement bytes
    last_x = last_y = last_z = None

    with PcapReader(str(pcap)) as pr:
        for pkt in pr:
            if IP not in pkt: continue
            ts = float(pkt.time)
            if capture_start is None: capture_start = ts
            rel = ts - capture_start

            if UDP in pkt:
                # We only care about C->S 0x20 movement broadcasts
                if pkt[IP].src.startswith("157.90"): continue  # skip server side
                payload = bytes(pkt[UDP].payload)
                r = decrypt_mod.decrypt_wire_packet(payload)
                if not r: continue
                parsed = burst_mod.parse_gamedata(r[0])
                if not parsed: continue
                for sub in parsed["subs"]:
                    if sub["outer"] != 0x20: continue
                    # parse-burst strips outer for 0x03 (inner_data) but for
                    # raw outers like 0x20 it keeps the full sub in `data`.
                    # Movement.java does skip(3); read() to land at the type byte.
                    inner = sub.get("data", b"")
                    if len(inner) < 4: continue
                    pos = 3
                    type_byte = inner[pos]; pos += 1
                    y = z = x = None
                    if (type_byte & 0x01) and pos + 2 <= len(inner):
                        y = (inner[pos] | (inner[pos+1] << 8)) - 32000; pos += 2
                    if (type_byte & 0x02) and pos + 2 <= len(inner):
                        z = (inner[pos] | (inner[pos+1] << 8)) - 32000; pos += 2
                    if (type_byte & 0x04) and pos + 2 <= len(inner):
                        x = (inner[pos] | (inner[pos+1] << 8)) - 32000; pos += 2
                    # Update last known coords (preserve what wasn't sent)
                    if y is not None: last_y = y
                    if z is not None: last_z = z
                    if x is not None: last_x = x
                    if last_x is not None and last_y is not None and last_z is not None:
                        events.append(("move", rel, last_x, last_y, last_z))

            elif TCP in pkt:
                # Server-to-client TCP, look for 0x83 0x0c Location packet
                if not pkt[IP].src.startswith("157.90"): continue
                payload = bytes(pkt[TCP].payload)
                if not payload: continue
                p = 0
                while p + 3 <= len(payload):
                    if payload[p] != 0xfe: break
                    sub_len = payload[p+1] | (payload[p+2] << 8); p += 3
                    if p + sub_len > len(payload): break
                    sub = payload[p:p+sub_len]; p += sub_len
                    if len(sub) >= 2 and sub[0] == 0x83 and sub[1] == 0x0c:
                        zone_id = struct.unpack_from("<I", sub, 2)[0]
                        # bsp path is null-terminated ASCII at offset 14
                        bsp = sub[14:].split(b"\x00", 1)[0].decode("latin-1", errors="replace")
                        events.append(("loc", rel, zone_id, bsp))

    # Walk events, for each Location find the most recent move just before
    print(f"Zone transitions inferred from {len(events)} events:\n")
    print(f"{'rel_ts':>8}  {'src_zone':>8}  {'dst_zone':>8}  {'bsp_path':<22}  "
          f"{'last_x':>7}  {'last_y':>7}  {'last_z':>7}")
    print("-" * 95)
    last_loc = None
    for i, e in enumerate(events):
        if e[0] == "loc":
            _, rel, zone_id, bsp = e
            # Find the most recent move event before this
            last_move = None
            for j in range(i - 1, -1, -1):
                if events[j][0] == "move":
                    last_move = events[j]
                    break
            x = last_move[2] if last_move else None
            y = last_move[3] if last_move else None
            z = last_move[4] if last_move else None
            src_zone = last_loc if last_loc is not None else 0
            print(f"{rel:>8.2f}  {src_zone:>8}  {zone_id:>8}  {bsp:<22}  "
                  f"{x if x is not None else '?':>7}  "
                  f"{y if y is not None else '?':>7}  "
                  f"{z if z is not None else '?':>7}")
            last_loc = zone_id


if __name__ == "__main__":
    main()
