#!/usr/bin/env python3
"""find_soullight.py — scan ALL decrypted UDP packets in a pcap for a
target byte value (the character's known soullight) outside of the
0x03/0x07 multipart stream. Differential variant: takes multiple pcaps
+ expected SL values; reports sub-opcodes whose payload contains the
expected value across multiple pcaps at consistent offsets.
"""
from __future__ import annotations
import sys, struct, json, argparse
from pathlib import Path
from collections import defaultdict

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")
burst_mod = import_module("parse-burst")

from scapy.all import PcapReader, IP, UDP


def scan_pcap(pcap: Path, sl_value: int) -> dict:
    """For a single pcap, find all sub-packets whose inner_data contains
    sl_value as u8 / s8 / u16le. Skip outer 0x03+reliable_type 0x07
    (multipart). Return list of (outer, sub_op, offset, encoding, payload_hex_snippet)."""
    pats = []
    if 0 <= sl_value <= 255:
        pats.append(("u8", bytes([sl_value])))
    if -128 <= sl_value <= 127:
        pats.append(("s8", struct.pack("<b", sl_value)))
    if 0 <= sl_value <= 65535:
        pats.append(("u16le", struct.pack("<H", sl_value)))

    hits = defaultdict(list)   # key: (outer, sub_id_byte_repr, offset, encoding)

    # Auto-detect server IP
    from collections import Counter
    peers = Counter()
    with PcapReader(str(pcap)) as pr:
        for i, pkt in enumerate(pr):
            if i > 5000: break
            if IP not in pkt: continue
            for ip in (pkt[IP].src, pkt[IP].dst):
                if not (ip.startswith("127.") or ip.startswith("192.168.")
                        or ip.startswith("172.") or ip.startswith("10.")):
                    peers[ip] += 1
    if not peers: return {}
    server_ip = peers.most_common(1)[0][0]

    with PcapReader(str(pcap)) as pr:
        for pkt in pr:
            if IP not in pkt or UDP not in pkt: continue
            if pkt[IP].src != server_ip: continue
            payload = bytes(pkt[UDP].payload)
            r = decrypt_mod.decrypt_wire_packet(payload)
            if not r: continue
            plain = r[0]
            parsed = burst_mod.parse_gamedata(plain)
            if not parsed: continue
            for sub in parsed["subs"]:
                outer = sub["outer"]
                inner = sub.get("inner_data", b"")
                if not inner: continue
                # Skip multipart (CharInfo / CharsysInfo)
                if outer == 0x03 and sub.get("reliable_type") == 0x07:
                    continue
                # Compute first 1-2 bytes as "sub-op signature"
                sig = inner[:4].hex() if len(inner) >= 1 else ""
                for enc, pat in pats:
                    i2 = 0
                    while True:
                        p = inner.find(pat, i2)
                        if p < 0: break
                        # Skip the magic 01 00 prefix (offset 0/1 are
                        # always the chain key, not a real field)
                        if p < 2:
                            i2 = p + 1; continue
                        key = (outer, sig, p, enc)
                        hits[key].append(inner[:32].hex())
                        i2 = p + 1
    return hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", "-j", type=Path, required=True)
    ap.add_argument("--strace-dir", type=Path,
                    default=Path("/home/javier/Documents/Projects/Neocron/ceres-j/strace"))
    args = ap.parse_args()

    spec = json.loads(args.json.read_text())
    chars = spec["characters"]

    # Per-char hit map
    per_char = {}
    for ch in chars:
        sl = ch.get("soullight")
        if sl is None: continue
        pcap = args.strace_dir / ch["capture_pcap"]
        if not pcap.exists():
            print(f"⚠️  {ch['name']}: pcap missing")
            continue
        print(f"Scanning {ch['name']} (SL={sl}) in {pcap.name}")
        per_char[ch["name"]] = scan_pcap(pcap, sl)

    # Cross-char correlation: which (outer, sig, offset, encoding) keys
    # show up in MULTIPLE chars?
    all_keys = defaultdict(set)
    for cname, hits in per_char.items():
        for key in hits:
            all_keys[key].add(cname)

    # Filter to keys with ≥3 chars hit
    print(f"\n{'='*78}")
    print("  Cross-char SL correlation (≥3 chars must hit same key)")
    print(f"{'='*78}")
    multi = sorted(all_keys.items(), key=lambda x: -len(x[1]))
    found = False
    for key, chars_hit in multi:
        if len(chars_hit) < 3: continue
        outer, sig, off, enc = key
        chars_str = ", ".join(sorted(chars_hit))
        print(f"  outer=0x{outer:02x} sig={sig[:8]} +{off} {enc}  [{chars_str}]")
        # Show one sample from each char
        for cname in sorted(chars_hit):
            sample_hits = per_char[cname].get(key, [])
            if sample_hits:
                print(f"      {cname}: {sample_hits[0]}")
        found = True
    if not found:
        print("  (no cross-char matches at min_hits=3)")
        print("\nBest candidates with 2 chars:")
        for key, chars_hit in multi[:20]:
            if len(chars_hit) < 2: break
            outer, sig, off, enc = key
            chars_str = ", ".join(sorted(chars_hit))
            print(f"  outer=0x{outer:02x} sig={sig[:8]} +{off} {enc}  [{chars_str}]")


if __name__ == "__main__":
    main()
