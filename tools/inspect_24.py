#!/usr/bin/env python3
"""Dump all 0x03→0x24 packets + server response context from retail."""
import sys
from pathlib import Path
import importlib.util
spec = importlib.util.spec_from_file_location("pb", "tools/parse-burst.py")
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)
import re

CAPTURES = [
    ("ACC1_CHAR1", "strace/nc2_strace_RETAIL_ACC1_CHAR1.log"),
    ("ACC1_CHAR2", "strace/nc2_strace_RETAIL_ACC1_CHAR2.log"),
    ("ACC2_CHAR1", "strace/nc2_strace_RETAIL_ACC2_CHAR1.log"),
    ("ACC2_CHAR2", "strace/nc2_strace_RETAIL_ACC2_CHAR2.log"),
]

# Extract UDP packets with their timestamp + direction, preserving order.
_RE_TS = re.compile(
    r'^\d+\s+(\d+:\d+:\d+\.\d+)\s+(recvmsg|sendmsg)\(\d+<UDP[^>]*>.*?iov_base="((?:[^"\\]|\\.)*)".*?= (\d+)$',
    re.MULTILINE,
)

def extract_with_ts(path):
    with open(path, "r", errors="replace") as f: content = f.read()
    packets = []
    for m in _RE_TS.finditer(content):
        ts, call, data_str, n = m.group(1), m.group(2), m.group(3), int(m.group(4))
        if n <= 0: continue
        direction = "S→C" if call == "recvmsg" else "C→S"
        data = pb.decode_strace_bytes(data_str)
        if data: packets.append((ts, direction, data))
    return packets

for name, path in CAPTURES:
    print(f"\n======= {name} =======")
    pkts = extract_with_ts(path)
    print(f"  total pkts with timestamps: {len(pkts)}")

    # Find client→server 0x03→0x24 packets; print content + surrounding server responses
    for i, (ts, dir, wire) in enumerate(pkts):
        if dir != "C→S": continue
        plain = pb.decrypt_wire(wire)
        if not plain or plain[0] != 0x13: continue
        parsed = pb.parse_gamedata(plain)
        if not parsed: continue
        for s in parsed["subs"]:
            if s["outer"] == 0x03 and s.get("reliable_type") == 0x24:
                inner = s.get("inner_data", b"")
                print(f"\n  [C→S @ {ts}] pkt#{i} 0x03→0x24 (reliable sub-type)")
                print(f"    full sub: {s['data'].hex()}")
                print(f"    inner data ({len(inner)}B): {inner.hex()}")
                # Show the next 5 S→C packets after this
                print(f"    -- next 5 server packets --")
                shown = 0
                for j in range(i+1, len(pkts)):
                    if shown >= 5: break
                    ts2, dir2, wire2 = pkts[j]
                    if dir2 != "S→C": continue
                    p2 = pb.decrypt_wire(wire2)
                    if not p2 or p2[0] != 0x13:
                        print(f"    [S→C @ {ts2}] non-13 byte0=0x{p2[0] if p2 else 0:02x}")
                        shown += 1
                        continue
                    parsed2 = pb.parse_gamedata(p2)
                    if not parsed2: continue
                    sub_summary = []
                    for s2 in parsed2["subs"]:
                        if s2["outer"] == 0x03:
                            rt = s2.get("reliable_type", 0)
                            sub_summary.append(f"0x03→0x{rt:02x}({len(s2.get('inner_data', b''))}B)")
                        else:
                            sub_summary.append(f"0x{s2['outer']:02x}({s2['len']}B)")
                    print(f"    [S→C @ {ts2}] {', '.join(sub_summary)}")
                    shown += 1
