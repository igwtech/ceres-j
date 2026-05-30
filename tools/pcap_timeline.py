#!/usr/bin/env python3
"""pcap_timeline.py — Fully-parsed packet-exchange timeline for NC2 pcaps.

Reads a Neocron 2 retail (or Ceres-J) pcap, decrypts every UDP datagram
with the LFSR/CFB wire cipher, unwraps the full protocol stack
(TCP 0xFE frames · UDP transport · 0x13 gamedata mux · 0x03 reliable ·
0x1f act-tags · 0x25 sub-tags · 0x03/0x07 multipart), names each packet
from the reverse-engineering catalog, and renders the exchange as a
Mermaid `sequenceDiagram` (or a plain-text timeline).

It is a *read-only* analysis tool. Parsing logic is shared with the
catalog: the cipher comes from `decrypt-retail.py` and the gamedata
sub-packet walk from `parse-burst.py`, so this stays in lock-step with
`tools/catalog_extract.py`.

Usage
-----
    python3 tools/pcap_timeline.py -i strace/NORMAN.pcap > timeline.md
    python3 tools/pcap_timeline.py -i cap.pcap --format text --max 400
    python3 tools/pcap_timeline.py -i cap.pcap --since 2 --until 8
    python3 tools/pcap_timeline.py -i cap.pcap --no-collapse --udp-only

Render the Mermaid output with mermaid-cli (mmdc) or paste into any
Markdown viewer that supports Mermaid.
"""
from __future__ import annotations

import argparse
import sys
from collections import Counter
from importlib import import_module
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
_dec = import_module("decrypt-retail")
_burst = import_module("parse-burst")

try:
    from scapy.all import PcapReader, IP, UDP, TCP
except ImportError:
    sys.exit("scapy missing — pip install --user --break-system-packages scapy")


# ─────────────────────────── name tables ────────────────────────────
# Mirror tools/catalog_extract.py so labels match the canonical catalog.

TCP_NAMES = {
    0x8000: "HandshakeB", 0x8001: "HandshakeA", 0x8003: "HandshakeC",
    0x8480: "Auth", 0x8482: "GetCharList/SelectServer",
    0x8381: "AuthAck", 0x8383: "ServerList", 0x8385: "CharList",
    0x8388: "PartyEvent", 0x8301: "ResumeAuth", 0x8303: "ClientKicked/Error",
    0x8305: "UDPServerData", 0x830c: "Location", 0x830d: "GameinfoReady",
    0x8317: "GMChat", 0x8318: "ChatFailed", 0x838f: "Keepalive838f",
    0x8386: "AuthFailure", 0x8737: "GetGamedata", 0x873a: "Gamedata",
    0x873c: "GetUDPConnection",
    0xa001: "SessionReady-S", 0xa002: "InteractionAck-S", 0xa003: "SessionReady-C",
}

# Raw UDP outer opcode (decrypted plaintext[0]) when NOT the 0x13 mux.
UDP_OUTER_NAMES = {
    0x01: "UDPHandshake", 0x03: "Reliable", 0x04: "UDPAlive",
    0x08: "UDPDisconnect", 0x0b: "CPing", 0x0c: "TimeSync",
    0x13: "Gamedata", 0x1b: "RawBroadcast1B", 0x1f: "RawGame1F",
    0x20: "RawMovement", 0x2a: "RequestInitBurst", 0x2f: "SelfStateHeartbeat",
    0x55: "SessionSig55", 0xf2: "SecurityChallenge",
}

# 0x03 reliable sub-types (matches parse-burst.RELIABLE_SUBTYPES + extras).
RELIABLE_NAMES = {
    0x01: "Resend/Ack", 0x07: "Multipart", 0x08: "ReliableAck/ZoningEnd",
    0x09: "ServerReliableAck", 0x0d: "TimeSync", 0x1b: "Group1B/PosUpdate",
    0x1f: "GamePackets", 0x22: "CharInfoReq", 0x23: "InfoResponse",
    0x24: "SessionInit24", 0x25: "PlayerInfo", 0x26: "RemoveWorldItem",
    0x27: "RequestWorldInfo", 0x28: "WorldInfo", 0x2b: "CityComDCB",
    0x2c: "StartPos/CharInfo", 0x2d: "NPCData", 0x2e: "Weather",
    0x2f: "UpdateModel", 0x30: "ShortPlayerInfo", 0x31: "RequestShortPlayer",
    0x32: "VehicleState", 0x33: "AckFF",
}

