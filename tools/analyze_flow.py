#!/usr/bin/env python3
"""analyze_flow.py — produce an annotated event-by-event timeline
for a single retail pcap.

Output is a markdown file under docs/protocol/_data/timelines/
listing every decoded packet in chronological order, with markers
shown inline as section breaks. This is the raw evidence that
flow docs (mermaid sequence diagrams) are built from.

Multipart 0x03/0x07 fragments are folded into one logical-packet
event at the *first* fragment's timestamp; the row is annotated
with `multipart {idx}/{total}`.

Sub-tag decoding for 0x03/0x1f (GamePackets — cash carrier and
others) follows the documented sub-tag table where possible.

Usage:
  analyze_flow.py --pcap strace/X.pcap
  analyze_flow.py --pcap strace/X.pcap --window 0,30  # only t=[0,30]s
"""
from __future__ import annotations
import argparse, datetime, sys
from pathlib import Path
from collections import defaultdict

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")
burst_mod = import_module("parse-burst")
catalog = import_module("catalog_extract")

try:
    from scapy.all import PcapReader, IP, UDP, TCP
except ImportError:
    sys.exit("scapy missing — pip install --user --break-system-packages scapy")


# ── 0x1f sub-tag decoding (documented map) ───────────────────────────

GAMEPACKET_SUBTAGS = {
    # Sub-tag bytes within a 0x03/0x1f → 25 13 [txn LE2] [tag] [data] frame.
    # Reference: cash_and_falldamage_subops memory + PROTOCOL.md.
    0x04: ("cash", "u32 cash absolute"),
    0x0f: ("velocity_float", "f32 velocity (fall in-flight)"),
    0x10: ("world_z_float", "f32 world Z (fall impact)"),
    0x30: ("hp_status_burst", "HP/PSI/STA pool status (foreign entity)"),
    0x50: ("hp_delta", "HP delta (foreign entity)"),
}


def decode_1f_subtag(inner: bytes) -> str | None:
    """If inner bytes carry a 0x03/0x1f payload with the canonical
    transaction-tag wrapper [01 00 25 13 [txn LE2] [sub_tag] [data]],
    return a short human description."""
    if len(inner) < 8: return None
    if inner[0:4] != bytes([0x01, 0x00, 0x25, 0x13]):
        return None
    sub = inner[6]
    name, descr = GAMEPACKET_SUBTAGS.get(sub, (None, None))
    if name:
        return f"sub=0x{sub:02x} {name}"
    return f"sub=0x{sub:02x} (unknown)"


# ── Movement bitfield decode ────────────────────────────────────────

def decode_movement(inner: bytes) -> str:
    """`UDP C->S 0x20` payload. After 3 skipped bytes the type
    bitfield gates which fields follow."""
    if len(inner) < 4: return ""
    type_bits = inner[3]
    fields = []
    if type_bits & 0x01: fields.append("Y")
    if type_bits & 0x02: fields.append("Z")
    if type_bits & 0x04: fields.append("X")
    if type_bits & 0x08: fields.append("tilt")
    if type_bits & 0x10: fields.append("yaw")
    if type_bits & 0x20: fields.append("status")
    if type_bits & 0x40: fields.append("?")
    return f"type=0x{type_bits:02x}({','.join(fields)})"


def annotate(direction, key, inner: bytes) -> str:
    """Return a short human-readable annotation for one event."""
    if "0x03/0x1f" in key:
        sub = decode_1f_subtag(inner)
        if sub: return sub
    if direction == "C->S" and key.endswith("0x20"):
        return decode_movement(inner)
    if "0x03/0x22" in key and len(inner) >= 1:
        # Zoning sub-opcodes
        return f"sub=0x{inner[0]:02x}"
    return ""


# ── Marker reading ──────────────────────────────────────────────────

def parse_markers(path: Path):
    return catalog.parse_markers(path)


# ── Walk one pcap ───────────────────────────────────────────────────

