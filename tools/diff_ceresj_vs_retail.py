#!/usr/bin/env python3
"""Side-by-side S→C / C→S timeline diff of a Ceres-J session vs retail.

Goal: find what retail sends during the playing-past-plaza phase that
Ceres-J does NOT, and vice versa. Both captures are now encrypted with
the same LFSR CFB cipher so the decrypt path is identical.

We split each session into phases based on fingerprint packet types:
  PHASE 0 — handshakes (0x01 outer) only
  PHASE 1 — initial burst (CharInfo multipart etc., up to first 0x24 from client)
  PHASE 2 — zone-population (after 0x24, many 0x03→0x1b)
  PHASE 3 — gameplay / playing
  PHASE 4 — shutdown (abort)

The diff focuses on PHASE 3 since that's where Ceres-J diverges.
"""
import re, sys
from pathlib import Path
from collections import Counter, defaultdict
import importlib.util
spec = importlib.util.spec_from_file_location("pb", "tools/parse-burst.py")
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

_RE_TS = re.compile(
    r'^\d+\s+(\d+:\d+:\d+\.\d+)\s+(recvmsg|sendmsg)\(\d+<UDP[^>]*>.*?iov_base="((?:[^"\\]|\\.)*)".*?= (\d+)$',
    re.MULTILINE,
)

def extract(path):
    with open(path, "r", errors="replace") as f: content = f.read()
    out = []
    for m in _RE_TS.finditer(content):
        ts, call = m.group(1), m.group(2)
        n = int(m.group(4))
        if n <= 0: continue
        direction = "S→C" if call == "recvmsg" else "C→S"
        data = pb.decode_strace_bytes(m.group(3))
        if data: out.append((ts, direction, data))
    return out

def phase_bucket(pkts, direction):
    """Return list of (ts, plain_bytes) for given direction, skipping
    packets that fail to decrypt."""
    out = []
    for ts, d, wire in pkts:
        if d != direction: continue
        p = pb.decrypt_wire(wire)
        if not p: continue
        out.append((ts, p))
    return out

def categorize_subpackets(plain):
    """Return list of (outer_type, reliable_type_or_None, inner_opcode_or_None, size)
    for a 0x13 gamedata packet; None otherwise."""
    if not plain or plain[0] != 0x13:
        return [(plain[0] if plain else None, None, None, len(plain))]
    parsed = pb.parse_gamedata(plain)
    if not parsed: return []
    subs = []
    for s in parsed["subs"]:
        ot = s["outer"]
        rt = s.get("reliable_type")
        inner = s.get("inner_data", b"")
        opc = None
        if rt == 0x1f and len(inner) >= 3:
            opc = inner[2]
        subs.append((ot, rt, opc, s["len"]))
    return subs

def ts_to_ms(ts):
    h, m, s = ts.split(":")
    s, us = s.split(".")
    return int(h)*3600000 + int(m)*60000 + int(s)*1000 + int(us)//1000

def phase_breakdown(pkts, direction, label):
    """Classify each packet into a phase and print a histogram per phase."""
    sub_pkts = phase_bucket(pkts, direction)
    if not sub_pkts:
        print(f"  {label}: no packets")
        return

    # State-tracked phase assignment based on packet stream
    # Phase advances happen on specific observed events.
    start_ms = ts_to_ms(sub_pkts[0][0])
    phase = 0
    phase_transitions = []

    client_sent_0x24 = False
    client_sent_20   = False  # first movement
    rsync_re_entered = False  # second 0x24 = re-sync attempt

    phase_stats = defaultdict(lambda: Counter())
    phase_pkt_count = defaultdict(int)
    phase_timerange = defaultdict(lambda: [None, None])

    # Need to iterate BOTH directions to detect phase transitions
    all_pkts = []
    for ts, d, wire in pkts:
        plain = pb.decrypt_wire(wire)
        if not plain: continue
        all_pkts.append((ts, d, plain))

    seen_0x24_count = 0
    cur_phase = 0
    for ts, d, plain in all_pkts:
        ms = ts_to_ms(ts) - start_ms
        # Detect phase transitions
        if d == "C→S":
            subs = categorize_subpackets(plain)
            for ot, rt, opc, sz in subs:
                if rt == 0x24:
                    seen_0x24_count += 1
                    if seen_0x24_count == 1 and cur_phase < 2:
                        cur_phase = 2
                        phase_transitions.append((ms, "client sent 0x24 (ready)"))
                    elif seen_0x24_count >= 2:
                        cur_phase = 4
                        phase_transitions.append((ms, f"client sent 0x24 #{seen_0x24_count} (RE-SYNC)"))
                if ot == 0x20 and cur_phase == 2:
                    cur_phase = 3
                    phase_transitions.append((ms, "client sent first 0x20 movement"))
                if plain[0] == 0x08:
                    cur_phase = 5
                    phase_transitions.append((ms, "client sent ABORT"))

        # Only record stats for the direction we care about
        if d != direction: continue
        phase_pkt_count[cur_phase] += 1
        if phase_timerange[cur_phase][0] is None:
            phase_timerange[cur_phase][0] = ms
        phase_timerange[cur_phase][1] = ms
        subs = categorize_subpackets(plain)
        for ot, rt, opc, sz in subs:
            if rt is not None:
                key = f"0x03→0x{rt:02x}"
                if rt == 0x1f and opc is not None:
                    key += f" opc=0x{opc:02x}"
            else:
                key = f"0x{ot:02x} raw"
            phase_stats[cur_phase][key] += 1

    print(f"\n{'='*72}\n{label}  —  {direction}\n{'='*72}")
    print("Phase transitions (ms from first packet):")
    for ms, evt in phase_transitions:
        print(f"  +{ms:>6} ms — {evt}")

    PHASE_NAMES = {
        0: "handshake",
        2: "initial burst + zone pop",
        3: "PLAYING (moving)",
        4: "RE-SYNC (2nd 0x24)",
        5: "after abort",
    }
    for ph in sorted(phase_stats.keys()):
        lo, hi = phase_timerange[ph]
        dur_s = (hi - lo) / 1000.0 if hi and lo else 0
        print(f"\n  --- Phase {ph} ({PHASE_NAMES.get(ph,'?')}): {phase_pkt_count[ph]} pkts over {dur_s:.1f}s ---")
        for key, n in phase_stats[ph].most_common(20):
            rate = n/dur_s if dur_s > 0 else 0
            print(f"    {n:4d}x  {key:<28}  ({rate:.1f}/s)")

if __name__ == "__main__":
    print("Loading Ceres-J capture (this may take a minute for a large strace)...")
    ceresj = extract("strace/nc2_strace_CERESJ_B_20260412_2222.log")
    print(f"  {len(ceresj)} UDP packets extracted")

    print("Loading retail ACC1_CHAR1 capture...")
    retail = extract("strace/nc2_strace_RETAIL_ACC1_CHAR1.log")
    print(f"  {len(retail)} UDP packets extracted")

    for direction in ("S→C", "C→S"):
        phase_breakdown(ceresj, direction, "CERES-J")
        phase_breakdown(retail, direction, "RETAIL ACC1_CHAR1")
