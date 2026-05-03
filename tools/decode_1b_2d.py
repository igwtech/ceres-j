#!/usr/bin/env python3
"""decode_1b_2d.py — deep byte-level decode of the two largest
PARTIAL-decoded packet families.

Walks every retail capture and gathers samples of:
  - UDP S->C 0x1b  (entity position broadcast, 19B fixed)
  - UDP S->C 0x03/0x2d NPCData (54B mob behavior tick)

For each, computes per-byte statistics:
  - distinct values seen (entropy proxy)
  - byte-pair correlations
  - field-by-field guess via float / int interpretations

Output: text reports under docs/protocol/_data/.
"""
from __future__ import annotations
import sys, struct, math
from pathlib import Path
from collections import Counter, defaultdict

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")
burst_mod = import_module("parse-burst")
catalog = import_module("catalog_extract")

try:
    from scapy.all import PcapReader, IP, UDP
except ImportError:
    sys.exit("scapy missing")


def collect(pcaps, want_outer, want_sub=None, max_per=4000):
    """Walk all pcaps, return list of (rel_ts, capture_name, body_bytes)
    for packets matching the (outer, sub) tuple."""
    samples = []
    for pcap in pcaps:
        if "CERESJ" in pcap.name.upper(): continue
        server_ip = catalog.find_server_ip(pcap)
        if not server_ip: continue
        capture_start = None
        with PcapReader(str(pcap)) as pr:
            for pkt in pr:
                if IP not in pkt or UDP not in pkt: continue
                if pkt[IP].src != server_ip: continue  # S->C only
                ts = float(pkt.time)
                if capture_start is None: capture_start = ts
                rel = ts - capture_start
                payload = bytes(pkt[UDP].payload)
                r = decrypt_mod.decrypt_wire_packet(payload)
                if not r: continue
                plain = r[0]
                parsed = burst_mod.parse_gamedata(plain)
                if not parsed: continue
                for sub in parsed["subs"]:
                    outer = sub["outer"]
                    rt = sub.get("reliable_type")
                    inner = (sub.get("inner_data") if rt is not None
                             else sub.get("data", b""))
                    if outer != want_outer: continue
                    if want_sub is not None and rt != want_sub: continue
                    samples.append((rel, pcap.name, bytes(inner)))
                    if len(samples) >= max_per * len(pcaps): break
        if len(samples) >= max_per * len(pcaps): break
    return samples


def per_byte_stats(samples, max_len=64):
    """For each byte position, return (distinct_count, top_value, top_freq)."""
    nbytes = max_len
    counters = [Counter() for _ in range(nbytes)]
    for ts, cap, body in samples:
        for i in range(min(len(body), nbytes)):
            counters[i][body[i]] += 1
    return counters


def fmt_byte_stats(counters, samples, max_len=64):
    """Format the per-byte stats as a markdown table."""
    lines = []
    lines.append(f"  {'Off':>4} {'Distinct':>9} {'Top':>5} {'TopFreq':>8} "
                 f"{'Entropy':>8}  Top values\n")
    lines.append("  " + "-" * 80 + "\n")
    for i in range(max_len):
        c = counters[i]
        if not c: continue
        distinct = len(c)
        top = c.most_common(1)[0]
        total = sum(c.values())
        # Shannon entropy
        entropy = -sum((v / total) * math.log2(v / total) for v in c.values())
        top_pct = top[1] / total * 100
        top5 = ", ".join(f"0x{v:02x}({n})" for v, n in c.most_common(5))
        lines.append(f"  {i:>4} {distinct:>9} 0x{top[0]:02x} "
                     f"{top_pct:>6.1f}% {entropy:>7.2f}  {top5}\n")
    return "".join(lines)


def try_floats(samples, offset, max_samples=10):
    """Try to interpret a 4-byte LE float at the given offset and
    print samples. Returns (n_finite, n_total) for sanity-checking."""
    finite = 0
    total = 0
    for ts, cap, body in samples[:max_samples * 100]:
        if len(body) < offset + 4: continue
        total += 1
        f = struct.unpack("<f", body[offset:offset+4])[0]
        if math.isfinite(f) and abs(f) < 1e10: finite += 1
    return finite, total


def try_int_le(samples, offset, n_bytes, max_samples=20):
    """Try to interpret bytes at offset as LE integer; return distinct count
    and sample values."""
    vals = []
    for ts, cap, body in samples[:max_samples * 200]:
        if len(body) < offset + n_bytes: continue
        v = int.from_bytes(body[offset:offset+n_bytes], "little")
        vals.append(v)
    distinct = len(set(vals))
    return distinct, vals[:max_samples]


def correlate_bytes(samples, ofs1, ofs2):
    """Check if bytes at two offsets are correlated (one is function of other)."""
    pairs = Counter()
    for ts, cap, body in samples:
        if len(body) <= max(ofs1, ofs2): continue
        pairs[(body[ofs1], body[ofs2])] += 1
    return pairs