# 0x1f act-tag (canonical envelope `1f 01 00 <act_tag> ...`).
ACT_TAG_NAMES = {
    0x00: "NoOp", 0x01: "WeaponFire", 0x02: "EventMarker", 0x16: "Death",
    0x17: "Use/Sit", 0x18: "NPCScript", 0x19: "EntityIdReq", 0x1a: "Dialog",
    0x1b: "LocalChat", 0x1e: "ItemMove", 0x1f: "EquipHolster",
    0x20: "VehicleControl", 0x21: "Posture", 0x22: "Stand/InputBurst",
    0x25: "StateAck", 0x27: "CloseDialog", 0x29: "HackSuccess",
    0x2a: "MissionGrant", 0x2c: "DamageEvent", 0x3d: "FramePoll",
    0x3e: "Liveness", 0x4a: "VehicleExit", 0x4c: "Equip",
}

# 0x25 state-ack sub-tag (S->C `1f 01 00 25 <sub> ...`).
SUBTAG_25_NAMES = {
    0x04: "Cash", 0x06: "NpcAiState", 0x07: "BuffApplication", 0x0b: "Counter",
    0x13: "Confirmation", 0x15: "HpTick", 0x16: "VehicleTransition",
    0x19: "XP/PoolFull", 0x1a: "PoolPartialRatio", 0x1f: "PoolFull",
    0x22: "PoolMaxes", 0x23: "StartAck",
}

MULTIPART_DISC_NAMES = {0x01: "CharInfo", 0x02: "CharsysInfo"}


# ─────────────────────────── parsing ────────────────────────────────

class Event:
    __slots__ = ("ts", "direction", "label", "detail", "size", "transport", "milestone")

    def __init__(self, ts, direction, label, size, transport, detail="", milestone=None):
        self.ts = ts
        self.direction = direction          # "C2S" or "S2C"
        self.label = label                  # short canonical name
        self.detail = detail                # extra (seq, hex preview, ...)
        self.size = size                    # bytes of this logical packet
        self.transport = transport          # "TCP" | "UDP"
        self.milestone = milestone          # phase milestone name or None


def find_server_ip(pcap: Path):
    """Server = peer on TCP 7000/12000, else busiest non-local IP."""
    gameport = Counter()
    nonlocal_ = Counter()
    with PcapReader(str(pcap)) as pr:
        for i, pkt in enumerate(pr):
            if i > 20000:
                break
            if IP not in pkt:
                continue
            if TCP in pkt:
                for prt, ip in ((pkt[TCP].sport, pkt[IP].src),
                                (pkt[TCP].dport, pkt[IP].dst)):
                    if prt in (7000, 12000):
                        gameport[ip] += 1
            for ip in (pkt[IP].src, pkt[IP].dst):
                if not ip.startswith(("127.", "192.168.", "172.", "10.",
                                      "169.254.", "224.", "255.")):
                    nonlocal_[ip] += 1
    if gameport:
        return gameport.most_common(1)[0][0]
    return nonlocal_.most_common(1)[0][0] if nonlocal_ else None


def decode_1f(inner: bytes) -> str:
    """Decode a 0x1f GamePackets inner body (`01 00 <act_tag> ...`)."""
    if len(inner) < 3:
        return "1f"
    act = inner[2]
    name = ACT_TAG_NAMES.get(act, f"0x{act:02x}")
    if act == 0x25 and len(inner) >= 4:
        sub = inner[3]
        return f"1f/StateAck/{SUBTAG_25_NAMES.get(sub, f'0x{sub:02x}')}"
    return f"1f/{name}"


