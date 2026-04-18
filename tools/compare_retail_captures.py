#!/usr/bin/env python3
"""Compare sub-packet histograms across all 4 retail captures + C→S/S→C."""
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path("tools")))
import importlib.util
spec = importlib.util.spec_from_file_location("pb", "tools/parse-burst.py")
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

CAPTURES = [
    ("ACC1_CHAR1", "strace/nc2_strace_RETAIL_ACC1_CHAR1.log"),
    ("ACC1_CHAR2", "strace/nc2_strace_RETAIL_ACC1_CHAR2.log"),
    ("ACC2_CHAR1", "strace/nc2_strace_RETAIL_ACC2_CHAR1.log"),
    ("ACC2_CHAR2", "strace/nc2_strace_RETAIL_ACC2_CHAR2.log"),
]

def hist_for(path, direction):
    wire = pb.extract_udp(Path(path), direction)
    types = Counter()
    outer = Counter()
    decrypted_ok = 0
    for w in wire:
        p = pb.decrypt_wire(w)
        if not p: continue
        decrypted_ok += 1
        outer[p[0]] += 1
        if p[0] != 0x13:
            continue
        parsed = pb.parse_gamedata(p)
        if not parsed: continue
        for s in parsed["subs"]:
            if s["outer"] == 0x03:
                rt = s.get("reliable_type", 0)
                name = pb.RELIABLE_SUBTYPES.get(rt, f"0x{rt:02x}?")
                types[f"0x03→0x{rt:02x} {name}"] += 1
            else:
                ot = s["outer"]
                name = pb.OUTER_SUBTYPES.get(ot, f"0x{ot:02x}?")
                types[f"0x{ot:02x} {name} raw"] += 1
    return len(wire), decrypted_ok, outer, types

def fmt_outer(c):
    if not c: return "(none)"
    return ", ".join(f"0x{k:02x}×{v}" for k, v in sorted(c.items()))

all_keys = set()
s2c_hists = {}
c2s_hists = {}
for name, path in CAPTURES:
    n_wire_s, n_ok_s, outer_s, types_s = hist_for(path, "recv")  # S→C
    n_wire_c, n_ok_c, outer_c, types_c = hist_for(path, "send")  # C→S
    s2c_hists[name] = (n_wire_s, n_ok_s, outer_s, types_s)
    c2s_hists[name] = (n_wire_c, n_ok_c, outer_c, types_c)
    all_keys |= set(types_s.keys()) | set(types_c.keys())

print("=" * 100)
print("OUTER PACKET HEADER COUNTS (all captures)")
print("=" * 100)
print(f"{'capture':<14} {'dir':<4} {'wire':>5} {'decrypted':>10}  outer-byte histogram")
for name, _ in CAPTURES:
    for direction, hist_map in (("S→C", s2c_hists), ("C→S", c2s_hists)):
        nw, nok, outer, _ = hist_map[name]
        print(f"{name:<14} {direction:<4} {nw:>5} {nok:>10}  {fmt_outer(outer)}")

print()
print("=" * 100)
print("S→C SUB-PACKET HISTOGRAM (server sends these) — across 4 captures")
print("=" * 100)
print(f"{'sub-packet':<42} {'ACC1C1':>8} {'ACC1C2':>8} {'ACC2C1':>8} {'ACC2C2':>8}")

rows = []
for key in sorted(all_keys):
    vals = [s2c_hists[n][3].get(key, 0) for n, _ in CAPTURES]
    if sum(vals) > 0:
        rows.append((key, vals))
rows.sort(key=lambda r: -sum(r[1]))
for key, vals in rows:
    print(f"{key:<42} {vals[0]:>8} {vals[1]:>8} {vals[2]:>8} {vals[3]:>8}")

print()
print("=" * 100)
print("C→S SUB-PACKET HISTOGRAM (client sends these) — across 4 captures")
print("=" * 100)
print(f"{'sub-packet':<42} {'ACC1C1':>8} {'ACC1C2':>8} {'ACC2C1':>8} {'ACC2C2':>8}")

rows = []
for key in sorted(all_keys):
    vals = [c2s_hists[n][3].get(key, 0) for n, _ in CAPTURES]
    if sum(vals) > 0:
        rows.append((key, vals))
rows.sort(key=lambda r: -sum(r[1]))
for key, vals in rows:
    print(f"{key:<42} {vals[0]:>8} {vals[1]:>8} {vals[2]:>8} {vals[3]:>8}")