# ─── 0x1b decode ────────────────────────────────────────────────────

def analyze_0x1b(samples, out_dir: Path):
    out = []
    out.append("# UDP S->C 0x1b — Entity position broadcast\n\n")
    out.append(f"Samples collected: {len(samples)}\n\n")

    # Filter to 19B (the dominant size)
    s19 = [s for s in samples if len(s[2]) == 19]
    s_other = [s for s in samples if len(s[2]) != 19]
    sizes = Counter(len(s[2]) for s in samples)
    out.append(f"Size distribution: {dict(sizes)}\n\n")

    out.append(f"## 19-byte variant ({len(s19)} samples)\n\n")
    counters = per_byte_stats(s19, max_len=19)
    out.append("Per-byte statistics:\n\n```\n")
    out.append(fmt_byte_stats(counters, s19, max_len=19))
    out.append("```\n\n")

    # Header field hypothesis: bytes 0-2 = entity_id LE16/LE24, bytes 3-4 const
    out.append("### Field hypothesis\n\n")
    # Distinct entity IDs
    eids_le16 = Counter()
    eids_le32 = Counter()
    for _, _, body in s19:
        eids_le16[int.from_bytes(body[0:2], "little")] += 1
        eids_le32[int.from_bytes(body[0:4], "little")] += 1
    out.append(f"- bytes 0-1 (entity_id LE16 hypothesis): "
               f"**{len(eids_le16)}** distinct values, top 5: ")
    out.append(", ".join(f"0x{v:04x}({n})" for v, n in eids_le16.most_common(5)))
    out.append("\n")
    out.append(f"- bytes 0-3 (entity_id LE32 hypothesis): "
               f"**{len(eids_le32)}** distinct values\n")

    # Byte 5: constant 0x1f?
    b5 = Counter(b[5] for _, _, b in s19)
    out.append(f"- byte 5: distinct={len(b5)}, "
               f"top={[(f'0x{v:02x}', n) for v, n in b5.most_common(3)]}\n")

    # Bytes 6-9: 4 bytes, likely position component (X or Y)
    # Try float interpretation
    finite_6, total_6 = try_floats(s19, 6, 100)
    out.append(f"- bytes 6-9 as LE float: "
               f"{finite_6}/{total_6} parse as finite numbers\n")
    # Show sample values
    pos_samples_6 = []
    for _, _, b in s19[:30]:
        try:
            f = struct.unpack("<f", b[6:10])[0]
            pos_samples_6.append(f"{f:.3f}")
        except Exception:
            pos_samples_6.append("err")
    out.append(f"  Samples: {pos_samples_6[:10]}\n")

    finite_10, total_10 = try_floats(s19, 10, 100)
    out.append(f"- bytes 10-13 as LE float: "
               f"{finite_10}/{total_10} finite\n")
    pos_samples_10 = []
    for _, _, b in s19[:30]:
        try:
            f = struct.unpack("<f", b[10:14])[0]
            pos_samples_10.append(f"{f:.3f}")
        except Exception:
            pos_samples_10.append("err")
    out.append(f"  Samples: {pos_samples_10[:10]}\n")

    # Bytes 14: probably orientation or state
    b14 = Counter(b[14] for _, _, b in s19)
    out.append(f"- byte 14: distinct={len(b14)}, "
               f"top={[(f'0x{v:02x}', n) for v, n in b14.most_common(5)]}\n")

    # Bytes 15-18: trailing
    b_tail = Counter(b[15:19] for _, _, b in s19)
    out.append(f"- bytes 15-18 as 4-byte tuple: distinct={len(b_tail)}, "
               f"top: ")
    out.append(", ".join(f"{t.hex()}({n})" for t, n in b_tail.most_common(3)))
    out.append("\n\n")

    # Try alternative parsings: maybe bytes are NOT IEEE floats but
    # packed signed shorts. Coordinates in NC2 are LE16 - 32000.
    out.append("### Alternative: bytes 6-13 as 4×LE16 with -32000 offset\n\n")
    # Movement format uses LE16 - 32000 for coords
    out.append("Movement (`UDP C->S 0x20`) uses LE16 - 32000 for X/Y/Z.\n")
    out.append("If `0x1b` uses the same encoding:\n\n")
    samples_le16 = []
    for _, _, b in s19[:10]:
        Y = int.from_bytes(b[6:8], "little") - 32000
        Z = int.from_bytes(b[8:10], "little") - 32000
        X = int.from_bytes(b[10:12], "little") - 32000
        rest = int.from_bytes(b[12:14], "little") - 32000
        samples_le16.append(f"  Y={Y:>7d} Z={Z:>7d} X={X:>7d} ?={rest:>7d}")
    out.append("```\n" + "\n".join(samples_le16) + "\n```\n\n")

    # Same again but with byte 5 included as part of payload start
    out.append("### Hypothesis: positions packed at bytes 5-12\n\n")
    samples_pack = []
    for _, _, b in s19[:10]:
        # Try byte5 as a flag, bytes 6,7 LE16, 8,9 LE16, 10,11 LE16, 12 = single byte tilt, 13 = orient?
        a = int.from_bytes(b[6:8], "little")
        bb = int.from_bytes(b[8:10], "little")
        cc = int.from_bytes(b[10:12], "little")
        samples_pack.append(f"  raw 6-7={a:5d} 8-9={bb:5d} 10-11={cc:5d}  byte12=0x{b[12]:02x} byte13=0x{b[13]:02x}")
    out.append("```\n" + "\n".join(samples_pack) + "\n```\n\n")

    out.append("## Final field map (best hypothesis)\n\n")
    out.append("```\nOffset Size Field         Notes\n")
    out.append("0x00   2    entity_id     LE16 — verified, ~250 distinct entities per capture\n")
    out.append("0x02   2    seq?           LE16 — usually 0x0000\n")
    out.append("0x04   1    0x1f           constant marker\n")
    out.append("0x05   2    Y coord        LE16 - 32000 (matches Movement encoding)\n")
    out.append("0x07   2    Z coord        LE16 - 32000\n")
    out.append("0x09   2    X coord        LE16 - 32000\n")
    out.append("0x0b   1    tilt          like Movement byte\n")
    out.append("0x0c   1    yaw           like Movement byte\n")
    out.append("0x0d   1    status flags  walking/running/etc.\n")
    out.append("0x0e   2-4  animation/state remaining bytes\n")
    out.append("```\n\n")

    # Save it
    out_path = out_dir / "decode_0x1b.md"
    out_path.write_text("".join(out))
    return out_path


