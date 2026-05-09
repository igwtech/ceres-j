#!/usr/bin/env python3
"""extract-1b-broadcasts.py — Pull all 0x1b raw + 0x03/0x1b reliable
packets from retail pcaps with byte-level field analysis.

Walks each pcap, decrypts UDP, and extracts only the position-broadcast
sub-packets we care about for task #160:

  - raw 0x1b              (top-level 0x1b inside a plaintext frame)
  - 0x03/<seq>/<sub>=0x1b reliable PosUpdate
  - inside 0x13 outer wrapper, both above

For each match, emits one CSV row plus an annotated hex dump grouped
by size, so we can see which fields are constant vs varying.

Usage:
  ./tools/extract-1b-broadcasts.py strace/*.pcap --out /tmp/1b_corpus.txt
"""

from __future__ import annotations
import argparse, struct, sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")

try:
    from scapy.all import PcapReader, IP, UDP
except ImportError:
    sys.exit("scapy missing — install with: pip install --user --break-system-packages scapy")


def is_local(ip: str) -> bool:
    return ip.startswith("127.") or ip.startswith("192.168.") or ip.startswith("172.")


def detect_server(path: Path) -> str | None:
    peer_count: Counter[str] = Counter()
    with PcapReader(str(path)) as pr:
        for i, pkt in enumerate(pr):
            if i > 5000:
                break
            if IP not in pkt:
                continue
            for ip in (pkt[IP].src, pkt[IP].dst):
                if not is_local(ip):
                    peer_count[ip] += 1
    return peer_count.most_common(1)[0][0] if peer_count else None


def strip_outer(plain: bytes) -> tuple[int, bytes]:
    """Strip the 0x13 outer frame if present. Returns (consumed, inner).
    7B header: [0x13][counter LE2][counter+sk LE2][len LE2]."""
    if not plain:
        return 0, b""
    if plain[0] == 0x13 and len(plain) >= 7:
        ln = struct.unpack_from("<H", plain, 5)[0]
        if 7 + ln <= len(plain):
            return 7, plain[7:7 + ln]
    return 0, plain


def find_1b(inner: bytes):
    """Yield (kind, offset, body) for every 0x1b broadcast in `inner`.
    kind is 'raw_1b' or 'rel_03_1b'. body is the bytes from 0x1b inclusive."""
    if not inner:
        return
    b0 = inner[0]
    if b0 == 0x1b:
        yield ("raw_1b", 0, inner)
    elif b0 == 0x03 and len(inner) >= 4:
        # [0x03][seq LE2][sub_op][...]
        sub = inner[3]
        if sub == 0x1b:
            yield ("rel_03_1b", 0, inner[3:])


def annotate_19b(body: bytes) -> str:
    if len(body) != 19:
        return ""
    parts = []
    parts.append(f"[0]op={body[0]:02x}")
    obj_lo = body[1]
    obj_hi = body[2]
    parts.append(f"[1..2]obj_id={obj_lo | (obj_hi << 8):04x}")
    parts.append(f"[3..4]={body[3]:02x}{body[4]:02x}")
    parts.append(f"[5]={body[5]:02x}")
    y = struct.unpack_from("<H", body, 6)[0]
    z = struct.unpack_from("<H", body, 8)[0]
    x = struct.unpack_from("<H", body, 10)[0]
    parts.append(f"[6..7]Y_raw={y:04x}({y - 32000:+d})")
    parts.append(f"[8..9]Z_raw={z:04x}({z - 32000:+d})")
    parts.append(f"[10..11]X_raw={x:04x}({x - 32000:+d})")
    parts.append(f"[12]orient={body[12]:02x}")
    v1 = struct.unpack_from("<I", body, 13)[0]
    parts.append(f"[13..16]v1=0x{v1:08x}")
    parts.append(f"[17..18]trail={body[17]:02x}{body[18]:02x}")
    return "  ".join(parts)


