#!/usr/bin/env python3
"""corpus_gap.py — corpus-wide protocol gap report: retail vs Ceres-J.

Where trace_diff.py compares ONE retail trace with ONE Ceres trace, this
aggregates EVERY harness trace under a runs directory, classifying each run
by the server it logged into (tcp_connect ip: retail 157.90.195.74 /
162.55.138.142, Ceres 172.18.0.3), and reports per direction:

  * retail-only labels   (Ceres never emits / never receives them),
  * Ceres-only labels    (retail never does — invented or wrong envelope),
  * size-variant mismatches for labels both sides use.

Extra Ceres traces (e.g. nc2-bot captures of the CURRENT build, written by
bot_capture.py in the same trace.jsonl format) can be added with
--ceres-dir; then --current-only restricts the Ceres side to those.

  corpus_gap.py [--runs runs] [--ceres-dir DIR ...] [--current-only]
"""
from __future__ import annotations

import argparse
import json
import os
from collections import Counter, defaultdict

RETAIL_IPS = {"157.90.195.74", "162.55.138.142"}
CERES_IPS = {"172.18.0.3"}


def label(sub: bytes) -> str:
    b0 = sub[0]
    if b0 == 0x03 and len(sub) >= 4:
        op = sub[3]
        if op == 0x1f and len(sub) >= 7:
            return f"0x03/0x1f/0x{sub[6]:02x}"
        if op in (0x22, 0x25) and len(sub) >= 5:
            return f"0x03/0x{op:02x}/0x{sub[4]:02x}"
        return f"0x03/0x{op:02x}"
    if b0 == 0x02 and len(sub) >= 4:
        return f"0x02/0x{sub[3]:02x}"
    if b0 == 0x1f and len(sub) >= 4:
        return f"raw 0x1f/0x{sub[3]:02x}"
    return f"raw 0x{b0:02x}"


def walk(plain: bytes):
    """Sub-packets of a 0x13 datagram, or None for a transport datagram."""
    if plain[0] != 0x13:
        return None
    subs, i, n = [], 5, len(plain)
    while i + 2 <= n:
        ln = plain[i] | (plain[i + 1] << 8)
        i += 2
        if ln <= 0 or i + ln > n:
            break
        subs.append(plain[i:i + ln])
        i += ln
    return subs


def server_of(path: str):
    ips = set()
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            if '"tcp_connect"' in line:
                try:
                    ips.add(json.loads(line)["extras"]["ip"])
                except Exception:
                    pass
    if ips & RETAIL_IPS and not ips & CERES_IPS:
        return "retail"
    if ips & CERES_IPS and not ips & RETAIL_IPS:
        return "ceres"
    return None


def inventory(paths):
    hist, sizes, sample = Counter(), defaultdict(Counter), {}
    for p in paths:
        with open(p, encoding="utf-8", errors="ignore") as f:
            for line in f:
                if '"udp_recv"' not in line and '"udp_send"' not in line:
                    continue
                try:
                    e = json.loads(line)
                    plain = bytes.fromhex(e["plain_hex"])
                except Exception:
                    continue
                if not plain:
                    continue
                d = e.get("direction") or (
                    "s2c" if e.get("ev") == "udp_recv" else "c2s")
                subs = walk(plain)
                items = ([(f"transport 0x{plain[0]:02x}", plain)]
                         if subs is None else [(label(s), s) for s in subs])
                for lab, b in items:
                    k = (lab, d)
                    hist[k] += 1
                    sizes[k][len(b)] += 1
                    sample.setdefault(k, b.hex())
    return hist, sizes, sample


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", default="runs")
    ap.add_argument("--ceres-dir", action="append", default=[],
                    help="extra dir of <run>/trace.jsonl Ceres captures")
    ap.add_argument("--current-only", action="store_true",
                    help="Ceres side = --ceres-dir traces only")
    args = ap.parse_args()

    retail, ceres = [], []
    for run in sorted(os.listdir(args.runs)):
        p = os.path.join(args.runs, run, "trace.jsonl")
        if not os.path.isfile(p):
            continue
        who = server_of(p)
        if who == "retail":
            retail.append(p)
        elif who == "ceres" and not args.current_only:
            ceres.append(p)
    for d in args.ceres_dir:
        for run in sorted(os.listdir(d)):
            p = os.path.join(d, run, "trace.jsonl")
            if os.path.isfile(p):
                ceres.append(p)

    R, RS, RX = inventory(retail)
    C, CS, CX = inventory(ceres)
    fmt = lambda s, k: ",".join(f"{a}B×{b}" for a, b in s[k].most_common(5))
    print(f"retail traces: {len(retail)}   ceres traces: {len(ceres)}")
    for d in ("s2c", "c2s"):
        print(f"\n===== {d.upper()} retail-only (Ceres never) =====")
        for k in sorted((k for k in R if k[1] == d and not C.get(k)),
                        key=lambda k: -R[k]):
            print(f"  {k[0]:22} retail={R[k]:6}  [{fmt(RS, k)}]  e.g. {RX[k][:60]}")
        print(f"\n===== {d.upper()} Ceres-only (retail never) =====")
        for k in sorted((k for k in C if k[1] == d and not R.get(k)),
                        key=lambda k: -C[k]):
            print(f"  {k[0]:22} ceres={C[k]:6}  [{fmt(CS, k)}]  e.g. {CX[k][:60]}")
        print(f"\n===== {d.upper()} both — size-variant mismatch =====")
        for k in sorted((k for k in R if k[1] == d and C.get(k)),
                        key=lambda k: -R[k]):
            if set(RS[k]) != set(CS[k]):
                print(f"  {k[0]:22} retail={R[k]:6} ceres={C[k]:6}"
                      f"  R[{fmt(RS, k)}]  C[{fmt(CS, k)}]")


if __name__ == "__main__":
    main()
