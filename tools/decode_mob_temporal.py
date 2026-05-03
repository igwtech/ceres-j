#!/usr/bin/env python3
"""decode_mob_temporal.py — track individual mobs across the
0x03/0x2d 54B stream and verify position-float / state-byte
behavior over time.

If bytes 8-11 are LE32 floats representing position, they should
change SMOOTHLY between consecutive packets for the same mob
(the mob walking around). If bytes 8-11 are packed encoding,
they'll change in non-monotonic ways.
"""
from __future__ import annotations
import sys, struct, math
from pathlib import Path
from collections import defaultdict

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")
burst_mod = import_module("parse-burst")
catalog = import_module("catalog_extract")

try:
    from scapy.all import PcapReader, IP, UDP
except ImportError:
    sys.exit("scapy missing")


def main():
    pcap = Path("strace/nc2_strace_RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715.pcap")
    server_ip = catalog.find_server_ip(pcap)

    # Track each mob's packet timeline
    by_mob = defaultdict(list)  # entity_id → [(rel_ts, body)]
    capture_start = None

    print(f"Walking {pcap.name}...", file=sys.stderr)
    with PcapReader(str(pcap)) as pr:
        for pkt in pr:
            if IP not in pkt or UDP not in pkt: continue
            if pkt[IP].src != server_ip: continue
            ts = float(pkt.time)
            if capture_start is None: capture_start = ts
            rel = ts - capture_start
            payload = bytes(pkt[UDP].payload)
            r = decrypt_mod.decrypt_wire_packet(payload)
            if not r: continue
            plain = r[0]
            parsed = burst_mod.parse_gamedata(plain)
            if not parsed: continue
            for sub in parsed["subs"]:
                if sub.get("reliable_type") != 0x2d: continue
                inner = sub.get("inner_data", b"")
                if len(inner) != 54: continue
                eid = int.from_bytes(inner[0:4], "little")
                by_mob[eid].append((rel, inner))

    # Pick the mob with most observations and trace its bytes over time
    sorted_mobs = sorted(by_mob.items(), key=lambda kv: -len(kv[1]))
    print(f"\nTop 5 mobs by packet count:", file=sys.stderr)
    for eid, packets in sorted_mobs[:5]:
        print(f"  mob 0x{eid:08x}: {len(packets)} packets, "
              f"timespan {packets[-1][0] - packets[0][0]:.1f}s",
              file=sys.stderr)

    # Pick the most-observed mob
    mob_id, packets = sorted_mobs[0]
    print(f"\nAnalyzing mob 0x{mob_id:08x} ({len(packets)} packets)",
          file=sys.stderr)

    # For each byte offset, check temporal smoothness
    # A "smooth" byte is one where consecutive samples differ by small amounts
    # (suggests the byte is a coordinate or animation tick).
    # A "stepped" byte is one with discrete jumps (state code, animation frame).
    out = []
    out.append(f"# Mob 0x{mob_id:08x} byte-level temporal trace\n\n")
    out.append(f"Capture: {pcap.name}\n")
    out.append(f"Packets: {len(packets)} over "
               f"{packets[-1][0] - packets[0][0]:.1f}s\n\n")

    # Show a 20-packet timeline
    out.append("## Sample timeline (first 20 packets, all 54 bytes)\n\n")
    out.append("```\n")
    out.append(f"{'#':>4} {'rel_ts':>8}  bytes 0-13                                      bytes 14-26                                bytes 27-53\n")
    out.append("-" * 140 + "\n")
    for i, (rel, body) in enumerate(packets[:20]):
        h1 = body[0:14].hex(' ')
        h2 = body[14:27].hex(' ')
        h3 = body[27:54].hex(' ')
        out.append(f"{i:>4} {rel:>7.2f}s  {h1}  {h2}  {h3}\n")
    out.append("```\n\n")

    # For each byte offset, decode as LE32 float and show progression
    out.append("## Byte 8-11 as LE32 float (pos_x hypothesis)\n\n")
    out.append("```\n")
    out.append(f"{'#':>4} {'rel_ts':>8}  bytes      float\n")
    out.append("-" * 50 + "\n")
    for i, (rel, body) in enumerate(packets[:30]):
        bs = body[8:12]
        try:
            f = struct.unpack("<f", bs)[0]
            f_str = f"{f:.4f}" if math.isfinite(f) and abs(f) < 1e10 else "garbage"
        except Exception:
            f_str = "err"
        out.append(f"{i:>4} {rel:>7.2f}s  {bs.hex()}  {f_str}\n")
    out.append("```\n\n")

    # Same for bytes 12-15, 16-19, 20-23
    for off in [12, 16, 20]:
        out.append(f"## Bytes {off}-{off+3} as LE32 float\n\n")
        out.append("```\n")
        for i, (rel, body) in enumerate(packets[:15]):
            bs = body[off:off+4]
            try:
                f = struct.unpack("<f", bs)[0]
                f_str = f"{f:.4f}" if math.isfinite(f) and abs(f) < 1e10 else "garbage"
            except Exception:
                f_str = "err"
            out.append(f"{i:>4} {rel:>7.2f}s  {bs.hex()}  {f_str}\n")
        out.append("```\n\n")

    # Try LE16 - 32000 hypothesis at various offsets
    out.append("## Position hypothesis: LE16 - 32000 at bytes 5-12\n\n")
    out.append("```\n")
    out.append(f"{'#':>4} {'rel_ts':>8}  bytes 5-6 -> Y       bytes 7-8 -> Z       bytes 9-10 -> X      bytes 11-12 -> ?\n")
    out.append("-" * 110 + "\n")
    for i, (rel, body) in enumerate(packets[:20]):
        a = int.from_bytes(body[5:7], "little") - 32000
        bb = int.from_bytes(body[7:9], "little") - 32000
        cc = int.from_bytes(body[9:11], "little") - 32000
        dd = int.from_bytes(body[11:13], "little") - 32000
        out.append(f"{i:>4} {rel:>7.2f}s  {a:>10d}           {bb:>10d}           {cc:>10d}           {dd:>10d}\n")
    out.append("```\n\n")

    # State byte 4 over time
    out.append("## State byte 4 over time\n\n")
    out.append("```\n")
    states_seen = set()
    state_changes = []
    last_state = None
    for i, (rel, body) in enumerate(packets):
        s = body[4]
        states_seen.add(s)
        if s != last_state:
            state_changes.append((i, rel, s))
            last_state = s
    out.append(f"States seen: {sorted(states_seen)}\n")
    out.append(f"Total state changes: {len(state_changes)}\n\n")
    for i, rel, s in state_changes[:30]:
        out.append(f"  packet #{i:>4} @ t={rel:.2f}s: byte4 = 0x{s:02x}\n")
    out.append("```\n\n")

    # Check for monotonic counter / sequence (could be heartbeat seq)
    out.append("## Monotonic / sequenced bytes\n\n")
    out.append("Looking for bytes that monotonically increase over time "
               "(would be a sequence counter or animation tick):\n\n")
    out.append("| Offset | Min | Max | Distinct | Monotonic-ness |\n|---|---:|---:|---:|---|\n")
    for offset in range(40, 54):
        vals = [body[offset] for _, body in packets]
        distinct = len(set(vals))
        # Check if mostly increasing
        increases = sum(1 for i in range(1, len(vals)) if vals[i] > vals[i-1])
        decreases = sum(1 for i in range(1, len(vals)) if vals[i] < vals[i-1])
        if increases + decreases > 0:
            mono_pct = increases / (increases + decreases) * 100
        else:
            mono_pct = 0
        out.append(f"| {offset} | {min(vals)} | {max(vals)} | {distinct} | {mono_pct:.0f}% increasing |\n")
    out.append("\n")

    out_path = Path("docs/protocol/_data/decode_mob_temporal.md")
    out_path.write_text("".join(out))
    print(f"wrote {out_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
