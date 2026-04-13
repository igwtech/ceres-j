#!/usr/bin/env python3
"""
analyze-retail-packets.py — statistical test of NC2 retail S→C UDP packets
against a known-plaintext local baseline.

Goal: definitively answer whether retail server→client UDP traffic is
(a) encrypted or (b) plaintext with unknown opcodes. The user's working
hypothesis is (b) — that NC2 from 2004 didn't encrypt wire traffic and
the ~3500 bytes of retail sync data we see in captures are just new
opcodes the Ceres-J / irata code never learned about.

Input: two strace log files produced by tools/debug-client-strace.sh.
  - /tmp/nc2_strace_RETAIL.log : session against the retail server
  - /tmp/nc2_strace.log        : session against the local Ceres-J server

Each file is a full strace -f -e trace=recvmsg,sendmsg,... of
neocronclient.exe running under wine. We extract UDP recvmsg syscall
results (bytes the kernel delivered to the client) and run statistical
tests on the collected packets.

Tests:
  1. Size distribution per side
  2. Overall byte-value entropy (bits/byte)
  3. Per-position entropy for bytes 0-15
  4. First-byte histogram (how many unique first bytes?)
  5. Same-size consistency — if multiple packets share an exact size,
     do their first few bytes match? Plaintext with a common opcode
     should show identical leading bytes; per-packet encryption
     should show all different.
  6. Null-byte and ASCII-printable frequency

Interpretation:
  - Entropy ~8.0 bits/byte, first-byte uniformly distributed,
    0% null-byte consistency → strong encryption signal
  - Entropy 4-6 bits/byte, first byte clustered on a handful of
    opcodes, samesize consistency high → plaintext signal
  - In between → inconclusive (the usual case for short traces)

Usage:
  python3 tools/analyze-retail-packets.py \
      /tmp/nc2_strace_RETAIL.log /tmp/nc2_strace.log
"""

from __future__ import annotations

import math
import re
import sys
from collections import Counter
from pathlib import Path


# strace -xx renders printable ASCII as raw chars and non-printable as \xHH.
# Our iov_base strings come from that output. This decoder handles both
# printable-literal and \xHH escape forms plus the common C escapes.
_RECVMSG_RE = re.compile(
    r'recvmsg\(\d+<UDP[^>]*>.*?iov_base="((?:[^"\\]|\\.)*)".*?= (\d+)$',
    re.MULTILINE,
)


def decode_strace_bytes(s: str) -> bytes:
    out = bytearray()
    i = 0
    n = len(s)
    while i < n:
        ch = s[i]
        if ch == "\\" and i + 1 < n:
            nxt = s[i + 1]
            if nxt == "x" and i + 3 < n:
                try:
                    out.append(int(s[i + 2 : i + 4], 16))
                    i += 4
                    continue
                except ValueError:
                    pass
            if nxt == "n":
                out.append(0x0A); i += 2; continue
            if nxt == "r":
                out.append(0x0D); i += 2; continue
            if nxt == "t":
                out.append(0x09); i += 2; continue
            if nxt == "0":
                out.append(0x00); i += 2; continue
            if nxt == "\\":
                out.append(0x5C); i += 2; continue
            if nxt == '"':
                out.append(0x22); i += 2; continue
            # Unknown escape — emit the backslash literally and continue
            out.append(ord(ch))
            i += 1
            continue
        out.append(ord(ch) & 0xFF)
        i += 1
    return bytes(out)


def extract_udp_recv(path: Path) -> list[tuple[int, bytes]]:
    """Return (announced_size, actual_bytes) pairs for every UDP recvmsg in the log."""
    if not path.exists():
        return []
    with path.open("r", errors="replace") as f:
        content = f.read()
    out: list[tuple[int, bytes]] = []
    for m in _RECVMSG_RE.finditer(content):
        data = decode_strace_bytes(m.group(1))
        size = int(m.group(2))
        if data:
            out.append((size, data))
    return out


def shannon_entropy(counts: Counter) -> float:
    total = sum(counts.values())
    if total <= 0:
        return 0.0
    return -sum((c / total) * math.log2(c / total) for c in counts.values() if c > 0)


