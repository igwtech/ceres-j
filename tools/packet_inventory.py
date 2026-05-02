#!/usr/bin/env python3
"""packet_inventory.py — build a comprehensive inventory of every packet
type observed in a retail pcap, correlated with marker timestamps.

For each unique (direction, outer, reliable_type, [tcp_opcode]) tuple,
emits:
  - count
  - inner-data size range (min, mean, max)
  - top 3 marker labels by temporal proximity
  - first-seen / last-seen timestamps relative to capture start
  - a 32-byte sample of the inner data

Output: markdown table sorted by frequency, plus a per-marker timeline
showing which packet types fired close to each marker.

Usage:
  packet_inventory.py --pcap <path> [--markers <path>] [--out <md>]
"""
from __future__ import annotations
import argparse, sys, struct
from pathlib import Path
from collections import defaultdict, Counter

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")
burst_mod = import_module("parse-burst")

try:
    from scapy.all import PcapReader, IP, UDP, TCP
except ImportError:
    sys.exit("scapy missing — pip install --user --break-system-packages scapy")


def parse_markers(path: Path):
    """Parse marker file. Format per line: `HH:MM:SS.uuuuuu  LABEL`."""
    if not path or not path.exists(): return []
    marks = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line: continue
        parts = line.split(None, 1)
        if len(parts) < 2: continue
        ts_str, label = parts
        try:
            h, m, s = ts_str.split(":")
            secs = int(h) * 3600 + int(m) * 60 + float(s)
            marks.append((secs, label.strip()))
        except ValueError:
            continue
    return marks