def walk(pcap: Path, window=None):
    server_ip = catalog.find_server_ip(pcap)
    if not server_ip:
        return None, [], []

    markers_path = pcap.with_suffix(".markers")
    markers_tod = parse_markers(markers_path)

    capture_start = None
    capture_start_tod = None
    events = []
    multipart_state = {}
    last_ts = 0.0

    with PcapReader(str(pcap)) as pr:
        for pkt in pr:
            if IP not in pkt: continue
            ts = float(pkt.time)
            if capture_start is None:
                capture_start = ts
                dt = datetime.datetime.fromtimestamp(ts)
                capture_start_tod = (dt.hour * 3600 + dt.minute * 60
                                     + dt.second + dt.microsecond / 1e6)
            rel_ts = ts - capture_start
            last_ts = rel_ts

            if window:
                lo, hi = window
                if rel_ts < lo: continue
                if rel_ts > hi: break

            if pkt[IP].src == server_ip: direction = "S->C"
            elif pkt[IP].dst == server_ip: direction = "C->S"
            else: continue

            if UDP in pkt:
                payload = bytes(pkt[UDP].payload)
                if not payload: continue
                sport = int(pkt[UDP].sport); dport = int(pkt[UDP].dport)
                _walk_udp(events, direction, payload, rel_ts,
                           multipart_state, sport, dport)
            elif TCP in pkt:
                payload = bytes(pkt[TCP].payload)
                if not payload: continue
                sport = int(pkt[TCP].sport); dport = int(pkt[TCP].dport)
                _walk_tcp(events, direction, payload, rel_ts, sport, dport)

    # Re-baseline markers
    markers_rel = []
    if capture_start_tod is not None and markers_tod:
        for mtod, label in markers_tod:
            rel = mtod - capture_start_tod
            if rel < -3600: rel += 24 * 3600
            markers_rel.append((rel, label))

    return server_ip, events, sorted(markers_rel)


def _walk_udp(events, direction, payload, rel_ts, multipart_state,
               sport=0, dport=0):
    r = decrypt_mod.decrypt_wire_packet(payload)
    if not r:
        if payload:
            key = catalog._key_str(direction, "UDP", payload[0], None)
            events.append({
                "ts": rel_ts, "dir": direction, "key": key,
                "inner": payload[:32], "annot": "(handshake)",
                "size": len(payload),
            })
        return
    plain = r[0]
    parsed = burst_mod.parse_gamedata(plain)
    if not parsed:
        if plain:
            key = catalog._key_str(direction, "UDP",
                                   plain[0] if plain else 0, None)
            events.append({
                "ts": rel_ts, "dir": direction, "key": key,
                "inner": plain[:32], "annot": "(non-0x13)",
                "size": len(plain),
            })
        return
    for sub in parsed["subs"]:
        outer = sub["outer"]
        rt = sub.get("reliable_type")
        inner = (sub.get("inner_data") if rt is not None
                 else sub.get("data", b""))
        # Multipart fold
        if (outer, rt) in catalog.MULTIPART_CHANNELS:
            parsed_h = catalog._parse_multipart_header(inner)
            if parsed_h:
                fi, tf, disc, ts_size, ck, frag = parsed_h
                state_key = (direction, ck, disc)
                st = multipart_state.setdefault(state_key, {
                    "received": set(), "ts0": rel_ts,
                    "first_inner": inner[:32],
                    "tf": tf, "ts_size": ts_size,
                    "frag_count": 0,
                })
                st["received"].add(fi)
                st["frag_count"] += 1
                if len(st["received"]) >= st["tf"]:
                    key = catalog._key_str(direction, "UDP", outer, rt,
                                            disc=disc)
                    events.append({
                        "ts": st["ts0"], "dir": direction, "key": key,
                        "inner": st["first_inner"],
                        "annot": (f"multipart complete: "
                                  f"{st['frag_count']}/{tf} frags, "
                                  f"{ts_size}B body, chain=0x{ck:02x}"),
                        "size": ts_size,
                    })
                    del multipart_state[state_key]
                else:
                    # Show the first fragment as the chain start
                    if len(st["received"]) == 1:
                        events.append({
                            "ts": rel_ts, "dir": direction,
                            "key": catalog._key_str(direction, "UDP",
                                                     outer, rt, disc=disc),
                            "inner": inner[:32],
                            "annot": (f"multipart begin: 1/{tf} frags, "
                                      f"chain=0x{ck:02x}"),
                            "size": len(inner),
                        })
            continue
        key = catalog._key_str(direction, "UDP", outer, rt)
        events.append({
            "ts": rel_ts, "dir": direction, "key": key,
            "inner": inner[:32],
            "annot": annotate(direction, key, inner),
            "size": len(inner),
        })


