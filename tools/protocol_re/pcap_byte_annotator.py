#!/usr/bin/env python3
"""Strict byte-level annotator for NC2 retail pcap sessions.

Reads a pcap, decrypts UDP, reassembles TCP, reframes reliable
sub-packets, and dispatches each unit to a registered decoder. Any
byte not claimed by a decoder is marked UNKNOWN. The output is the
ground truth for "% of session bytes named" — the metric we use to
measure RE progress.

Usage:
    python3 ceres-j/tools/protocol_re/pcap_byte_annotator.py \
        --pcap PATH/file.pcap \
        [--jsonl out.jsonl] \
        [--server-ip A.B.C.D] \
        [--top-unknowns 20]

Outputs:
    stdout: aggregated session report
    --jsonl: per-packet annotations (line-delimited JSON), each line:
        {
          "pkt_id": int, "ts": float,
          "transport": "tcp"|"udp", "direction": "c2s"|"s2c",
          "framing": "tcp"|"udp_raw"|"udp_reliable_sub",
          "opcode": "0x830c" | "0x03/0x1f/0x25" | ...,
          "total_len": int,
          "named_bytes": int,
          "unknown_spans": [[start, len], ...],
          "fields": [{offset, length, name, kind, value, confidence}, ...]
        }

Design rules:
  - The annotator does NOT guess. It only claims fields a decoder
    registered. Everything else is UNKNOWN.
  - Field spans must not overlap. The annotator validates this and
    drops any decoder output that violates it (with a warning).
  - Outer wrappers (FE-frame, cipher-frame, reliable wrapper) are
    accounted for separately as "framing_overhead" so they don't
    inflate the named-byte count for the inner payload.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from dataclasses import asdict
from pathlib import Path
from typing import Iterable, List, Optional, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from importlib import import_module

decrypt_mod = import_module("decrypt-retail")
burst_mod = import_module("parse-burst")

from protocol_re.decoders import (
    FieldSpan, dispatch_tcp, dispatch_udp_raw,
    dispatch_udp_reliable_inner,
)


# ─── pcap walking ──────────────────────────────────────────────────────

def iter_pcap(path: Path, server_ip: Optional[str]
              ) -> Iterable[Tuple[float, str, str, bytes]]:
    """Yield (timestamp, transport, direction, raw_payload) tuples.

    Direction is "c2s" if dst==server_ip else "s2c". Skips packets
    with empty payloads.
    """
    try:
        from scapy.all import PcapReader, IP, UDP, TCP, Raw
    except ImportError:
        sys.exit("scapy missing — pip install scapy")

    # Auto-detect server IP if not provided: highest-traffic non-local
    if server_ip is None:
        ip_bytes = Counter()
        with PcapReader(str(path)) as pr:
            for i, p in enumerate(pr):
                if i > 5000:
                    break
                if IP not in p:
                    continue
                ip = p[IP]
                src, dst = ip.src, ip.dst
                # Skip obvious private ranges as candidate "server"
                for cand in (src, dst):
                    if cand.startswith(("10.", "192.168.",
                                        "127.", "169.254.")):
                        continue
                    if cand.startswith(tuple(f"172.{x}."
                                             for x in range(16, 32))):
                        continue
                    if Raw in p:
                        ip_bytes[cand] += len(bytes(p[Raw]))
        if ip_bytes:
            server_ip = ip_bytes.most_common(1)[0][0]
            print(f"[autodetect] server_ip = {server_ip}",
                  file=sys.stderr)
        else:
            sys.exit("could not autodetect server IP — pass --server-ip")

    with PcapReader(str(path)) as pr:
        for p in pr:
            if IP not in p or Raw not in p:
                continue
            ip = p[IP]
            direction = ("c2s" if ip.dst == server_ip
                         else ("s2c" if ip.src == server_ip
                               else None))
            if direction is None:
                continue
            ts = float(getattr(p, "time", 0.0) or 0.0)
            if UDP in p:
                yield ts, "udp", direction, bytes(p[Raw])
            elif TCP in p:
                yield ts, "tcp", direction, bytes(p[Raw])


# ─── TCP stream reassembly (per-direction FE-frame splitter) ───────────

class TcpReassembler:
    """Reassemble FE-framed packets from raw TCP segment payloads.

    Wire format: `fe <len:LE16> <body:len>`. Buffers per direction;
    yields complete frames as (body_bytes,). Discards garbage if
    re-sync needed.
    """
    def __init__(self):
        self.buf = {"c2s": bytearray(), "s2c": bytearray()}

    def feed(self, direction: str, segment: bytes) -> List[bytes]:
        b = self.buf[direction]
        b.extend(segment)
        out = []
        while len(b) >= 3:
            if b[0] != 0xfe:
                # Re-sync by skipping a byte. Anything before the
                # next 0xfe is corrupt or stale.
                nxt = b.find(b"\xfe", 1)
                if nxt < 0:
                    b.clear()
                    return out
                del b[:nxt]
                continue
            body_len = b[1] | (b[2] << 8)
            if len(b) < 3 + body_len:
                break
            body = bytes(b[3:3 + body_len])
            out.append(body)
            del b[:3 + body_len]
        return out


# ─── UDP frame walker ──────────────────────────────────────────────────

def decrypt_udp(wire: bytes) -> Optional[bytes]:
    """Run LFSR+CFB decrypt via parse-burst.decrypt_wire."""
    try:
        return burst_mod.decrypt_wire(wire)
    except Exception:
        return None


# ─── Annotation core ───────────────────────────────────────────────────

def _spans_to_named_bytes(spans: List[FieldSpan]) -> int:
    return sum(s.length for s in spans)


def _compute_unknown_spans(total_len: int,
                            spans: List[FieldSpan]
                            ) -> List[Tuple[int, int]]:
    """Return list of (start, length) for unclaimed regions."""
    covered = sorted((s.offset, s.offset + s.length) for s in spans)
    out, cur = [], 0
    for (a, b) in covered:
        if a > cur:
            out.append((cur, a - cur))
        cur = max(cur, b)
    if cur < total_len:
        out.append((cur, total_len - cur))
    return out


def _validate_spans(spans: List[FieldSpan], total_len: int
                     ) -> List[FieldSpan]:
    """Drop overlapping/out-of-bounds spans (caller bug-trap)."""
    good = []
    for s in sorted(spans, key=lambda x: x.offset):
        if s.offset < 0 or s.offset + s.length > total_len:
            print(f"  WARN: span out of bounds: {s}",
                  file=sys.stderr)
            continue
        if good and s.offset < good[-1].offset + good[-1].length:
            print(f"  WARN: overlapping span dropped: {s}",
                  file=sys.stderr)
            continue
        good.append(s)
    return good


# ─── Per-packet annotation ─────────────────────────────────────────────

def annotate_tcp(pkt_id: int, ts: float, direction: str,
                  frame_body: bytes) -> dict:
    """Annotate a single FE-framed TCP packet body.

    `frame_body` excludes the 3-byte FE envelope (the framing
    overhead is accounted for at the session level).
    """
    if len(frame_body) < 2:
        opcode_label = "(short)"
        opcode = 0
    else:
        opcode = (frame_body[0] << 8) | frame_body[1]
        opcode_label = f"0x{opcode:04x}"

    spans = dispatch_tcp(direction, opcode, frame_body)
    spans = _validate_spans(spans, len(frame_body))
    unknown_spans = _compute_unknown_spans(len(frame_body), spans)

    return {
        "pkt_id": pkt_id,
        "ts": ts,
        "transport": "tcp",
        "direction": direction,
        "framing": "tcp",
        "opcode": opcode_label,
        "total_len": len(frame_body),
        "named_bytes": _spans_to_named_bytes(spans),
        "unknown_spans": [list(t) for t in unknown_spans],
        "fields": [asdict(s) for s in spans],
    }


def annotate_udp_raw(pkt_id: int, ts: float, direction: str,
                      plain: bytes) -> List[dict]:
    """Annotate a decrypted UDP datagram.

    If it's a 0x13 reliable wrapper, peel sub-packets and annotate
    each separately. Otherwise treat the whole datagram as a raw
    UDP packet.
    """
    if not plain:
        return []
    if plain[0] == 0x13:
        return annotate_udp_reliable(pkt_id, ts, direction, plain)

    spans = dispatch_udp_raw(direction, plain)
    spans = _validate_spans(spans, len(plain))
    unknown_spans = _compute_unknown_spans(len(plain), spans)
    return [{
        "pkt_id": pkt_id,
        "ts": ts,
        "transport": "udp",
        "direction": direction,
        "framing": "udp_raw",
        "opcode": f"0x{plain[0]:02x}",
        "total_len": len(plain),
        "named_bytes": _spans_to_named_bytes(spans),
        "unknown_spans": [list(t) for t in unknown_spans],
        "fields": [asdict(s) for s in spans],
    }]


def annotate_udp_reliable(pkt_id: int, ts: float, direction: str,
                            plain: bytes) -> List[dict]:
    """Parse 0x13 wrapper → list of sub-packet annotations.

    Each sub-packet's "body" is the bytes AFTER its length prefix,
    starting with the sub-packet's outer-type byte.
    """
    parsed = burst_mod.parse_gamedata(plain)
    if parsed is None:
        return [{
            "pkt_id": pkt_id, "ts": ts,
            "transport": "udp", "direction": direction,
            "framing": "udp_wrapper_parse_failed",
            "opcode": "0x13?", "total_len": len(plain),
            "named_bytes": 0,
            "unknown_spans": [[0, len(plain)]],
            "fields": [],
        }]

    out = []
    for sub in parsed["subs"]:
        sub_data = bytes(sub["data"])
        outer = sub["outer"]
        if outer == 0x03 and "reliable_type" in sub:
            rel_type = sub["reliable_type"]
            opcode_label = f"0x03/0x{rel_type:02x}"
            # Strip the 4-byte reliable header `[03][seq:LE16][sub]`
            # and annotate the inner body.
            inner = bytes(sub["inner_data"])
            spans = dispatch_udp_reliable_inner(
                direction, rel_type, inner)
            spans = _validate_spans(spans, len(inner))
            unknown_spans = _compute_unknown_spans(
                len(inner), spans)
            out.append({
                "pkt_id": pkt_id, "ts": ts,
                "transport": "udp", "direction": direction,
                "framing": "udp_reliable_sub",
                "opcode": opcode_label,
                "total_len": len(inner),
                "named_bytes": _spans_to_named_bytes(spans),
                "unknown_spans": [list(t) for t in unknown_spans],
                "fields": [asdict(s) for s in spans],
            })
        else:
            spans = dispatch_udp_raw(direction, sub_data)
            spans = _validate_spans(spans, len(sub_data))
            unknown_spans = _compute_unknown_spans(
                len(sub_data), spans)
            out.append({
                "pkt_id": pkt_id, "ts": ts,
                "transport": "udp", "direction": direction,
                "framing": "udp_wrapped_sub",
                "opcode": f"0x{outer:02x}",
                "total_len": len(sub_data),
                "named_bytes": _spans_to_named_bytes(spans),
                "unknown_spans": [list(t) for t in unknown_spans],
                "fields": [asdict(s) for s in spans],
            })
    return out


# ─── Session-level aggregation ─────────────────────────────────────────

def aggregate(records: List[dict]) -> dict:
    """Reduce per-packet records to a session summary."""
    total_len = sum(r["total_len"] for r in records)
    total_named = sum(r["named_bytes"] for r in records)
    per_op_len = defaultdict(int)
    per_op_named = defaultdict(int)
    per_op_count = Counter()
    for r in records:
        key = (r["direction"], r["framing"], r["opcode"])
        per_op_len[key] += r["total_len"]
        per_op_named[key] += r["named_bytes"]
        per_op_count[key] += 1

    per_op = []
    for key, length in per_op_len.items():
        named = per_op_named[key]
        per_op.append({
            "direction": key[0],
            "framing": key[1],
            "opcode": key[2],
            "count": per_op_count[key],
            "total_bytes": length,
            "named_bytes": named,
            "unknown_bytes": length - named,
            "pct_named": (100.0 * named / length) if length else 0,
        })
    per_op.sort(key=lambda x: x["unknown_bytes"], reverse=True)

    return {
        "total_packets": len(records),
        "total_bytes": total_len,
        "named_bytes": total_named,
        "unknown_bytes": total_len - total_named,
        "pct_named": (100.0 * total_named / total_len)
                      if total_len else 0,
        "per_opcode": per_op,
    }


# ─── Main ──────────────────────────────────────────────────────────────

def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--pcap", type=Path, required=True)
    ap.add_argument("--server-ip", default=None,
                    help="Override server-IP autodetect")
    ap.add_argument("--jsonl", type=Path, default=None,
                    help="Write per-packet annotations as JSONL")
    ap.add_argument("--top-unknowns", type=int, default=20,
                    help="How many top-unknown opcodes to print")
    args = ap.parse_args(argv)

    if not args.pcap.is_file():
        sys.exit(f"no such file: {args.pcap}")

    tcp = TcpReassembler()
    records: List[dict] = []
    pkt_id = 0
    skipped_decrypt = 0

    for ts, transport, direction, raw in iter_pcap(
            args.pcap, args.server_ip):
        if transport == "tcp":
            for frame_body in tcp.feed(direction, raw):
                pkt_id += 1
                records.append(annotate_tcp(
                    pkt_id, ts, direction, frame_body))
        elif transport == "udp":
            plain = decrypt_udp(raw)
            if plain is None:
                skipped_decrypt += 1
                continue
            pkt_id += 1
            recs = annotate_udp_raw(pkt_id, ts, direction, plain)
            records.extend(recs)

    if args.jsonl is not None:
        with args.jsonl.open("w") as f:
            for r in records:
                f.write(json.dumps(r) + "\n")
        print(f"wrote {len(records)} records to {args.jsonl}",
              file=sys.stderr)

    summary = aggregate(records)
    print(f"=== {args.pcap.name} ===")
    print(f"Total packets/sub-packets: {summary['total_packets']:,}")
    print(f"Total bytes:               {summary['total_bytes']:,}")
    print(f"Named bytes:               {summary['named_bytes']:,} "
          f"({summary['pct_named']:.1f}%)")
    print(f"Unknown bytes:             {summary['unknown_bytes']:,} "
          f"({100 - summary['pct_named']:.1f}%)")
    if skipped_decrypt:
        print(f"UDP decrypt failures:      {skipped_decrypt:,}")

    print(f"\n--- Top {args.top_unknowns} opcodes by unknown bytes ---")
    print(f"{'dir':>4} {'framing':>20} {'opcode':>16} "
          f"{'count':>8} {'total':>10} {'unknown':>10} {'%named':>7}")
    for row in summary["per_opcode"][:args.top_unknowns]:
        print(f"{row['direction']:>4} {row['framing']:>20} "
              f"{row['opcode']:>16} {row['count']:>8,} "
              f"{row['total_bytes']:>10,} "
              f"{row['unknown_bytes']:>10,} "
              f"{row['pct_named']:>6.1f}%")


if __name__ == "__main__":
    main()
