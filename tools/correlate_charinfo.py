#!/usr/bin/env python3
"""correlate_charinfo.py — differential analysis of multi-character
CharInfo bursts to identify byte positions of known fields.

Inputs:
  - multichar.json   — per-char known values (cash, HP, faction, etc.)
  - one pcap per character (path in JSON)

Output: for each byte offset in the reassembled CharInfo multipart,
a list of which known fields it MIGHT correspond to (where the byte/
LE16/LE32 value at that offset matches a known character attribute
across MULTIPLE characters with high consistency).

The signal is strongest when:
  - All N characters have DISTINCT values for an attribute (e.g. all
    8 cash values are different)
  - The same offset in their CharInfo bursts contains those exact
    values, in the same byte order

A field is "found" when ≥3 chars hit and 0 chars miss.
"""
from __future__ import annotations
import argparse, json, os, struct, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")
burst_mod = import_module("parse-burst")

try:
    from scapy.all import PcapReader, IP, UDP
except ImportError:
    sys.exit("scapy missing — pip install --user --break-system-packages scapy")


def reassemble_charinfo(pcap: Path, server_ip: str = None) -> bytes:
    """Reassemble the CharInfo multipart from a pcap.
    Returns the concatenated payload (bytes after the 6-byte per-fragment header)."""
    # Auto-detect server IP if not given (highest non-local peer)
    if server_ip is None:
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
        if not peers:
            return b""
        server_ip = peers.most_common(1)[0][0]

    fragments = []
    single_2c = None  # fresh-char Genesis Dungeon CharInfo: single 0x03/0x2c
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
                if sub["outer"] == 0x03 and sub.get("reliable_type") == 0x07:
                    inner = sub.get("inner_data", b"")
                    if len(inner) < 11: continue
                    frag_idx = inner[0] | (inner[1] << 8)
                    chunk = inner[10:]   # skip 6-byte per-fragment header
                    fragments.append((frag_idx, chunk))
                elif sub["outer"] == 0x03 and sub.get("reliable_type") == 0x2c:
                    inner = sub.get("inner_data", b"")
                    if len(inner) > 500 and single_2c is None:
                        # Single-packet CharInfo (Genesis Dungeon fresh char).
                        # Skip 2-byte prefix to align with Section 1's `fa` marker.
                        fa_pos = inner.find(b"\xfa")
                        if fa_pos >= 0:
                            single_2c = inner[fa_pos - 3:]  # 3-byte ID/length prefix before S1
    if single_2c and not fragments:
        return single_2c
    fragments.sort()
    if not fragments:
        return b""
    # Some captures contain multiple multipart streams (e.g. login then
    # zone change). Take ONLY the first contiguous stream — fragments
    # 0,1,2,...,N until the index resets.
    first_stream = []
    expected = 0
    for idx, chunk in fragments:
        if idx != expected:
            break
        first_stream.append(chunk)
        expected += 1
    return b"".join(first_stream)


def parse_charinfo_sections(data: bytes) -> dict[int, tuple[int, bytes]]:
    """Parse [id 1B][size LE2][body]+ sections strictly. Sections that
    don't follow this format are skipped via byte-walk recovery.
    Returns {section_id: (offset_in_data, body)} — the FIRST occurrence
    of each id (later occurrences typically false matches in walked
    bytes)."""
    sections = {}
    i = 0
    if data[:1] == b"\x00":
        i = 1
    if data[i:i+3] == b"\x22\x02\x01":
        i += 3

    # Section ids 1..14 are valid in NC2 CharInfo; some are 0x0a, 0x0b,
    # 0x0c, 0x0d. Assume strictly sequential ids starting at 1, with
    # potentially missing optional sections. Stop on first impossible jump.
    expected_next_ids = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14}
    while i + 3 < len(data):
        sid = data[i]
        size = data[i+1] | (data[i+2] << 8)
        if (sid not in expected_next_ids or size > 4096
                or i + 3 + size > len(data)):
            i += 1
            continue
        if sid in sections:
            i += 1
            continue
        sections[sid] = (i + 3, data[i+3:i+3+size])
        i += 3 + size
    return sections


def section_aware_search(parsed_chars: list, fname: str, encoding: str):
    """Search every char's section bodies for a given field value.
    Returns dict {(section_id, offset_within_section): list of (char_name, value)}."""
    from collections import defaultdict
    hits_by_secoff = defaultdict(list)
    for pc in parsed_chars:
        v = pc["spec"].get(fname)
        if v is None: continue
        for sid, (sec_off, sec_body) in pc["sections"].items():
            offsets = search_value(sec_body, v, encoding)
            for off in offsets:
                hits_by_secoff[(sid, off)].append((pc["name"], v))
    return hits_by_secoff