def _walk_tcp(events, direction, payload, rel_ts, sport=0, dport=0):
    server_port = sport if direction == "S->C" else dport
    pos = 0
    while pos + 3 <= len(payload):
        if payload[pos] != 0xfe: break
        sub_len = payload[pos + 1] | (payload[pos + 2] << 8)
        pos += 3
        if pos + sub_len > len(payload): break
        sub = payload[pos:pos + sub_len]
        pos += sub_len
        if len(sub) < 2: continue
        opcode = (sub[0] << 8) | sub[1]
        key = catalog._key_str(direction, "TCP", opcode, None)
        events.append({
            "ts": rel_ts, "dir": direction, "key": key,
            "inner": sub[:32], "annot": f"port={server_port}",
            "size": len(sub),
        })


# ── Output ──────────────────────────────────────────────────────────

def write_timeline(pcap: Path, server_ip: str, events: list,
                    markers: list, out_dir: Path):
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / (pcap.stem + ".md")

    # Build a name lookup so the timeline shows human names
    def name_for(key):
        return catalog.lookup_name(key)

    # Merge markers and events into one chronological stream
    stream = []
    for t, label in markers:
        stream.append((t, "M", label))
    for e in events:
        stream.append((e["ts"], "E", e))
    stream.sort(key=lambda x: x[0])

    lines = []
    lines.append(f"# Timeline: `{pcap.name}`\n\n")
    lines.append(f"_Generated by `tools/analyze_flow.py` on "
                 f"{datetime.date.today().isoformat()}._\n\n")
    lines.append(f"- Server: `{server_ip}`\n")
    lines.append(f"- Events: **{len(events)}**\n")
    lines.append(f"- Markers: **{len(markers)}**\n")
    if events:
        lines.append(f"- Duration: {events[-1]['ts']:.1f}s\n")
    lines.append("\n")
    lines.append("Multipart `0x03/0x07` fragments are folded into a "
                 "single 'multipart complete' event at the first "
                 "fragment's timestamp. The 'multipart begin' marker "
                 "shows when reassembly started.\n\n")

    lines.append("| t (s) | Δ (ms) | Dir | Type | Name | Sz | Annotation |\n")
    lines.append("|---:|---:|---|---|---|---:|---|\n")

    last_t = 0.0
    for entry in stream:
        t = entry[0]
        kind = entry[1]
        if kind == "M":
            label = entry[2]
            lines.append(f"| **{t:.2f}** | | | | **▸ marker** | | "
                         f"**{label}** |\n")
            continue
        e = entry[2]
        delta_ms = int((t - last_t) * 1000)
        last_t = t
        sample = e["inner"].hex()
        if len(sample) > 32: sample = sample[:32] + "…"
        annot = e["annot"]
        if annot:
            annot = f"{annot} · `{sample}`"
        else:
            annot = f"`{sample}`"
        lines.append(f"| {t:.2f} | {delta_ms} | {e['dir']} | "
                     f"`{e['key'].split()[2]}` | {name_for(e['key'])} | "
                     f"{e['size']} | {annot} |\n")

    out.write_text("".join(lines))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pcap", type=Path, required=True)
    ap.add_argument("--out-dir", type=Path,
                    default=Path("docs/protocol/_data/timelines"))
    ap.add_argument("--window", type=str, default=None,
                    help="Restrict to t=[lo,hi] seconds, e.g. '0,30'")
    args = ap.parse_args()

    window = None
    if args.window:
        lo, hi = args.window.split(",")
        window = (float(lo), float(hi))

    server_ip, events, markers = walk(args.pcap, window=window)
    if not server_ip:
        sys.exit("no server peer found in pcap")
    out = write_timeline(args.pcap, server_ip, events, markers,
                          args.out_dir)
    print(f"wrote {out}")
    print(f"  {len(events)} events, {len(markers)} markers")


if __name__ == "__main__":
    main()
