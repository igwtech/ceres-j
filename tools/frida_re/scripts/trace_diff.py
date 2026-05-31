#!/usr/bin/env python3
"""trace_diff.py — diff two harness trace.jsonl files (decrypted wire)
at the opcode / sub-tag level, to surface protocol gaps between retail
and Ceres-J for the SAME scripted pass.

The orchestrator trace logs every UDP packet already decrypted
(`udp_recv` = S->C, `udp_send` = C->S) with `plain_hex` + `direction`.
We classify each 0x13 sub-packet (or transport packet) by opcode/sub-tag
and compare per-direction histograms. ONE-SIDED opcodes (present on one
server, absent on the other) are the high-signal protocol gaps —
robust to zone/NPC noise because they're about *presence*, not volume.

  trace_diff.py <retail_trace.jsonl> <ceres_trace.jsonl> [--top N]
"""
from __future__ import annotations
import argparse
import json
from collections import Counter


def opcode_label(sub: bytes) -> str:
    if len(sub) < 1:
        return "<empty>"
    if sub[0] == 0x03 and len(sub) >= 4:
        op = sub[3]
        if op == 0x1f and len(sub) >= 7:
            return f"0x03/0x1f/0x{sub[6]:02x}"
        if op == 0x22 and len(sub) >= 5:
            return f"0x03/0x22/0x{sub[4]:02x}"
        if op == 0x25 and len(sub) >= 5:
            return f"0x03/0x25/0x{sub[4]:02x}"
        return f"0x03/0x{op:02x}"
    if sub[0] == 0x02 and len(sub) >= 4:
        return f"0x02-rewrap/0x{sub[3]:02x}"
    return f"raw 0x{sub[0]:02x}"


def walk_subpackets(plain: bytes):
    """Return (sub-packets list, transport_byte). For a 0x13 datagram,
    transport is None and subs are the inner sub-packets. For a non-0x13
    (transport-layer) datagram, subs is empty and transport = plain[0]."""
    if not plain:
        return [], None
    if plain[0] != 0x13:
        return [], plain[0]
    subs = []
    i, n = 5, len(plain)            # past [0x13][ctr LE2][ctr+sk LE2]
    while i + 2 <= n:
        sub_len = plain[i] | (plain[i + 1] << 8)
        i += 2
        if sub_len <= 0 or i + sub_len > n:
            break
        subs.append(plain[i:i + sub_len])
        i += sub_len
    return subs, None


def inventory(path: str):
    hist = Counter()        # (label, dir) -> count
    ascii_strings = Counter()
    total = 0
    for line in open(path, encoding="utf-8", errors="ignore"):
        if '"udp_recv"' not in line and '"udp_send"' not in line:
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
        total += 1
        d = e.get("direction") or ("s2c" if e.get("ev") == "udp_recv" else "c2s")
        subs, transport = walk_subpackets(plain)
        if transport is not None:
            hist[(f"transport 0x{transport:02x}", d)] += 1
            continue
        for sub in subs:
            hist[(opcode_label(sub), d)] += 1
            # scrape embedded ASCII names (>=4 printable)
            run = bytearray()
            for byte in sub:
                if 0x20 <= byte < 0x7f:
                    run.append(byte)
                else:
                    if len(run) >= 4:
                        ascii_strings[run.decode("latin1")] += 1
                    run = bytearray()
            if len(run) >= 4:
                ascii_strings[run.decode("latin1")] += 1
    return {"hist": hist, "ascii": ascii_strings, "total": total}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("retail")
    ap.add_argument("ceres")
    ap.add_argument("--top", type=int, default=80)
    args = ap.parse_args()

    a = inventory(args.retail)
    b = inventory(args.ceres)
    print(f"retail packets={a['total']}   ceres packets={b['total']}\n")

    keys = sorted(set(a["hist"]) | set(b["hist"]),
                  key=lambda k: -(a["hist"].get(k, 0) + b["hist"].get(k, 0)))
    print(f"{'opcode / dir':30}{'retail':>8}{'ceres':>8}")
    print("-" * 54)
    one_sided = []
    for k in keys[:args.top]:
        ra, rb = a["hist"].get(k, 0), b["hist"].get(k, 0)
        flag = ""
        if (ra == 0) != (rb == 0):
            flag = "  << ONE-SIDED"
            one_sided.append((k, ra, rb))
        print(f"{k[0] + ' ' + k[1]:30}{ra:>8}{rb:>8}{flag}")

    print("\n==== ONE-SIDED (protocol gaps) ====")
    for (k, ra, rb) in one_sided:
        who = "retail-only (Ceres MISSING)" if rb == 0 else "Ceres-only (retail never)"
        print(f"  {k[0] + ' ' + k[1]:30} retail={ra} ceres={rb}  {who}")

    # ASCII strings present on one side only (script/NPC names etc.)
    ra_only = set(a["ascii"]) - set(b["ascii"])
    rb_only = set(b["ascii"]) - set(a["ascii"])
    if ra_only or rb_only:
        print("\n==== ASCII strings one-sided (top 15 each) ====")
        print("  retail-only:", sorted(ra_only,
              key=lambda s: -a["ascii"][s])[:15])
        print("  ceres-only: ", sorted(rb_only,
              key=lambda s: -b["ascii"][s])[:15])


if __name__ == "__main__":
    main()