def annotate_03_1b(body: bytes) -> str:
    """body starts at the 0x1b sub-op byte (post-seq)."""
    if not body:
        return ""
    parts = [f"[0]subop={body[0]:02x}"]
    if len(body) >= 3:
        eid = struct.unpack_from("<H", body, 1)[0]
        parts.append(f"[1..2]entity={eid:04x}")
    if len(body) >= 5:
        parts.append(f"[3..4]={body[3]:02x}{body[4]:02x}")
    if len(body) >= 6:
        parts.append(f"[5]marker={body[5]:02x}")
    if len(body) >= 10:
        parts.append(f"[6..9]={body[6]:02x}{body[7]:02x}{body[8]:02x}{body[9]:02x}")
    if len(body) >= 11:
        parts.append(f"[10]={body[10]:02x}")
    if len(body) >= 12:
        parts.append(f"[11]={body[11]:02x}")
    return "  ".join(parts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pcaps", nargs="+", type=Path)
    ap.add_argument("--out", type=Path, default=None,
                    help="Optional output file (defaults to stdout)")
    ap.add_argument("--per-pcap-limit", type=int, default=20,
                    help="Cap samples per kind per pcap to avoid drowning")
    args = ap.parse_args()

    out = args.out.open("w") if args.out else sys.stdout

    raw_19_sizes: Counter[int] = Counter()
    rel_03_sizes: Counter[int] = Counter()
    raw_constants: dict[int, Counter[int]] = defaultdict(Counter)
    rel_constants: dict[int, Counter[int]] = defaultdict(Counter)

    for pcap in args.pcaps:
        if not pcap.exists():
            print(f"# missing: {pcap}", file=out)
            continue
        srv = detect_server(pcap)
        if not srv:
            continue
        print(f"\n========= {pcap.name} (server={srv}) =========",
              file=out)
        raw_count = 0
        rel_count = 0
        with PcapReader(str(pcap)) as pr:
            for pkt in pr:
                if IP not in pkt or UDP not in pkt:
                    continue
                # S→C only
                if pkt[IP].src != srv:
                    continue
                payload = bytes(pkt[UDP].payload)
                if not payload:
                    continue
                result = decrypt_mod.decrypt_wire_packet(payload)
                if not result:
                    continue
                plain, _ = result
                # Optionally strip outer 0x13
                _, inner = strip_outer(plain)
                # Walk the inner for 0x1b matches at top level
                for kind, _off, body in find_1b(inner):
                    if kind == "raw_1b":
                        if raw_count >= args.per_pcap_limit:
                            continue
                        raw_count += 1
                        raw_19_sizes[len(body)] += 1
                        if len(body) == 19:
                            for i in range(19):
                                raw_constants[i][body[i]] += 1
                        print(f"  raw_1b len={len(body):3d} "
                              f"hex={body.hex()}", file=out)
                        if len(body) == 19:
                            print(f"           {annotate_19b(body)}",
                                  file=out)
                    else:
                        if rel_count >= args.per_pcap_limit:
                            continue
                        rel_count += 1
                        rel_03_sizes[len(body)] += 1
                        if len(body) >= 11:
                            for i in range(min(len(body), 12)):
                                rel_constants[i][body[i]] += 1
                        print(f"  rel_03_1b len={len(body):3d} "
                              f"hex={body.hex()}", file=out)
                        print(f"             {annotate_03_1b(body)}",
                              file=out)
                # Also scan for 0x1b appearing further into a multi-
                # sub-packet 0x13 frame: a 0x13 frame can carry many
                # sub-packets concatenated. We only check top-level
                # for now — that covers retail's predominant single-
                # sub-packet shape.

    print("\n========== AGGREGATE ==========", file=out)
    print(f"raw_1b sizes: {dict(raw_19_sizes)}", file=out)
    print(f"rel_03_1b sizes: {dict(rel_03_sizes)}", file=out)
    if raw_19_sizes.get(19, 0) > 0:
        print("\nraw_1b(19) byte constants (top 3 per offset):",
              file=out)
        for off in range(19):
            top = raw_constants[off].most_common(3)
            total = sum(raw_constants[off].values())
            line = ", ".join(f"{v:02x}({c}/{total})" for v, c in top)
            print(f"  [{off:2d}] {line}", file=out)
    if any(rel_03_sizes.values()):
        print("\nrel_03_1b byte constants (top 3 per offset, first 12):",
              file=out)
        for off in range(12):
            top = rel_constants[off].most_common(3)
            total = sum(rel_constants[off].values())
            if total == 0:
                continue
            line = ", ".join(f"{v:02x}({c}/{total})" for v, c in top)
            print(f"  [{off:2d}] {line}", file=out)

    if out is not sys.stdout:
        out.close()
        print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