def analyze(label: str, packets: list[tuple[int, bytes]]) -> dict:
    print(f"\n=== {label}: {len(packets)} packets ===")
    if not packets:
        print("  (no packets to analyze)")
        return {}

    sizes = Counter(sz for sz, _ in packets)
    top_sizes = sorted(sizes.items(), key=lambda kv: -kv[1])[:10]
    print(f"Size distribution (top 10): {top_sizes}")

    all_bytes = b"".join(data for _, data in packets)
    total = len(all_bytes)
    print(f"Total bytes received: {total}")

    null_pct = sum(1 for b in all_bytes if b == 0) / total * 100
    ascii_pct = sum(1 for b in all_bytes if 0x20 <= b < 0x7F) / total * 100
    overall_ent = shannon_entropy(Counter(all_bytes))
    print(
        "Global stats:\n"
        f"  null-byte frequency : {null_pct:6.2f}%  "
        "(encrypted ~0.4%, plaintext often 10-30%)"
    )
    print(
        f"  ASCII printable      : {ascii_pct:6.2f}%  "
        "(encrypted ~37%, plaintext often >50%)"
    )
    print(
        f"  overall entropy      : {overall_ent:6.3f} bits/byte  "
        "(encrypted ~7.99, plaintext ~4-6)"
    )

    # First-byte histogram
    first_bytes = Counter(d[0] for _, d in packets)
    print(
        f"\nFirst-byte uniqueness: {len(first_bytes)} distinct values across "
        f"{len(packets)} packets "
        f"({len(first_bytes) / len(packets) * 100:.0f}% unique)"
    )
    print("First-byte top 10:")
    for byte, cnt in sorted(first_bytes.items(), key=lambda kv: -kv[1])[:10]:
        print(f"  0x{byte:02x}: {cnt}")

    # Per-position entropy
    max_len = max(len(d) for _, d in packets)
    print(f"\nPer-position entropy (first {min(16, max_len)} bytes):")
    for pos in range(min(16, max_len)):
        col = Counter(d[pos] for _, d in packets if len(d) > pos)
        if col:
            print(
                f"  pos {pos:2d}: entropy={shannon_entropy(col):5.3f}  "
                f"unique={len(col):3d}/{sum(col.values())}"
            )

    # Same-size consistency
    print("\nSame-size consistency (groups of >=3 identical-size packets):")
    groups = {}
    for size, data in packets:
        groups.setdefault(size, []).append(data)
    interesting = sorted(
        ((s, g) for s, g in groups.items() if len(g) >= 3),
        key=lambda kv: -len(kv[1]),
    )
    if not interesting:
        print("  (no size has 3+ packets — consistency test skipped)")
    for size, group in interesting[:6]:
        b0 = Counter(d[0] for d in group)
        b1 = Counter(d[1] for d in group if len(d) > 1)
        b2 = Counter(d[2] for d in group if len(d) > 2)
        print(
            f"  size={size:4d}B  count={len(group):3d}  "
            f"unique(b0)={len(b0)}  unique(b1)={len(b1)}  unique(b2)={len(b2)}"
        )

    return {
        "count": len(packets),
        "total_bytes": total,
        "null_pct": null_pct,
        "ascii_pct": ascii_pct,
        "entropy": overall_ent,
        "first_byte_unique": len(first_bytes),
        "first_byte_top": sorted(first_bytes.items(), key=lambda kv: -kv[1])[:10],
    }


def verdict(retail: dict, local: dict) -> None:
    print("\n=== VERDICT ===")
    if not retail or not local:
        print("  Insufficient data. Cannot render a verdict.")
        return
    rfp = retail["first_byte_unique"] / retail["count"]
    lfp = local["first_byte_unique"] / local["count"]
    print(
        f"  Retail first-byte uniqueness: {rfp:.0%}"
        f"   Local first-byte uniqueness: {lfp:.0%}"
    )
    print(
        f"  Retail entropy:  {retail['entropy']:5.3f} bits/byte"
        f"   Local entropy:   {local['entropy']:5.3f} bits/byte"
    )
    print(
        f"  Retail null%:    {retail['null_pct']:5.2f}%"
        f"   Local null%:     {local['null_pct']:5.2f}%"
    )

    # Heuristic classification
    # Plaintext: low first-byte uniqueness, moderate entropy, high null%
    # Encrypted: high first-byte uniqueness, high entropy, very low null%
    retail_encrypted_score = 0
    if rfp > 0.60:
        retail_encrypted_score += 1
    if retail["entropy"] > 7.5:
        retail_encrypted_score += 1
    if retail["null_pct"] < 2.0:
        retail_encrypted_score += 1

    retail_plain_score = 0
    if rfp < 0.30:
        retail_plain_score += 1
    if retail["entropy"] < 6.5:
        retail_plain_score += 1
    if retail["null_pct"] > 5.0:
        retail_plain_score += 1

    print()
    if retail_encrypted_score >= 2 and retail_plain_score == 0:
        print("  → RETAIL S→C IS ENCRYPTED (confidence: high)")
    elif retail_plain_score >= 2 and retail_encrypted_score == 0:
        print("  → RETAIL S→C IS PLAINTEXT (confidence: high)")
    elif retail_encrypted_score > retail_plain_score:
        print("  → retail S→C is likely encrypted (confidence: medium)")
    elif retail_plain_score > retail_encrypted_score:
        print("  → retail S→C is likely plaintext (confidence: medium)")
    else:
        print("  → inconclusive — scores are split; need larger sample")


def main(argv: list[str]) -> int:
    retail_path = Path(argv[1]) if len(argv) > 1 else Path("/tmp/nc2_strace_RETAIL.log")
    local_path = Path(argv[2]) if len(argv) > 2 else Path("/tmp/nc2_strace.log")

    retail_pkts = extract_udp_recv(retail_path)
    local_pkts = extract_udp_recv(local_path)

    if not retail_pkts:
        print(f"[warn] no UDP recvmsg packets found in {retail_path}")
    if not local_pkts:
        print(f"[warn] no UDP recvmsg packets found in {local_path}")

    retail_stats = analyze(f"RETAIL ({retail_path})", retail_pkts)
    local_stats = analyze(f"LOCAL  ({local_path})", local_pkts)
    verdict(retail_stats, local_stats)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
