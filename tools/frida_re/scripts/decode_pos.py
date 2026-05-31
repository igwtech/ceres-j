#!/usr/bin/env python3
"""decode_pos.py — extract the player's world position from the Movement
(raw 0x20) packets in a harness trace.jsonl.

Movement sub-packet layout (29B): [0x20][00 00 7f][x:f32][z:f32][y:f32]
[vel:f32][heading:f32][pad]. The z float is the height (matches the DB
z_coordinate, e.g. -256). Prints the FIRST and LAST positions seen
(first ~ spawn = where the char logged out; useful to replicate in the
Ceres DB).

  decode_pos.py <trace.jsonl> [--n 5]
"""
from __future__ import annotations
import argparse
import json
import struct


def f32(b, off):
    return struct.unpack_from("<f", b, off)[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("trace")
    ap.add_argument("--n", type=int, default=5)
    args = ap.parse_args()

    positions = []
    for line in open(args.trace, encoding="utf-8", errors="ignore"):
        if '"udp_send"' not in line:
            continue
        try:
            e = json.loads(line)
        except Exception:
            continue
        ph = e.get("plain_hex")
        if not ph:
            continue
        try:
            plain = bytes.fromhex(ph)
        except Exception:
            continue
        if not plain or plain[0] != 0x13:
            continue
        # walk subs
        i, n = 5, len(plain)
        while i + 2 <= n:
            sub_len = plain[i] | (plain[i + 1] << 8)
            i += 2
            if sub_len <= 0 or i + sub_len > n:
                break
            sub = plain[i:i + sub_len]
            i += sub_len
            # Movement: raw 0x20, >= 16B so we can read 3 floats at +4
            if sub and sub[0] == 0x20 and len(sub) >= 16:
                try:
                    x = f32(sub, 4)
                    z = f32(sub, 8)
                    y = f32(sub, 12)
                except Exception:
                    continue
                # plausibility: world coords are within +/- a few thousand
                if all(abs(v) < 1e6 for v in (x, y, z)):
                    positions.append((x, y, z))

    print(f"movement packets with position: {len(positions)}")
    if not positions:
        return
    def show(label, p):
        x, y, z = p
        print(f"  {label}: x={x:.2f} y={y:.2f} z={z:.2f}   "
              f"(DB ints: x={round(x)} y={round(y)} z={round(z)})")
    print("FIRST (spawn / logout position):")
    for p in positions[:args.n]:
        show("first", p)
    print("LAST:")
    for p in positions[-args.n:]:
        show("last", p)


if __name__ == "__main__":
    main()