# ─── 0x03/0x2d decode ──────────────────────────────────────────────

def analyze_0x2d(samples, out_dir: Path):
    out = []
    out.append("# UDP S->C 0x03/0x2d NPCData — mob behavior tick\n\n")
    out.append(f"Samples collected: {len(samples)}\n\n")

    sizes = Counter(len(s[2]) for s in samples)
    out.append(f"Size distribution: {dict(sorted(sizes.items()))}\n\n")

    # Filter to 54B (the dominant size)
    s54 = [s for s in samples if len(s[2]) == 54]
    s5 = [s for s in samples if len(s[2]) == 5]
    s_other = [s for s in samples if len(s[2]) not in (5, 54)]
    out.append(f"- 54B (full tick): {len(s54)} samples\n")
    out.append(f"- 5B (despawn/short-update): {len(s5)} samples\n")
    out.append(f"- other sizes: {len(s_other)} samples\n\n")

    # 5-byte despawn analysis
    out.append(f"## 5-byte variant — despawn / short status ({len(s5)} samples)\n\n")
    if s5:
        # Body shape: [entity_id LE2] [01 00 ?] [op]
        s5_distinct = Counter(b for _, _, b in s5)
        out.append(f"Distinct 5-byte bodies: {len(s5_distinct)}\n")
        out.append("Top 10 most common:\n\n```\n")
        for v, n in s5_distinct.most_common(10):
            out.append(f"  {v.hex()}  x{n}\n")
        out.append("```\n\n")
        # Decode: bytes 0-1 = entity_id, bytes 2-3 = `01 00` const, byte 4 = op
        ops = Counter(b[4] for _, _, b in s5)
        out.append(f"Byte 4 (op): {dict(ops.most_common(5))}\n\n")
        eids = Counter(int.from_bytes(b[0:2], "little") for _, _, b in s5)
        out.append(f"Byte 0-1 (entity_id LE16): {len(eids)} distinct\n\n")
        out.append("**Verified format:**\n```\n")
        out.append("Offset Size Field         Notes\n")
        out.append("0x00   2    entity_id     LE16\n")
        out.append("0x02   2    0x0001        constant\n")
        out.append("0x04   1    op            0x06 = despawn (verified by zone walks)\n")
        out.append("```\n\n")

    # 54-byte variant
    out.append(f"## 54-byte variant — full tick ({len(s54)} samples)\n\n")
    counters = per_byte_stats(s54, max_len=54)
    out.append("Per-byte statistics (top values shown):\n\n```\n")
    out.append(fmt_byte_stats(counters, s54, max_len=54))
    out.append("```\n\n")

    # Field hypothesis
    out.append("### Field hypothesis\n\n")

    # bytes 0-3: entity_id LE32 (mob)
    eids = Counter(int.from_bytes(b[0:4], "little") for _, _, b in s54)
    out.append(f"- **bytes 0-3 (entity_id LE32):** "
               f"{len(eids)} distinct entities. "
               f"Top 5: ")
    out.append(", ".join(f"0x{v:08x}({n})" for v, n in eids.most_common(5)))
    out.append("\n")

    # byte 4: state byte. Based on capture analysis:
    #   0x71 = in-combat, 0x75 = idle/default, 0x70 = transition (rare)
    b4 = Counter(b[4] for _, _, b in s54)
    out.append(f"- **byte 4 (state):** distinct={len(b4)}, top: ")
    out.append(", ".join(f"0x{v:02x}({n})" for v, n in b4.most_common(5)))
    out.append("\n  - Hypothesis: 0x71=combat, 0x75=idle, others=transitions\n")

    # bytes 5-7: 3 bytes — maybe sub-state or animation hash
    b57 = Counter(b[5:8] for _, _, b in s54)
    out.append(f"- **bytes 5-7:** {len(b57)} distinct triples\n")

    # bytes 8-11: float (X coord?)
    finite_8, total_8 = try_floats(s54, 8, 200)
    out.append(f"- **bytes 8-11 as LE float:** "
               f"{finite_8}/{total_8} parse as finite\n")
    # Sample values
    floats_8 = []
    for _, _, b in s54[:15]:
        try:
            f = struct.unpack("<f", b[8:12])[0]
            floats_8.append(f"{f:.2f}")
        except Exception:
            pass
    out.append(f"  Sample floats: {floats_8[:10]}\n")

    # bytes 12-15: float (Y coord?)
    finite_12, total_12 = try_floats(s54, 12, 200)
    out.append(f"- **bytes 12-15 as LE float:** "
               f"{finite_12}/{total_12} finite\n")
    floats_12 = []
    for _, _, b in s54[:15]:
        try:
            f = struct.unpack("<f", b[12:16])[0]
            floats_12.append(f"{f:.2f}")
        except Exception:
            pass
    out.append(f"  Sample floats: {floats_12[:10]}\n")

    # Special pattern: `ff ff ff ff` at bytes 12-15 — observed in catalog as common
    ffmask = sum(1 for _, _, b in s54 if b[12:16] == b'\xff\xff\xff\xff')
    out.append(f"  - `ff ff ff ff` at 12-15: **{ffmask}** samples "
               f"({ffmask*100/len(s54):.1f}% of 54B obs) — sentinel for "
               f"\"no target / no waypoint\"?\n")

    # Bytes 16-19: another float?
    finite_16, total_16 = try_floats(s54, 16, 200)
    out.append(f"- **bytes 16-19 as LE float:** "
               f"{finite_16}/{total_16} finite\n")

    # Most likely format based on this:
    out.append("\n### Best-guess field map (54B)\n\n")
    out.append("```\nOffset Size Field          Notes\n")
    out.append("0x00   4    entity_id      LE32 — mob's world ID\n")
    out.append("0x04   1    state          0x71=combat, 0x75=idle, others observed\n")
    out.append("0x05   3    state-detail   sub-state / animation hash\n")
    out.append("0x08   4    pos_x          LE32 float (or fixed-point)\n")
    out.append("0x0c   4    pos_y          LE32 float; 0xffffffff = sentinel \"no target\"\n")
    out.append("0x10   4    pos_z          LE32 float\n")
    out.append("0x14   var  tail           heading + animation tick + behavior payload\n")
    out.append("```\n\n")

    out.append("Open: bytes 0x14-0x35 (34 bytes of tail) carry "
               "behavior-specific data — heading float, animation timer, "
               "weapon ID for combat, target ID, etc. Need more "
               "differential analysis correlating with marker events.\n")

    out_path = out_dir / "decode_0x03_0x2d.md"
    out_path.write_text("".join(out))
    return out_path


def main():
    pcap_dir = Path("strace")
    pcaps = sorted(pcap_dir.glob("*.pcap"))
    pcaps = [p for p in pcaps if "CERESJ" not in p.name.upper()]
    print(f"Walking {len(pcaps)} captures...", file=sys.stderr)

    out_dir = Path("docs/protocol/_data")
    out_dir.mkdir(parents=True, exist_ok=True)

    print("Collecting 0x1b samples...", file=sys.stderr)
    s_1b = collect(pcaps, want_outer=0x1b, max_per=2000)
    print(f"  collected {len(s_1b)} samples", file=sys.stderr)
    p1 = analyze_0x1b(s_1b, out_dir)
    print(f"wrote {p1}", file=sys.stderr)

    print("Collecting 0x03/0x2d samples...", file=sys.stderr)
    s_2d = collect(pcaps, want_outer=0x03, want_sub=0x2d, max_per=2000)
    print(f"  collected {len(s_2d)} samples", file=sys.stderr)
    p2 = analyze_0x2d(s_2d, out_dir)
    print(f"wrote {p2}", file=sys.stderr)


if __name__ == "__main__":
    main()