def search_value(data: bytes, value, encoding: str) -> list[int]:
    """Search for `value` in `data` using the given encoding.
    Encodings: 'u8', 's8', 'u16le', 's16le', 'u32le', 's32le', 'f32le'.
    Returns all match offsets."""
    if value is None: return []
    try:
        if encoding == "u8":
            if not (0 <= value <= 255): return []
            pat = bytes([value])
        elif encoding == "s8":
            if not (-128 <= value <= 127): return []
            pat = struct.pack("<b", value)
        elif encoding == "u16le":
            if not (0 <= value <= 65535): return []
            pat = struct.pack("<H", value)
        elif encoding == "s16le":
            if not (-32768 <= value <= 32767): return []
            pat = struct.pack("<h", value)
        elif encoding == "u32le":
            if not (0 <= value <= 0xFFFFFFFF): return []
            pat = struct.pack("<I", value)
        elif encoding == "s32le":
            pat = struct.pack("<i", value)
        elif encoding == "f32le":
            pat = struct.pack("<f", float(value))
        else:
            return []
    except struct.error:
        return []
    hits = []
    i = 0
    while True:
        p = data.find(pat, i)
        if p < 0: break
        hits.append(p)
        i = p + 1
    return hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", "-j", type=Path, required=True)
    ap.add_argument("--strace-dir", type=Path,
                    default=Path("/home/javier/Documents/Projects/Neocron/ceres-j/strace"))
    ap.add_argument("--min-hits", type=int, default=3,
                    help="Minimum chars whose value matches at the same offset")
    args = ap.parse_args()

    spec = json.loads(args.json.read_text())
    chars = spec["characters"]
    print(f"Loaded {len(chars)} character entries")

    # Reassemble CharInfo for each character
    parsed_chars = []
    for ch in chars:
        pcap_pattern = ch.get("capture_pcap", "")
        # Resolve glob/wildcard
        if "<ts>" in pcap_pattern:
            stem = pcap_pattern.replace("<ts>", "*")
            matches = sorted(args.strace_dir.glob(stem))
            if not matches:
                print(f"  ⚠️  {ch['name']}: no pcap matches '{stem}'")
                continue
            pcap = matches[-1]   # newest
        else:
            pcap = args.strace_dir / pcap_pattern
        if not pcap.exists():
            print(f"  ⚠️  {ch['name']}: pcap not found at {pcap}")
            continue
        body = reassemble_charinfo(pcap)
        if not body:
            print(f"  ⚠️  {ch['name']}: empty CharInfo from {pcap.name}")
            continue
        sections = parse_charinfo_sections(body)
        parsed_chars.append({
            "name": ch["name"],
            "spec": ch,
            "body": body,
            "sections": sections,
        })
        print(f"  ✓ {ch['name']}: CharInfo {len(body)}B, sections {sorted(sections.keys())}")

    if not parsed_chars:
        sys.exit("no parseable captures")

    # For each known field, search every char's body
    FIELDS = [
        ("hp_cur", "u16le"), ("hp_max", "u16le"),
        ("sta_cur", "u16le"), ("sta_max", "u16le"),
        ("psi_cur", "u16le"), ("psi_max", "u16le"),
        ("cash", "u32le"),
        ("soullight", "u8"), ("soullight", "s8"),
        ("soullight", "u16le"), ("soullight", "s16le"),
        ("rank", "u8"), ("level", "u8"), ("level", "u16le"),
        ("profession", "u8"), ("class_id", "u8"),
        ("faction_id", "u8"),
        ("kill_cash_delta", "u32le"),
        ("subskill_HLT", "u8"), ("subskill_PSU", "u8"),
        ("subskill_ATL", "u8"), ("subskill_END", "u8"),
    ]

    print(f"\n{'='*78}")
    print(f"  SECTION-AWARE CORRELATION (min_hits={args.min_hits})")
    print(f"{'='*78}")

    for fname, encoding in FIELDS:
        # Per-char value summary
        vals = [(pc["name"], pc["spec"].get(fname)) for pc in parsed_chars
                if pc["spec"].get(fname) is not None]
        if len(vals) < args.min_hits:
            continue

        hits_by_secoff = section_aware_search(parsed_chars, fname, encoding)
        # Filter to (section, offset) where ≥ min_hits chars produced a match
        good = [(secoff, hit_list) for secoff, hit_list in hits_by_secoff.items()
                if len(hit_list) >= args.min_hits]
        if not good:
            continue

        good.sort(key=lambda x: (-len(x[1]), x[0]))
        print(f"\n{fname} ({encoding}):")
        for cname, v in vals:
            print(f"    {cname:15s} = {v}")
        for (sid, off), hit_list in good[:5]:
            chars_hit = ", ".join(c for c, _ in hit_list)
            print(f"  → S{sid}+{off}  hits in {len(hit_list)}/{len(vals)}  [{chars_hit}]")


if __name__ == "__main__":
    main()