def reliable_label(rt: int, inner: bytes) -> tuple[str, str]:
    """Return (label, detail) for a 0x03 reliable sub-packet."""
    base = RELIABLE_NAMES.get(rt, f"0x{rt:02x}")
    if rt == 0x1f:
        return f"03/{decode_1f(inner)}", ""
    if rt == 0x07:  # multipart fragment header
        hdr = _burst_multipart_header(inner)
        if hdr:
            fi, tf, disc, total, _ck = hdr
            dn = MULTIPART_DISC_NAMES.get(disc, f"disc0x{disc:02x}")
            return f"03/Multipart/{dn}", f"frag {fi+1}/{tf}, {total}B total"
        return "03/Multipart", ""
    return f"03/{base}", ""


def _burst_multipart_header(inner: bytes):
    """[frag_idx LE2][total_frags LE4][disc 1][data_size LE3][chain_key 1]."""
    if len(inner) < 11:
        return None
    fi = int.from_bytes(inner[0:2], "little")
    tf = int.from_bytes(inner[2:6], "little")
    disc = inner[6]
    total = int.from_bytes(inner[7:10], "little")
    ck = inner[10]
    if tf == 0 or tf > 1024:
        return None
    return fi, tf, disc, total, ck


# milestones (first-occurrence phase markers), keyed by label substring.
MILESTONES = [
    ("Auth", "Auth"), ("AuthAck", "AuthAck"), ("CharList", "CharList"),
    ("ResumeAuth", "ResumeAuth"), ("UDPServerData", "UDPServerData"),
    ("Location", "Location"), ("UDPHandshake", "UDP handshake begins"),
    ("UDPAlive", "UDP session alive"), ("Multipart/CharInfo", "CharInfo delivery"),
    ("StartPos", "Start position"), ("ReliableAck/ZoningEnd", "Zone entry / ZoningEnd"),
    ("RawMovement", "Movement stream"), ("Movement", "Movement stream"),
]


def parse_tcp(payload: bytes, ts, direction):
    """Walk 0xFE-framed sub-packets in one TCP segment → list[Event]."""
    out = []
    pos = 0
    if not payload:
        return out
    # Some captures (or mid-stream segments) have no 0xFE; treat whole
    # segment as one frame keyed on the leading BE16 opcode.
    if payload[0] != 0xfe:
        if len(payload) >= 2:
            opc = (payload[0] << 8) | payload[1]
            out.append(Event(ts, direction, f"TCP {TCP_NAMES.get(opc, f'0x{opc:04x}')}",
                             len(payload), "TCP", detail=f"0x{opc:04x}"))
        return out
    while pos + 3 <= len(payload):
        if payload[pos] != 0xfe:
            break
        sub_len = payload[pos + 1] | (payload[pos + 2] << 8)
        pos += 3
        if pos + sub_len > len(payload):
            break
        sub = payload[pos:pos + sub_len]
        pos += sub_len
        if len(sub) < 2:
            continue
        opc = (sub[0] << 8) | sub[1]
        name = TCP_NAMES.get(opc, f"0x{opc:04x}")
        out.append(Event(ts, direction, f"TCP {name}", sub_len, "TCP",
                         detail=f"0x{opc:04x}"))
    return out


def parse_udp(payload: bytes, ts, direction):
    """Decrypt + unwrap one UDP datagram → list[Event]."""
    out = []
    r = _dec.decrypt_wire_packet(payload)
    if not r:
        b0 = payload[0] if payload else 0
        out.append(Event(ts, direction,
                         f"UDP {UDP_OUTER_NAMES.get(b0, f'0x{b0:02x}')}",
                         len(payload), "UDP", detail=f"raw 0x{b0:02x}"))
        return out
    plain = r[0]
    if not plain:
        return out

    # Not the 0x13 gamedata mux → classify by plaintext[0].
    if plain[0] != 0x13:
        b0 = plain[0]
        name = UDP_OUTER_NAMES.get(b0, f"0x{b0:02x}")
        detail = ""
        # Raw 0x03 reliable carried directly (no 0x13 wrapper).
        if b0 == 0x03 and len(plain) >= 4:
            rt = plain[3]
            lab, detail = reliable_label(rt, plain[4:])
            out.append(Event(ts, direction, f"UDP {lab}", len(plain), "UDP", detail))
            return out
        out.append(Event(ts, direction, f"UDP {name}", len(plain), "UDP", detail))
        return out

    parsed = _burst.parse_gamedata(plain)
    if not parsed or not parsed["subs"]:
        out.append(Event(ts, direction, "UDP Gamedata(empty)", len(plain), "UDP"))
        return out

    for s in parsed["subs"]:
        outer = s["outer"]
        if outer == 0x03 and "reliable_type" in s:
            lab, detail = reliable_label(s["reliable_type"], s.get("inner_data", b""))
            seq = s.get("reliable_seq")
            det = (f"seq={seq} " + detail).strip() if seq is not None else detail
            out.append(Event(ts, direction, lab, s["len"], "UDP", det))
        else:
            nm = UDP_OUTER_NAMES.get(outer, f"0x{outer:02x}")
            out.append(Event(ts, direction, nm, s["len"], "UDP"))
    return out


