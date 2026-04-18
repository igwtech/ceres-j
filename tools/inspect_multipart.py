#!/usr/bin/env python3
"""Dump retail multipart 0x03→0x07 fragments and extract the reassembled payload."""
import sys
from pathlib import Path
import importlib.util
spec = importlib.util.spec_from_file_location("pb", "tools/parse-burst.py")
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

for capture in ["strace/nc2_strace_RETAIL_ACC1_CHAR1.log"]:
    print(f"==== {capture} ====")
    wire = pb.extract_udp(Path(capture), "recv")
    fragments = []  # list of (seq, frag_idx, total, chain_key, payload)
    for w in wire:
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
                seq = s["reliable_seq"]
                fragments.append((seq, frag_idx, total, chain_key, payload))
                print(f"  fragment seq={seq} frag={frag_idx}/{total} chain_key=0x{chain_key:02x} payload_len={len(payload)}")
                print(f"    first16: {payload[:16].hex()}")

    # Reassemble by chain_key
    print(f"\n  Total multipart fragments: {len(fragments)}")
    if fragments:
        # Group by chain_key to reassemble
        chains = {}
        for seq, idx, total, ck, payload in fragments:
            chains.setdefault(ck, []).append((idx, payload))
        for ck, frags in chains.items():
            frags.sort(key=lambda x: x[0])
            reassembled = b"".join(p for _, p in frags)
            print(f"\n  Chain key 0x{ck:02x}: {len(frags)} fragments, reassembled {len(reassembled)} bytes")
            print(f"    first 32 bytes: {reassembled[:32].hex()}")
