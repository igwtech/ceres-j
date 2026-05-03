#!/usr/bin/env python3
"""timeline-subs.py — print sub-packets in chronological order with timestamps.

Reads strace, decrypts both directions, but preserves wall-clock timestamps
per packet so you can correlate against the markers file.
"""
from __future__ import annotations
import argparse, re, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")
burst_mod = import_module("parse-burst")

# Match recvmsg/sendmsg with timestamp (HH:MM:SS.uuuuuu before the call)
_TS_RE = re.compile(
    r'^\s*\d+\s+(\d\d:\d\d:\d\d\.\d{6})\s+'
    r'(recvmsg|sendmsg)\(\d+<UDP[^>]*>.*?'
    r'iov_base="((?:[^"\\]|\\.)*)".*?= (\d+)\s*$',
    re.MULTILINE,
)

SKIP_TAGS = {"0x1b/unk", "0x20/Movement", "0x0b/CPing"}


def label(sub):
    o = sub["outer"]
    if o == 0x03:
        rt = sub.get("reliable_type", -1)
        name = burst_mod.RELIABLE_SUBTYPES.get(rt, f"unk")
        tag = f"0x03→0x{rt:02x}/{name}"
        if rt == 0x1f:
            d = sub.get("inner_data", b"")
            if len(d) >= 2:
                tag += f"+0x{d[1]:02x}"
        return tag
    name = burst_mod.OUTER_SUBTYPES.get(o, "unk")
    return f"0x{o:02x}/{name}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", "-i", type=Path, required=True)
    ap.add_argument("--markers", type=Path, default=None)
    ap.add_argument("--show-noise", action="store_true",
                    help="Include 0x1b/0x20/0x0b in timeline")
    args = ap.parse_args()

    content = args.input.read_text(errors="replace")
    events = []  # (ts, dir, plain_bytes)
    for m in _TS_RE.finditer(content):
        ts = m.group(1)
        call = m.group(2)
        wire = decrypt_mod.decode_strace_bytes(m.group(3))
        if not wire:
            continue
        d = "S→C" if call == "recvmsg" else "C→S"
        r = decrypt_mod.decrypt_wire_packet(wire)
        if not r:
            continue
        plain = r[0]
        events.append((ts, d, plain))

    markers = []
    if args.markers and args.markers.exists():
        for line in args.markers.read_text().splitlines():
            parts = line.strip().split(None, 1)
            if len(parts) == 2:
                markers.append(parts)

    print(f"loaded {len(events)} packets, {len(markers)} markers")
    print()

    # Interleave events + markers in timestamp order
    combined = [(t, "MARK", lbl) for (t, lbl) in markers]
    for ts, d, plain in events:
        parsed = burst_mod.parse_gamedata(plain)
        if not parsed:
            combined.append((ts, d, f"NOT-GAMEDATA byte0=0x{plain[0]:02x}"))
            continue
        for sub in parsed["subs"]:
            tag = label(sub)
            if not args.show_noise and tag in SKIP_TAGS:
                continue
            extra = f" len={sub['len']:3d}"
            hexb = sub["data"].hex()
            if len(hexb) > 80:
                hexb = hexb[:80] + "…"
            combined.append((ts, d, f"{tag:30s}{extra}  {hexb}"))

    combined.sort(key=lambda x: x[0])
    for ts, d, body in combined:
        if d == "MARK":
            print(f"{ts}  ──── MARK: {body} ────")
        else:
            print(f"{ts}  {d}  {body}")


if __name__ == "__main__":
    main()