def collect_events(pcap: Path, server_ip: str, proto: str):
    events = []
    t0 = None
    with PcapReader(str(pcap)) as pr:
        for pkt in pr:
            if IP not in pkt or server_ip not in (pkt[IP].src, pkt[IP].dst):
                continue
            ts = float(pkt.time)
            if t0 is None:
                t0 = ts
            rel = ts - t0
            direction = "S2C" if pkt[IP].src == server_ip else "C2S"
            if TCP in pkt and proto in ("both", "tcp"):
                pl = bytes(pkt[TCP].payload)
                if pl:
                    events.extend(parse_tcp(pl, rel, direction))
            elif UDP in pkt and proto in ("both", "udp"):
                pl = bytes(pkt[UDP].payload)
                if pl:
                    events.extend(parse_udp(pl, rel, direction))
    events.sort(key=lambda e: e.ts)
    return events


# ─────────────────────────── collapsing ─────────────────────────────

class Group:
    __slots__ = ("direction", "label", "count", "t_start", "t_end",
                 "bytes", "sample_detail", "transport", "milestone")

    def __init__(self, e: Event):
        self.direction = e.direction
        self.label = e.label
        self.transport = e.transport
        self.count = 1
        self.t_start = e.ts
        self.t_end = e.ts
        self.bytes = e.size
        self.sample_detail = e.detail
        self.milestone = e.milestone

    def add(self, e: Event):
        self.count += 1
        self.t_end = e.ts
        self.bytes += e.size


def collapse(events, enabled: bool):
    """Merge consecutive same-(direction,label) events into runs."""
    if not enabled:
        return [Group(e) for e in events]
    groups = []
    cur = None
    for e in events:
        if cur and cur.direction == e.direction and cur.label == e.label:
            cur.add(e)
        else:
            if cur:
                groups.append(cur)
            cur = Group(e)
    if cur:
        groups.append(cur)
    return groups


def assign_milestones(groups):
    """Tag the first group whose label matches each milestone."""
    seen = set()
    for g in groups:
        for needle, phase in MILESTONES:
            if needle in seen:
                continue
            if needle in g.label:
                g.milestone = phase
                seen.add(needle)
                break
    return groups


# ─────────────────────────── rendering ──────────────────────────────

def _san(text: str) -> str:
    """Strip characters Mermaid's sequence parser rejects in messages."""
    for a, b in (("(", " "), (")", " "), ("[", " "), ("]", " "),
                 ("{", " "), ("}", " "), (";", ","), (":", "="), ("#", "no")):
        text = text.replace(a, b)
    return " ".join(text.split())


