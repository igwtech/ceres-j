#!/usr/bin/env python3
"""compare_captures.py — side-by-side packet diff across two retail pcaps.

Goal: answer "what is the same vs different between scenario A and
scenario B?" without speculation. The catalog tells us *what* was
observed; this tool tells us *what changes when the scenario changes*,
which is how packet semantics get pinned down.

Two output modes:
  --mode=summary  (default) — table of packet-type frequencies in
                              each capture, plus delta column
  --mode=timeline — interleaved timeline of decoded packets from
                    both captures (useful when comparing two zone
                    walks at the per-event level)

Usage:
  compare_captures.py --left strace/A.pcap --right strace/B.pcap
  compare_captures.py --left A.pcap --right B.pcap --mode=timeline \\
                      --window 5

The "window" arg (timeline mode) clips both captures to the
[first_marker, first_marker+window] window so a 14-min capture and a
2-min capture remain comparable around their first event.
"""
from __future__ import annotations
import argparse, sys, datetime
from pathlib import Path
from collections import Counter

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")
burst_mod = import_module("parse-burst")

try:
    from scapy.all import PcapReader, IP, UDP, TCP
except ImportError:
    sys.exit("scapy missing — pip install --user --break-system-packages scapy")


# Reuse logic from catalog_extract.py — re-implementing here would
# drift over time. Just import.
catalog = import_module("catalog_extract")


def parse_pcap(pcap: Path):
    """Yield (rel_ts, direction, type_key, inner_bytes) tuples."""
    server_ip = catalog.find_server_ip(pcap)
    if not server_ip:
        return
    capture_start = None

    with PcapReader(str(pcap)) as pr:
        for pkt in pr:
            if IP not in pkt: continue
            ts = float(pkt.time)
            if capture_start is None:
                capture_start = ts
            rel_ts = ts - capture_start

            if pkt[IP].src == server_ip: direction = "S->C"
            elif pkt[IP].dst == server_ip: direction = "C->S"
            else: continue

            if UDP in pkt:
                payload = bytes(pkt[UDP].payload)
                if not payload: continue
                r = decrypt_mod.decrypt_wire_packet(payload)
                if not r:
                    yield (rel_ts, direction,
                           catalog._key_str(direction, "UDP",
                                            payload[0], None),
                           payload)
                    continue
                plain = r[0]
                parsed = burst_mod.parse_gamedata(plain)
                if not parsed:
                    yield (rel_ts, direction,
                           catalog._key_str(direction, "UDP",
                                            plain[0] if plain else 0, None),
                           plain)
                    continue
                for sub in parsed["subs"]:
                    outer = sub["outer"]
                    rt = sub.get("reliable_type")
                    inner = (sub.get("inner_data") if rt is not None
                             else sub.get("data", b""))
                    yield (rel_ts, direction,
                           catalog._key_str(direction, "UDP", outer, rt),
                           inner)
            elif TCP in pkt:
                payload = bytes(pkt[TCP].payload)
                pos = 0
                while pos + 3 <= len(payload):
                    if payload[pos] != 0xfe: break
                    sub_len = payload[pos + 1] | (payload[pos + 2] << 8)
                    pos += 3
                    if pos + sub_len > len(payload): break
                    sub = payload[pos:pos + sub_len]
                    pos += sub_len
                    if len(sub) < 2: continue
                    opcode = (sub[0] << 8) | sub[1]
                    yield (rel_ts, direction,
                           catalog._key_str(direction, "TCP", opcode, None),
                           sub)


# ─── Summary mode ───────────────────────────────────────────────────────

def summary(left: Path, right: Path):
    L = Counter(); R = Counter()
    L_size = {}; R_size = {}
    for ts, dir, key, inner in parse_pcap(left):
        L[key] += 1
        L_size.setdefault(key, []).append(len(inner))
    for ts, dir, key, inner in parse_pcap(right):
        R[key] += 1
        R_size.setdefault(key, []).append(len(inner))

    keys = sorted(set(L) | set(R),
                  key=lambda k: -(max(L.get(k, 0), R.get(k, 0))))

    print(f"# Compare: {left.name} vs {right.name}\n")
    print(f"_Generated {datetime.date.today().isoformat()}_\n")
    print(f"Left:  `{left.name}` — {sum(L.values()):,} packets, "
          f"{len(L)} unique types")
    print(f"Right: `{right.name}` — {sum(R.values()):,} packets, "
          f"{len(R)} unique types")
    print()
    print("| Type | Left | Right | Δ | Left avg sz | Right avg sz | Notes |")
    print("|---|---:|---:|---:|---:|---:|---|")
    for k in keys:
        l = L.get(k, 0); r = R.get(k, 0)
        l_avg = (sum(L_size[k]) // l) if l else "—"
        r_avg = (sum(R_size[k]) // r) if r else "—"
        delta = r - l
        note = ""
        if l == 0 and r > 0: note = "**ONLY RIGHT**"
        elif r == 0 and l > 0: note = "**ONLY LEFT**"
        elif abs(delta) > max(l, r) * 0.5 and max(l, r) > 5:
            note = "_significant change_"
        print(f"| `{k}` | {l} | {r} | {delta:+d} | {l_avg} | {r_avg} | "
              f"{note} |")


# ─── Timeline mode ──────────────────────────────────────────────────────

def timeline(left: Path, right: Path, window_s: float):
    L = list(parse_pcap(left))
    R = list(parse_pcap(right))

    if window_s > 0:
        L = [r for r in L if r[0] <= window_s]
        R = [r for r in R if r[0] <= window_s]

    print(f"# Timeline: {left.name} vs {right.name} "
          f"(window {window_s}s)\n")
    print(f"_{len(L)} left events, {len(R)} right events_\n")
    print("| Side | Δt | Dir | Type | Inner sample |")
    print("|---|---:|---|---|---|")

    # Interleave by ts (annotate side)
    merged = [(t, "L", d, k, i) for (t, d, k, i) in L] + \
             [(t, "R", d, k, i) for (t, d, k, i) in R]
    merged.sort(key=lambda x: x[0])
    for t, side, dir, key, inner in merged:
        sample = inner[:24].hex()
        if len(inner) > 24: sample += "..."
        print(f"| {side} | {t:.2f} | {dir} | `{key}` | `{sample}` |")


# ─── Main ───────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--left", type=Path, required=True)
    ap.add_argument("--right", type=Path, required=True)
    ap.add_argument("--mode", choices=("summary", "timeline"),
                    default="summary")
    ap.add_argument("--window", type=float, default=0,
                    help="Timeline mode: clip both captures to "
                         "[0, window] seconds (0 = no clip)")
    args = ap.parse_args()

    if args.mode == "summary":
        summary(args.left, args.right)
    else:
        timeline(args.left, args.right, args.window)


if __name__ == "__main__":
    main()
