#!/usr/bin/env python3
"""Decrypt + reassemble client-side (C→S) multipart streams in retail captures.
Shows what the client sends to the server as multipart, how the server
responds, and whether this pattern is consistent across sessions."""
from pathlib import Path
import importlib.util, re
spec = importlib.util.spec_from_file_location("pb", "tools/parse-burst.py")
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

_RE_TS = re.compile(
    r'^\d+\s+(\d+:\d+:\d+\.\d+)\s+(recvmsg|sendmsg)\(\d+<UDP[^>]*>.*?iov_base="((?:[^"\\]|\\.)*)".*?= (\d+)$',
    re.MULTILINE,
)

def extract_with_ts(path):
    with open(path, "r", errors="replace") as f: content = f.read()
    packets = []
    for m in _RE_TS.finditer(content):
        ts, call = m.group(1), m.group(2)
        n = int(m.group(4))
        if n <= 0: continue
        direction = "S→C" if call == "recvmsg" else "C→S"
        data = pb.decode_strace_bytes(m.group(3))
        if data: packets.append((ts, direction, data))
    return packets

CAPTURES = [
    ("ACC1_CHAR1", "strace/nc2_strace_RETAIL_ACC1_CHAR1.log"),
    ("ACC1_CHAR2", "strace/nc2_strace_RETAIL_ACC1_CHAR2.log"),
    ("ACC2_CHAR1", "strace/nc2_strace_RETAIL_ACC2_CHAR1.log"),
    ("ACC2_CHAR2", "strace/nc2_strace_RETAIL_ACC2_CHAR2.log"),
]

for name, path in CAPTURES:
    print(f"\n========= {name} =========")
    pkts = extract_with_ts(path)
    # Collect C→S fragments in order
    fragments = []  # (pkt_index, ts, seq, frag_idx, total, chain_key, payload)
    for i, (ts, d, w) in enumerate(pkts):
        if d != "C→S": continue
        p = pb.decrypt_wire(w)
        if not p or p[0] != 0x13: continue
        parsed = pb.parse_gamedata(p)
        if not parsed: continue
        for s in parsed["subs"]:
            if s["outer"] == 0x03 and s.get("reliable_type") == 0x07:
                inner = s.get("inner_data", b"")
                if len(inner) < 5: continue
                frag_idx = inner[0] | (inner[1] << 8)
                total = inner[2] | (inner[3] << 8)
                chain_key = inner[4]
                payload = inner[5:]
                fragments.append((i, ts, s["reliable_seq"], frag_idx, total, chain_key, payload))

    print(f"  Total C→S multipart fragments: {len(fragments)}")
    if not fragments:
        continue

    # Group by total (= chain length); expect contiguous frag_idx runs
    chains = []
    current = []
    for f in fragments:
        if not current or f[4] != current[-1][4] or f[3] != current[-1][3] + 1:
            if current: chains.append(current)
            current = [f]
        else:
            current.append(f)
    if current: chains.append(current)

    for ci, chain in enumerate(chains):
        print(f"\n  --- Chain {ci+1}: {len(chain)} fragments ---")
        first_pkt_i, first_ts = chain[0][0], chain[0][1]
        last_pkt_i, last_ts = chain[-1][0], chain[-1][1]
        print(f"    sent {first_ts} .. {last_ts}  (pkts {first_pkt_i}..{last_pkt_i})")
        for _, ts, seq, idx, total, ck, payload in chain:
            print(f"    frag {idx}/{total} seq={seq} chain_key=0x{ck:02x} "
                  f"payload={len(payload)}B  first16={payload[:16].hex()}")
        # Reassemble assuming RETAIL format: strip 6-byte per-fragment header
        # (i.e., concat payload[6:] across all fragments).
        concat_full = b"".join(p for _,_,_,_,_,_,p in chain)
        concat_stripped = b"".join(p[6:] for _,_,_,_,_,_,p in chain if len(p) >= 6)
        print(f"    Full concat ({len(concat_full)}B) first 48: {concat_full[:48].hex()}")
        print(f"    Stripped 6B/frag  ({len(concat_stripped)}B) first 48: {concat_stripped[:48].hex()}")

        # Show the next 3 server packets that arrived after the last fragment
        print(f"    -- next 3 S→C server packets (seeking response) --")
        shown = 0
        for j in range(last_pkt_i+1, min(last_pkt_i+30, len(pkts))):
            if shown >= 3: break
            ts2, d2, w2 = pkts[j]
            if d2 != "S→C": continue
            p2 = pb.decrypt_wire(w2)
            if not p2: continue
            if p2[0] != 0x13:
                print(f"    [S→C @ {ts2}] non-13, byte0=0x{p2[0]:02x} ({len(w2)}B wire)")
                shown += 1; continue
            parsed2 = pb.parse_gamedata(p2)
            if not parsed2: continue
            summary = []
            for s2 in parsed2["subs"]:
                if s2["outer"] == 0x03:
                    rt = s2.get("reliable_type", 0)
                    summary.append(f"0x03→0x{rt:02x}({len(s2.get('inner_data', b''))}B)")
                else:
                    summary.append(f"0x{s2['outer']:02x}({s2['len']}B)")
            print(f"    [S→C @ {ts2}] {', '.join(summary)}")
            shown += 1