def render_mermaid(groups, meta, max_events):
    lines = ["```mermaid", "sequenceDiagram", "    autonumber",
             "    participant C as Client", "    participant S as Server"]
    lines.append(f"    Note over C,S: {_san(meta)}")
    shown = groups[:max_events] if max_events else groups
    for g in shown:
        if g.milestone:
            lines.append(f"    Note over C,S: ⟦ {_san(g.milestone)} ⟧ @ t+{g.t_start:.2f}s")
        arrow = "->>" if g.direction == "C2S" else "-->>"
        src, dst = ("C", "S") if g.direction == "C2S" else ("S", "C")
        if g.count > 1:
            span = g.t_end - g.t_start
            msg = (f"t+{g.t_start:.2f} {g.label} ×{g.count} "
                   f"over {span:.2f}s {g.bytes}B")
        else:
            extra = f" {g.sample_detail}" if g.sample_detail else ""
            msg = f"t+{g.t_start:.3f} {g.label}{extra} {g.bytes}B"
        lines.append(f"    {src}{arrow}{dst}: {_san(msg)}")
    if max_events and len(groups) > max_events:
        lines.append(f"    Note over C,S: … {len(groups) - max_events} "
                     f"more groups truncated, raise --max")
    lines.append("```")
    return "\n".join(lines)


def render_text(groups, meta, max_events):
    out = [meta, "=" * len(meta), ""]
    shown = groups[:max_events] if max_events else groups
    for g in shown:
        if g.milestone:
            out.append(f"\n──── {g.milestone}  (t+{g.t_start:.2f}s) ────")
        arrow = "C→S" if g.direction == "C2S" else "S→C"
        if g.count > 1:
            span = g.t_end - g.t_start
            out.append(f"  t+{g.t_start:8.3f}  {arrow}  {g.label:<34} "
                       f"×{g.count:<5d} over {span:6.2f}s  {g.bytes}B")
        else:
            extra = f"  ({g.sample_detail})" if g.sample_detail else ""
            out.append(f"  t+{g.t_start:8.3f}  {arrow}  {g.label:<34} "
                       f"{g.bytes}B{extra}")
    if max_events and len(groups) > max_events:
        out.append(f"\n  … {len(groups) - max_events} more groups (raise --max)")
    return "\n".join(out)


def render_summary(groups):
    by_label = Counter()
    for g in groups:
        by_label[(g.direction, g.label)] += g.count
    out = ["", "── packet-type totals ──"]
    for (d, lab), n in by_label.most_common():
        arrow = "C→S" if d == "C2S" else "S→C"
        out.append(f"  {n:7d}  {arrow}  {lab}")
    return "\n".join(out)


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--input", "-i", type=Path, required=True)
    ap.add_argument("--server-ip", default=None, help="override server IP autodetect")
    ap.add_argument("--proto", choices=("both", "tcp", "udp"), default="both")
    ap.add_argument("--format", choices=("mermaid", "text", "both"), default="mermaid")
    ap.add_argument("--max", type=int, default=300,
                    help="max timeline rows/arrows (0 = all). Default 300.")
    ap.add_argument("--no-collapse", action="store_true",
                    help="show every packet (do not merge consecutive runs)")
    ap.add_argument("--since", type=float, default=None, help="start time (s, relative)")
    ap.add_argument("--until", type=float, default=None, help="end time (s, relative)")
    ap.add_argument("--summary", action="store_true",
                    help="append a packet-type totals table")
    args = ap.parse_args(argv[1:])

    if not args.input.exists():
        sys.exit(f"no such pcap: {args.input}")

    server_ip = args.server_ip or find_server_ip(args.input)
    if not server_ip:
        sys.exit("could not determine server IP; pass --server-ip")
    print(f"[info] server IP = {server_ip}", file=sys.stderr)

    events = collect_events(args.input, server_ip, args.proto)
    if args.since is not None:
        events = [e for e in events if e.ts >= args.since]
    if args.until is not None:
        events = [e for e in events if e.ts <= args.until]
    print(f"[info] {len(events)} logical packets parsed", file=sys.stderr)

    groups = collapse(events, enabled=not args.no_collapse)
    groups = assign_milestones(groups)

    span = (events[-1].ts - events[0].ts) if events else 0.0
    meta = (f"{args.input.name} · server {server_ip} · {args.proto} · "
            f"{len(events)} packets · {len(groups)} groups · {span:.1f}s")

    if args.format in ("mermaid", "both"):
        print(render_mermaid(groups, meta, args.max))
    if args.format in ("text", "both"):
        print(render_text(groups, meta, args.max))
    if args.summary:
        print(render_summary(groups))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
