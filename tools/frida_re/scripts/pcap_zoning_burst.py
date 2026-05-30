"""Extract Zoning1 C→S requests AND the S→C confirm burst that
follows each one. Byte-pins the server-side reply (start-ack,
commit, NpcData, mystery 0x23) per memory `plaza_sector_walk_cross.md`.

Usage: python pcap_zoning_burst.py <pcap> [--window-ms 800]

The script reuses the parser from pcap_extract_zoning.py.
"""
from __future__ import annotations

import sys
import argparse
from pathlib import Path
from collections import Counter, defaultdict

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import pcap_extract_zoning as base  # noqa: E402


# Sub-ops we expect inside the S→C burst (per memory).
INTERESTING_SUBOPS = {
    0x1f: 'Transaction',
    0x2d: 'NpcData/LstPlayer',
    0x23: 'mystery-23',
    0x28: 'WorldInfo',
    0x07: 'Multipart',
    0x1b: 'PositionUpdate',
    0x2c: 'CharInfo/StartPos',
}


def _decode_03_1f_subtag(sub: bytes) -> str:
    """Decode the 0x03/0x1f Transaction sub-type (offset 6) +
    inner tag (offset 7)."""
    if len(sub) < 8: return '?short'
    return f"0x{sub[6]:02x}/0x{sub[7]:02x}"


def main(argv=None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("pcap")
    p.add_argument("--window-ms", type=int, default=800,
                   help="capture S→C traffic within this many ms "
                        "after each Zoning1 C→S (default 800)")
    p.add_argument("--limit", type=int, default=3,
                   help="limit detail dump to N Zoning crossings")
    args = p.parse_args(argv)

    window_s = args.window_ms / 1000.0
    zonings = []        # (ts, sip, sp, dip, dp, sub)
    all_packets = []    # (ts, dir, sip, dip, sub_list)

    for ts, sip, sp, dip, dp, body in base.parse_pcap(args.pcap):
        plain = base.decrypt_one(body)
        if plain is None or len(plain) < 7 or plain[0] != 0x13:
            continue
        subs = list(base.walk_subpackets(plain))
        # Direction inferred from port (game server on 5005-5008 or 12000).
        is_c2s = dp in {5002, 5003, 5004, 5005, 5006, 5007, 5008, 12000}
        all_packets.append((ts, 'C→S' if is_c2s else 'S→C',
                            sip, dip, subs))
        # Identify Zoning1
        for sub in subs:
            if len(sub) >= 5 and sub[0] == 0x03 \
                    and sub[3] == 0x22 and sub[4] == 0x0d \
                    and is_c2s:
                zonings.append((ts, sip, sp, dip, dp, sub))

    print(f"=== {len(zonings)} Zoning1 C→S events ===\n")

    for i, (ts, sip, sp, dip, dp, sub) in enumerate(zonings):
        # Decode core fields per the layout I pinned earlier.
        body = sub[6:]   # after envelope + sub-tag
        to_sec   = sub[6]
        from_sec = int.from_bytes(sub[18:22], 'little') \
            if len(sub) >= 22 else None
        door     = int.from_bytes(sub[14:16], 'little') \
            if len(sub) >= 16 else None
        flags    = int.from_bytes(sub[12:14], 'little') \
            if len(sub) >= 14 else None
        print(f"  [{i+1}] T={ts:.3f}  to_sec={to_sec} "
              f"from_sec={from_sec} flags=0x{flags:04x} "
              f"door=0x{door:04x}")
        if i >= args.limit:
            continue
        print(f"      Zoning1 body: {sub.hex(' ')}")

        # S→C reply window
        deadline = ts + window_s
        burst = [p for p in all_packets
                 if p[0] >= ts and p[0] <= deadline
                 and p[1] == 'S→C' and p[2] == dip]  # server → client
        if not burst:
            print(f"      (no S→C reply within {args.window_ms}ms)\n")
            continue

        # Walk each S→C packet's sub-packets, count opcodes.
        op_counter = Counter()
        first_packet_subs = []
        for (pts, _d, _sip, _dip, subs) in burst:
            for s in subs:
                if len(s) >= 4 and s[0] == 0x03:
                    op_counter[s[3]] += 1
                    if not first_packet_subs:
                        first_packet_subs.append((pts - ts, s))

        print(f"      S→C burst (T+0..+{args.window_ms}ms): "
              f"{sum(op_counter.values())} sub-packets")
        for op, n in sorted(op_counter.items()):
            label = INTERESTING_SUBOPS.get(op, '?')
            print(f"        0x03/0x{op:02x} ({label:<20}) × {n}")

        # First few 0x03/0x1f Transaction sub-types
        ifs = []
        for (pts, _d, _sip, _dip, subs) in burst:
            for s in subs:
                if len(s) >= 8 and s[0] == 0x03 and s[3] == 0x1f:
                    ifs.append((pts - ts, _decode_03_1f_subtag(s),
                                s.hex(' ')))
        if ifs:
            print(f"      First 0x03/0x1f Transactions:")
            for dt, tag, hexs in ifs[:5]:
                print(f"        T+{dt*1000:6.1f}ms  {tag}  {hexs[:90]}")

        # 0x03/0x23 mystery samples
        i23 = []
        for (pts, _d, _sip, _dip, subs) in burst:
            for s in subs:
                if len(s) >= 4 and s[0] == 0x03 and s[3] == 0x23:
                    i23.append((pts - ts, s.hex(' ')))
        if i23:
            print(f"      0x03/0x23 samples:")
            for dt, hexs in i23[:3]:
                print(f"        T+{dt*1000:6.1f}ms  {hexs[:90]}")
        print()

    return 0


if __name__ == '__main__':
    sys.exit(main())
