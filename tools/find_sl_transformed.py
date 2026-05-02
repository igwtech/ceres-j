#!/usr/bin/env python3
"""find_sl_transformed.py — brute-force search for soullight under
common transforms / packings.

For each char's CharInfo body, scan EVERY byte offset and check whether
the bytes at that offset across all 5 chars satisfy a consistent
relationship with the known SL values [86, 51, 10, 10, 59]:

  - byte_i = SL_i + K  (constant additive offset, e.g. +128 signed)
  - byte_i = SL_i XOR K (constant XOR mask)
  - byte_i = SL_i * M + K (linear with constants)
  - byte_i = SL_i & 0x7F (low-7-bit packing — high bit shared)
  - byte_i high nibble = ((SL_i >> 4) & 0xF) (nibble packing)

Also tests u16le LE and BE with offset transforms.
"""
from __future__ import annotations
import sys, json, struct, argparse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
correlate = import_module("correlate_charinfo")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", "-j", type=Path, required=True)
    ap.add_argument("--strace-dir", type=Path,
                    default=Path("/home/javier/Documents/Projects/Neocron/ceres-j/strace"))
    args = ap.parse_args()

    spec = json.loads(args.json.read_text())
    chars = spec["characters"]
    parsed = []
    for ch in chars:
        pcap = args.strace_dir / ch["capture_pcap"]
        if not pcap.exists(): continue
        body = correlate.reassemble_charinfo(pcap)
        if not body: continue
        parsed.append({"name": ch["name"], "spec": ch, "body": body})

    sl_vals = [pc["spec"].get("soullight") for pc in parsed]
    if not all(v is not None for v in sl_vals):
        sys.exit("missing SL values")
    print(f"SL values: {dict(zip([pc['name'] for pc in parsed], sl_vals))}")

    # Make each body the same length for offset-by-offset analysis
    common_len = min(len(pc["body"]) for pc in parsed)
    bodies = [pc["body"] for pc in parsed]
    n_chars = len(bodies)

    # Hypothesis 1: byte_i = SL_i + K (mod 256) — constant additive offset
    print("\n--- Hypothesis: byte = SL + K (mod 256), K constant ---")
    h1_hits = []
    for off in range(common_len):
        bs = [bodies[c][off] for c in range(n_chars)]
        deltas = [(bs[c] - sl_vals[c]) & 0xFF for c in range(n_chars)]
        if len(set(deltas)) == 1:
            h1_hits.append((off, deltas[0], bs))
    if h1_hits:
        print(f"  {len(h1_hits)} offsets where SL+K hits all 5 chars")
        for off, k, bs in h1_hits[:20]:
            print(f"    +{off:04x}  K=0x{k:02x} ({k})  bytes={[f'{b:02x}' for b in bs]}")
    else:
        print("  no offsets satisfy SL+K=byte across all 5 chars")

    # Hypothesis 2: byte_i = SL_i XOR K
    print("\n--- Hypothesis: byte = SL XOR K, K constant ---")
    h2_hits = []
    for off in range(common_len):
        bs = [bodies[c][off] for c in range(n_chars)]
        xors = [bs[c] ^ sl_vals[c] for c in range(n_chars)]
        if len(set(xors)) == 1:
            h2_hits.append((off, xors[0], bs))
    if h2_hits:
        print(f"  {len(h2_hits)} offsets where SL XOR K hits all 5 chars")
        for off, k, bs in h2_hits[:20]:
            print(f"    +{off:04x}  K=0x{k:02x}  bytes={[f'{b:02x}' for b in bs]}")
    else:
        print("  no offsets satisfy SL XOR K = byte across all 5 chars")

    # Hypothesis 3: byte_i = SL_i & 0x7F (low 7 bits, high bit varies)
    print("\n--- Hypothesis: byte & 0x7F = SL & 0x7F (low-7-bit packed) ---")
    h3_hits = []
    for off in range(common_len):
        bs = [bodies[c][off] for c in range(n_chars)]
        lows = [bs[c] & 0x7F for c in range(n_chars)]
        sls7 = [sl_vals[c] & 0x7F for c in range(n_chars)]
        if lows == sls7:
            h3_hits.append((off, bs))
    if h3_hits:
        print(f"  {len(h3_hits)} offsets where low-7-bit == SL across all 5 chars")
        for off, bs in h3_hits[:20]:
            print(f"    +{off:04x}  bytes={[f'{b:02x}' for b in bs]}")
    else:
        print("  no low-7-bit packing matches")

    # Hypothesis 4: u16le with constant offset
    print("\n--- Hypothesis: u16le = SL + K (16-bit) ---")
    h4_hits = []
    for off in range(common_len - 1):
        ws = [bodies[c][off] | (bodies[c][off+1] << 8) for c in range(n_chars)]
        deltas = [(ws[c] - sl_vals[c]) & 0xFFFF for c in range(n_chars)]
        if len(set(deltas)) == 1:
            h4_hits.append((off, deltas[0], ws))
    if h4_hits:
        print(f"  {len(h4_hits)} offsets where u16le-K=SL hits all 5 chars")
        for off, k, ws in h4_hits[:20]:
            print(f"    +{off:04x}  K=0x{k:04x} ({k})  words={ws}")
    else:
        print("  no u16le additive-offset matches")

    # Hypothesis 5: low-nibble packing (4 bits)
    print("\n--- Hypothesis: low nibble of byte = SL & 0xF (4-bit packed) ---")
    h5_hits = []
    for off in range(common_len):
        bs = [bodies[c][off] for c in range(n_chars)]
        lowns = [bs[c] & 0xF for c in range(n_chars)]
        sls4 = [sl_vals[c] & 0xF for c in range(n_chars)]
        if lowns == sls4:
            h5_hits.append((off, bs))
    if h5_hits:
        print(f"  {len(h5_hits)} offsets where low-nibble matches SL low-nibble")
        # Filter to "likely real" — both 10s have low nibble A; 86=6, 51=3, 59=B
        # SL low nibbles: [6, 3, A, A, B] — 4 distinct values
        # Many positions will randomly satisfy this; print top 30
        for off, bs in h5_hits[:30]:
            print(f"    +{off:04x}  bytes={[f'{b:02x}' for b in bs]}")
    else:
        print("  no low-nibble packing matches")

    # Hypothesis 6: high-nibble packing
    print("\n--- Hypothesis: high nibble of byte = SL >> 4 (high 4 bits) ---")
    h6_hits = []
    for off in range(common_len):
        bs = [bodies[c][off] for c in range(n_chars)]
        highns = [(bs[c] >> 4) & 0xF for c in range(n_chars)]
        sls_h = [(sl_vals[c] >> 4) & 0xF for c in range(n_chars)]
        if highns == sls_h:
            h6_hits.append((off, bs))
    if h6_hits:
        print(f"  {len(h6_hits)} offsets where high-nibble matches SL>>4")
        for off, bs in h6_hits[:20]:
            print(f"    +{off:04x}  bytes={[f'{b:02x}' for b in bs]}")
    else:
        print("  no high-nibble packing matches")


if __name__ == "__main__":
    main()