def find_server_ip(pcap):
    peers = Counter()
    with PcapReader(str(pcap)) as pr:
        for i, pkt in enumerate(pr):
            if i > 5000: break
            if IP not in pkt: continue
            for ip in (pkt[IP].src, pkt[IP].dst):
                if not (ip.startswith("127.") or ip.startswith("192.168.")
                        or ip.startswith("172.") or ip.startswith("10.")):
                    peers[ip] += 1
    return peers.most_common(1)[0][0] if peers else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pcap", "-p", type=Path, required=True)
    ap.add_argument("--markers", "-m", type=Path)
    ap.add_argument("--out", "-o", type=Path,
                    default=Path("packet_inventory.md"))
    args = ap.parse_args()

    server_ip = find_server_ip(args.pcap)
    if not server_ip:
        sys.exit("no external peer found in pcap")

    markers_tod = parse_markers(args.markers) if args.markers else []
    # markers will be re-baselined to rel_ts after capture_start_tod is known
    markers = []

    # Bucket by (direction, outer, reliable_type, tcp_opcode)
    # entry: count, sizes[], first_ts, last_ts, sample_bytes, ts_history
    # direction: 'S->C' or 'C->S'
    # outer: numeric byte (UDP) or 'TCP'
    # reliable_type: numeric for 0x03 wrapped, else None
    # tcp_opcode: first 2 bytes of TCP payload after 0xfe framing, else None
    inv = defaultdict(lambda: {
        "count": 0, "sizes": [], "first": None, "last": None,
        "sample": b"", "ts_list": []
    })

    capture_start = None
    capture_start_tod = None  # time-of-day (seconds since midnight UTC)

    with PcapReader(str(args.pcap)) as pr:
        for pkt in pr:
            if IP not in pkt: continue
            ts = float(pkt.time)
            if capture_start is None:
                capture_start = ts
                # Compute time-of-day for first packet so markers (which are
                # logged as HH:MM:SS.uuuuuu) can be re-baselined against it.
                import datetime
                # Strace markers are logged in the host's LOCAL time, so
                # convert pcap epoch ts to local-time-of-day (no tz arg).
                dt = datetime.datetime.fromtimestamp(ts)
                capture_start_tod = (dt.hour * 3600 + dt.minute * 60
                                     + dt.second + dt.microsecond / 1e6)
            rel_ts = ts - capture_start

            # Direction
            if pkt[IP].src == server_ip:
                direction = "S->C"
            elif pkt[IP].dst == server_ip:
                direction = "C->S"
            else:
                continue

            if UDP in pkt:
                payload = bytes(pkt[UDP].payload)
                if direction == "S->C":
                    r = decrypt_mod.decrypt_wire_packet(payload)
                    if not r: continue
                    parsed = burst_mod.parse_gamedata(r[0])
                    if not parsed:
                        # Single-byte handshake / abort
                        if payload:
                            key = (direction, payload[0], None, None)
                            entry = inv[key]
                            entry["count"] += 1
                            entry["sizes"].append(len(payload))
                            if entry["first"] is None: entry["first"] = rel_ts
                            entry["last"] = rel_ts
                            if not entry["sample"]: entry["sample"] = payload[:32]
                            entry["ts_list"].append(rel_ts)
                        continue
                    for sub in parsed["subs"]:
                        outer = sub["outer"]
                        rt = sub.get("reliable_type")
                        inner = sub.get("inner_data", b"")
                        key = (direction, outer, rt, None)
                        entry = inv[key]
                        entry["count"] += 1
                        entry["sizes"].append(len(inner))
                        if entry["first"] is None: entry["first"] = rel_ts
                        entry["last"] = rel_ts
                        if not entry["sample"]: entry["sample"] = inner[:32]
                        entry["ts_list"].append(rel_ts)
                else:
                    # C->S — try same decrypt path (LFSR is symmetric)
                    r = decrypt_mod.decrypt_wire_packet(payload)
                    if not r:
                        # Likely single-byte handshake
                        if payload:
                            key = (direction, payload[0], None, None)
                            entry = inv[key]
                            entry["count"] += 1
                            entry["sizes"].append(len(payload))
                            if entry["first"] is None: entry["first"] = rel_ts
                            entry["last"] = rel_ts
                            if not entry["sample"]: entry["sample"] = payload[:32]
                            entry["ts_list"].append(rel_ts)
                        continue
                    parsed = burst_mod.parse_gamedata(r[0])
                    if not parsed: continue
                    for sub in parsed["subs"]:
                        outer = sub["outer"]
                        rt = sub.get("reliable_type")
                        inner = sub.get("inner_data", b"")
                        key = (direction, outer, rt, None)
                        entry = inv[key]
                        entry["count"] += 1
                        entry["sizes"].append(len(inner))
                        if entry["first"] is None: entry["first"] = rel_ts
                        entry["last"] = rel_ts
                        if not entry["sample"]: entry["sample"] = inner[:32]
                        entry["ts_list"].append(rel_ts)

            elif TCP in pkt:
                payload = bytes(pkt[TCP].payload)
                if not payload: continue
                # Walk 0xfe-framed sub-packets
                pos = 0
                while pos + 3 <= len(payload):
                    if payload[pos] != 0xfe: break
                    sub_len = payload[pos + 1] | (payload[pos + 2] << 8)
                    pos += 3
                    if pos + sub_len > len(payload): break
                    sub = payload[pos:pos + sub_len]
                    pos += sub_len
                    if len(sub) < 2:
                        opcode = sub[0] if sub else 0
                    else:
                        opcode = (sub[0] << 8) | sub[1]
                    key = (direction, "TCP", None, opcode)
                    entry = inv[key]
                    entry["count"] += 1
                    entry["sizes"].append(sub_len)
                    if entry["first"] is None: entry["first"] = rel_ts
                    entry["last"] = rel_ts
                    if not entry["sample"]: entry["sample"] = sub[:32]
                    entry["ts_list"].append(rel_ts)

    # Re-baseline marker time-of-day → relative seconds since capture start.
    # Handle midnight-wrap by allowing negative deltas to wrap to +24h.
    if capture_start_tod is not None and markers_tod:
        for mtod, label in markers_tod:
            rel = mtod - capture_start_tod
            if rel < -3600:  # wrapped past midnight
                rel += 24 * 3600
            markers.append((rel, label))

    # For each entry, find nearest marker labels
    def nearest_markers(ts_list, k=3, window=2.0):
        labels = Counter()
        for ts in ts_list:
            for mts, label in markers:
                if abs(mts - ts) <= window:
                    labels[label] += 1
        return labels.most_common(k)

    # Build markdown report
    out = []
    out.append("# Packet inventory\n\n")
    out.append(f"Pcap: `{args.pcap.name}`  \n")
    out.append(f"Server IP: `{server_ip}`  \n")
    out.append(f"Capture span: {(rel_ts):.1f}s  \n")
    out.append(f"Markers: {len(markers)}\n\n")

    # Sort by direction then count desc
    rows = []
    for key, e in inv.items():
        direction, outer, rt, opcode = key
        sizes = e["sizes"]
        size_str = (f"{min(sizes)}/{int(sum(sizes)/len(sizes))}/{max(sizes)}"
                    if sizes else "—")
        if outer == "TCP":
            type_str = f"TCP/0x{opcode:04x}"
        elif rt is not None:
            type_str = f"UDP/0x{outer:02x}/0x{rt:02x}"
        else:
            type_str = f"UDP/0x{outer:02x}"
        nearest = nearest_markers(e["ts_list"])
        nearest_str = ", ".join(f"{l}({n})" for l, n in nearest) or "—"
        rows.append((direction, type_str, e["count"], size_str,
                     e["first"], e["last"], nearest_str, e["sample"].hex()))

    rows.sort(key=lambda r: (r[0], -r[2]))

    out.append("## All packet types (sorted by direction, then frequency)\n\n")
    out.append("| Dir | Type | Count | size min/avg/max | first | last | nearest markers | sample |\n")
    out.append("|---|---|---:|---|---:|---:|---|---|\n")
    for direction, tp, cnt, sz, first, last, near, sample in rows:
        out.append(f"| {direction} | `{tp}` | {cnt} | {sz} | "
                   f"{first:.1f} | {last:.1f} | {near} | "
                   f"`{sample[:48]}{'...' if len(sample) > 48 else ''}` |\n")

    if markers:
        out.append("\n## Marker timeline\n\n")
        out.append("| Time | Label | Packets fired within ±2s |\n")
        out.append("|---:|---|---|\n")
        for mts, label in markers:
            # Find packets within ±2s of this marker
            nearby = Counter()
            for key, e in inv.items():
                for ts in e["ts_list"]:
                    if abs(mts - ts) <= 2.0:
                        direction, outer, rt, opcode = key
                        if outer == "TCP":
                            tp = f"TCP/0x{opcode:04x}"
                        elif rt is not None:
                            tp = f"{direction}|0x{outer:02x}/0x{rt:02x}"
                        else:
                            tp = f"{direction}|0x{outer:02x}"
                        nearby[tp] += 1
            top = ", ".join(f"`{tp}`×{n}" for tp, n in nearby.most_common(8))
            out.append(f"| {mts:.1f}s | {label} | {top} |\n")

    args.out.write_text("".join(out))
    print(f"Wrote {args.out} ({sum(len(s) for s in out):,} bytes)")
    print(f"  {len(rows)} unique packet types observed")
    print(f"  {sum(e['count'] for e in inv.values())} total packets")


if __name__ == "__main__":
    main()
