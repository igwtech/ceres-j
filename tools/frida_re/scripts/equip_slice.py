#!/usr/bin/env python3
"""equip_slice.py — window an equip_probe run's decrypted wire by its
per-action markers, so each DRAW/HOLSTER shows exactly which packets it
produced. Highlights opcodes that DON'T occur in the idle baseline (the
equip-specific traffic — the whole point).

  ./equip_slice.py ../runs/equip_retail_20260606_..   [--dir c2s|s2c|both]

Reads equip_marks.tsv (epoch markers, same clock as trace ts_ns) and
trace.jsonl (udp_send/udp_recv with decrypted plain_hex). Prints, per
marker window, the UDP packets with a light envelope tag.
"""
from __future__ import annotations
import argparse
import json
import os
import sys


def inner_of(plain: bytes) -> bytes:
    """Strip the 0x13 app wrapper: 13 [ctr] [ackseq2] [connid] [len LE16]
    [inner...]. The inner packet starts at offset 7."""
    if len(plain) >= 8 and plain[0] == 0x13:
        ln = plain[5] | (plain[6] << 8)
        return plain[7:7 + ln] if ln else plain[7:]
    return plain


def envelope_tag(plain: bytes) -> str:
    """Counter-independent classifier of the INNER packet (so the same
    logical opcode hashes identically across the incrementing wrapper
    counter). Reliable 0x03 subtype 0x1f exposes the act_tag, which is
    the equip/holster command id (c2s_03_1f_envelope canonical)."""
    inner = inner_of(plain)
    if not inner:
        return "?"
    op = inner[0]
    if op == 0x03 and len(inner) >= 4:
        sub = inner[3]
        if sub == 0x1f and len(inner) >= 7:
            return f"rel/1f act=0x{inner[6]:02x}"
        if sub in (0x25, 0x2c, 0x07, 0x08):
            return f"rel/0x{sub:02x}"
        return f"rel/0x{sub:02x}"
    return f"raw 0x{op:02x}"


def inner_hex(plain: bytes) -> str:
    return inner_of(plain).hex()


def load_marks(run_dir):
    path = os.path.join(run_dir, "equip_marks.tsv")
    marks = []
    for line in open(path):
        ts, _, label = line.partition("\t")
        marks.append((float(ts), label.strip()))
    return sorted(marks)


def load_udp(run_dir):
    evs = []
    for line in open(os.path.join(run_dir, "trace.jsonl")):
        try:
            o = json.loads(line)
        except Exception:
            continue
        if o.get("ev") in ("udp_send", "udp_recv"):
            evs.append(o)
    evs.sort(key=lambda o: o["ts_ns"])
    return evs


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("run_dir")
    ap.add_argument("--dir", choices=["c2s", "s2c", "both"], default="both")
    ap.add_argument("--max", type=int, default=40, help="max packets per window")
    a = ap.parse_args()

    marks = load_marks(a.run_dir)
    evs = load_udp(a.run_dir)
    if not marks:
        print("no equip_marks.tsv — was this an equip_probe run?", file=sys.stderr)
        return 1

    # window each packet into [mark_i, mark_{i+1})
    windows = []
    for i, (ts, label) in enumerate(marks):
        end = marks[i + 1][0] if i + 1 < len(marks) else float("inf")
        pk = [o for o in evs if ts <= o["ts_ns"] / 1e9 < end]
        windows.append((label, pk))

    def tag_of(o):
        if not o.get("decrypt_ok"):
            return "(decrypt FAIL)"
        return envelope_tag(bytes.fromhex(o.get("plain_hex") or ""))

    # tags seen during idle baseline = the heartbeat to subtract
    idle_ops = set()
    for label, pk in windows:
        if label.startswith("IDLE_BASELINE"):
            for o in pk:
                idle_ops.add((o["direction"], tag_of(o)))

    for label, pk in windows:
        if a.dir != "both":
            pk = [o for o in pk if o["direction"] == a.dir]
        # summarize per (dir, tag): count + a sample inner hex
        agg = {}
        for o in pk:
            key = ("C->S" if o["direction"] == "c2s" else "S->C", tag_of(o))
            ent = agg.setdefault(key, {"n": 0, "sample": inner_hex(bytes.fromhex(o.get("plain_hex") or ""))})
            ent["n"] += 1
        print(f"\n=== {label} ===  ({len(pk)} pkts)")
        # NEW (equip-specific) first, then heartbeat
        rows = sorted(agg.items(), key=lambda kv: ((kv[0][1] in [t for d, t in idle_ops if d == ({'C->S':'c2s','S->C':'s2c'}[kv[0][0]])]), -kv[1]["n"]))
        for (arrow, tag), ent in rows:
            d = "c2s" if arrow == "C->S" else "s2c"
            novel = "" if (d, tag) in idle_ops else "  *NEW"
            print(f"  {arrow}  {tag:<16} x{ent['n']:<3} {ent['sample'][:48]}{novel}")
    print("\n(* = inner opcode/act_tag not seen in idle baseline → equip-specific)")

    # ── NOVEL RELIABLE BODIES (seq-normalized) — catches variant bodies a
    # high-count tag would otherwise hide (e.g. the 0x25/0x22 equip confirm
    # buried under 30x 0x25/0x23 heartbeat). Drops the 0x03 seq bytes so the
    # same logical message collapses across windows.
    def relkey(o):
        i = inner_of(bytes.fromhex(o.get("plain_hex") or ""))
        if len(i) >= 4 and i[0] == 0x03:
            return (i[0:1] + i[3:]).hex()   # opcode + payload, seq dropped
        return None

    idle_bodies = {("c2s", relkey(o)) for _, pk in windows if _.startswith("IDLE_BASELINE")
                   for o in pk if o.get("decrypt_ok") and relkey(o)} | \
                  {("s2c", relkey(o)) for _, pk in windows if _.startswith("IDLE_BASELINE")
                   for o in pk if o.get("decrypt_ok") and relkey(o)}
    print("\n=== NOVEL reliable bodies vs idle (per action, seq-normalized) ===")
    for label, pk in windows:
        if label.startswith("IDLE") or label == "IN_WORLD":
            continue
        novel = {}
        for o in pk:
            if not o.get("decrypt_ok"):
                continue
            k = relkey(o)
            if k and (o["direction"], k) not in idle_bodies:
                novel[(o["direction"], k)] = novel.get((o["direction"], k), 0) + 1
        if novel:
            print(f"  {label}:")
            for (d, k), n in novel.items():
                print(f"     {'C->S' if d=='c2s' else 'S->C'} x{n}  {k}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
