#!/usr/bin/env python3
"""decode_coverage.py — measure how much of a retail capture we can
decode at 100%, by-the-bytes.

Walks every packet in a pcap and classifies each into one of:

  - FULL    — every byte's role is documented (we could re-emit it)
  - PARTIAL — header / first-N bytes understood, body opaque
  - HEADER  — only the wrapper/opcode known, payload meaning unknown
  - UNKNOWN — mystery packet (rare cipher echoes, etc.)

Output: stdout summary table + by-packet-type breakdown.

The decode-confidence map is hand-curated from the protocol docs
(OPCODE_STRUCTURE.md, SUBTAGS.md, CLIENT_LUA_BRIDGE.md, per-packet
docs). Entries are conservative — when in doubt, downgrade.
"""
from __future__ import annotations
import sys, argparse
from pathlib import Path
from collections import Counter, defaultdict

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")
burst_mod = import_module("parse-burst")
catalog = import_module("catalog_extract")

try:
    from scapy.all import PcapReader, IP, UDP, TCP
except ImportError:
    sys.exit("scapy missing")


# ─── Decode-confidence map ───────────────────────────────────────────
#
# Score 4 = FULL, 3 = PARTIAL, 2 = HEADER, 1 = UNKNOWN.
#
# Conservative: PARTIAL means we know the wrapper + some fields but
# not every byte. FULL means we could re-serialize the packet from
# parsed fields without a hex sample. HEADER means we know what
# opcode/wrapper this is but the body is opaque to us.

# TCP opcodes (16-bit big-endian)
TCP_DECODE = {
    # FULL — fixed-format control packets, every byte known
    0x8001: 4,   # HandshakeA — 3B fixed `80 01 66`
    0x8000: 4,   # HandshakeB — 3B fixed `80 00 78`
    0x8003: 4,   # HandshakeC — 3B fixed `80 03 68`
    0x830d: 4,   # GameinfoReady — 4B `83 0d 00 00`
    0xa001: 4,   # SessionReady-S — 2B
    0xa002: 4,   # ?-S 2B (we know it's an interaction marker, format = constant)
    0xa003: 4,   # SessionReady-C — 2B
    0x873a: 4,   # Gamedata ack — 2B
    0x8737: 4,   # GetGamedata — 6B fixed `87 37 00 00 00 00`
    0x873c: 4,   # GetUDPConnection — 6B fixed (no-op on retail)
    0x8386: 4,   # Char-create ack — 7B `83 86 01 00 00 00 [status]` — field decoded
    0x838f: 4,   # Interaction marker — 7B `83 8f 00 00 00 00 00` — fixed

    # PARTIAL — wrapper + some fields known
    0x8480: 3,   # Auth — 65B; we know the account-block constant + token but not all fields
    0x8482: 3,   # GetCharList/CharCreate — 30/39/110B; discriminator field decoded, body partial
    0x8301: 3,   # AuthB — 53B; some fields
    0x8381: 3,   # AuthAck — 31B; token field known
    0x8385: 3,   # CharList — 209-225B; format mostly understood (multi-slot)
    0x8305: 3,   # UDPServerData — 28B; UDP IP/port/token fields
    0x830c: 3,   # Location — 27-38B; zone_id LE4 + zone-name string
    0x8383: 2,   # InfoServer reply — 26B; structure unclear
    0x8317: 4,   # CHAT REFLECTION — sender_uid+name+channel+message — fully decoded 2026-05-03
    0x8318: 2,   # NEW from PARTY captures — 7B status update
    0x8388: 2,   # NEW from PARTY captures — 18-23B player-info push
    0x8386: 4,   # Char-create/delete ack with status byte + optional ASCII error message — fully decoded
}

# UDP raw outers (1 byte)
UDP_RAW_DECODE = {
    0x00: 3,   # inventory channel 12B — op enum known, trailer 3B unknown
    0x01: 3,   # UDP handshake start (10B) / inventory probe (3B) — handshake body unknown bytes
    0x02: 3,   # inventory ack — TLV format mostly known
    0x03: 4,   # reliable wrapper itself — fully understood
    0x04: 3,   # UDP handshake reply 7B — body bytes 3-5 are seed-related but unconfirmed
    0x07: 3,   # raw 1012B — same as multipart fragment without 0x03 wrapping
    0x08: 4,   # disconnect — 1B
    0x0b: 4,   # CPing — 5B/9B; tick bytes known
    0x0c: 4,   # TimeSync — 5B; tick bytes known
    0x0d: 2,   # 10B mystery wrapper (single-sub gamedata variant)
    0x0f: 2,   # 10B mystery wrapper
    0x11: 2,   # 10B mystery wrapper
    0x13: 4,   # gamedata wrapper (not seen as raw outer in retail though)
    0x1b: 4,   # entity broadcast 19B — FULLY DECODED 2026-05-03 (every byte mapped)
    0x1d: 2,   # 10B mystery wrapper
    0x1f: 3,   # raw 14B pool burst — fields known (HP/PSI/STA/status)
    0x20: 4,   # Movement — bitfield + payload fully decoded for C→S
    0x2a: 3,   # RequestPos — 16B; format known but field meanings sparse
    0x2d: 3,   # 6B raw — trade-request hypothesis (only LE-blocked sample)
    0x32: 4,   # NPC dialogue 9B — entity_id + payload, format known
    0x3c: 2,   # 12B inventory metadata — header known, body unknown
    # rare upper opcodes (cipher echoes etc.)
    0x05: 1, 0x06: 1, 0x09: 2, 0x0a: 1, 0x25: 1, 0x27: 3, 0x2c: 2,
    0x31: 1, 0x33: 2, 0x3a: 1, 0x3e: 1, 0x44: 1, 0x45: 1, 0x55: 1,
    0x58: 1, 0x6a: 1, 0x8a: 1, 0x92: 1, 0x9e: 1, 0xbf: 1, 0xc4: 1,
    0xc5: 1, 0xda: 1, 0xef: 1, 0xf5: 1, 0xf6: 1,
}

# UDP reliable sub-opcodes (after 0x03 wrapper)
UDP_REL_DECODE = {
    0x00: 3,   # script-list upload (ASCII path strings) — first frame decoded
    0x01: 3,   # Resend / ack
    0x07: 4,   # Multipart — full per-fragment header decoded
    0x08: 4,   # ZoningEnd — 2B
    0x09: 2,   # ?
    0x0d: 4,   # TimeSync — 12B
    0x1b: 3,   # PosUpdate (reliable) — 11B; fields known partially
    0x1f: 4,   # GamePackets — extensively tag-decoded (104K samples)
    0x22: 4,   # CharInfo zoning — sub 0x0d/0x03/0x06 all decoded
    0x23: 4,   # InfoResponse — 4 variants all decoded (zone-info, session-info, transition-meta, post-transition)
    0x24: 3,   # Reliable/?  — 2B "01 00", appears at world entry; meaning unknown
    0x25: 3,   # PlayerInfo — 65B; partial decode (entity_id at offset 1)
    0x26: 4,   # RemoveWorldItem — 4B (entity_id LE4)
    0x27: 4,   # RequestWorldInfo — 4B
    0x28: 3,   # WorldInfo — 13-56B; entity-spawn fields partially known
    0x2b: 3,   # CityCom DCB RPC — ASCII method names decoded; body field-by-field opaque
    0x2c: 4,   # StartPos / single CharInfo — sections decoded
    0x2d: 3,   # NPCData — 54B; mob-state byte + entity_id known, internal TLV partial
    0x2e: 4,   # Weather — 13B; format known
    0x2f: 3,   # UpdateModel — variable; format partially known
    0x30: 3,   # ShortPlayerInfo — 17-22B; format partial
    0x31: 3,   # RequestShortPlayer — 6B
    0x32: 4,   # NPC dialogue reliable — 8B; entity_id LE4 + payload
    0x33: 4,   # `ff 00` 2B fixed terminator
}

# Multipart discriminators
MULTIPART_DECODE = {
    0x01: 4,   # CharInfo — body sections fully decoded
    0x02: 3,   # CharsysInfo — TLV format known, but it's a dead code path
    0x03: 1,   # unknown disc
    0x04: 1,   # unknown disc
    0x38: 2,   # unknown disc — body prelude `22 02 04` instead of `22 02 01`
}


# ─── Walker ──────────────────────────────────────────────────────────

def confidence_score(transport, direction, opcode_path):
    """Return (score, explanation).
    score: 4=FULL, 3=PARTIAL, 2=HEADER, 1=UNKNOWN."""
    if transport == "TCP":
        op = opcode_path[0]
        s = TCP_DECODE.get(op, 1)
        return s, f"TCP opcode 0x{op:04x}"
    # UDP
    if len(opcode_path) == 1:
        op = opcode_path[0]
        s = UDP_RAW_DECODE.get(op, 1)
        return s, f"UDP raw 0x{op:02x}"
    if len(opcode_path) == 2:
        outer, sub = opcode_path
        if outer == 0x03:
            s = UDP_REL_DECODE.get(sub, 1)
            return s, f"UDP 0x03/0x{sub:02x}"
        # Non-0x03 outer
        s = UDP_RAW_DECODE.get(outer, 1)
        return s, f"UDP 0x{outer:02x}/?"
    if len(opcode_path) == 3:
        outer, sub, disc = opcode_path
        if outer == 0x03 and sub == 0x07:
            s = MULTIPART_DECODE.get(disc, 1)
            return s, f"Multipart disc 0x{disc:02x}"
    return 1, "unknown"


def walk(pcap):
    server_ip = catalog.find_server_ip(pcap)
    if not server_ip:
        sys.exit("no server peer")
    by_score = Counter()           # 1..4 → packet count
    bytes_by_score = Counter()     # 1..4 → byte count
    by_type_score = defaultdict(Counter)  # type_key → score → count
    by_type_bytes = defaultdict(Counter)
    multipart_state = {}

    with PcapReader(str(pcap)) as pr:
        for pkt in pr:
            if IP not in pkt: continue
            if pkt[IP].src == server_ip: direction = "S->C"
            elif pkt[IP].dst == server_ip: direction = "C->S"
            else: continue

            if UDP in pkt:
                payload = bytes(pkt[UDP].payload)
                if not payload: continue
                _walk_udp(payload, direction, by_score, bytes_by_score,
                           by_type_score, by_type_bytes, multipart_state)
            elif TCP in pkt:
                payload = bytes(pkt[TCP].payload)
                if not payload: continue
                _walk_tcp(payload, direction, by_score, bytes_by_score,
                           by_type_score, by_type_bytes)
    return by_score, bytes_by_score, by_type_score, by_type_bytes


def _record(by_score, bytes_by_score, by_type_score, by_type_bytes,
            type_key, score, n_bytes):
    by_score[score] += 1
    bytes_by_score[score] += n_bytes
    by_type_score[type_key][score] += 1
    by_type_bytes[type_key][score] += n_bytes


def _walk_udp(payload, direction, by_score, bytes_by_score,
               by_type_score, by_type_bytes, mp_state):
    r = decrypt_mod.decrypt_wire_packet(payload)
    if not r:
        # Single-byte handshake or cipher-echo packet
        op = payload[0] if payload else 0
        score, _ = confidence_score("UDP", direction, [op])
        type_key = f"UDP {direction} 0x{op:02x}"
        _record(by_score, bytes_by_score, by_type_score, by_type_bytes,
                type_key, score, len(payload))
        return
    plain = r[0]
    parsed = burst_mod.parse_gamedata(plain)
    if not parsed:
        op = plain[0] if plain else 0
        score, _ = confidence_score("UDP", direction, [op])
        type_key = f"UDP {direction} 0x{op:02x}"
        _record(by_score, bytes_by_score, by_type_score, by_type_bytes,
                type_key, score, len(plain))
        return
    for sub in parsed["subs"]:
        outer = sub["outer"]
        rt = sub.get("reliable_type")
        inner = (sub.get("inner_data") if rt is not None
                 else sub.get("data", b""))
        if (outer, rt) in catalog.MULTIPART_CHANNELS:
            parsed_h = catalog._parse_multipart_header(inner)
            if parsed_h:
                fi, tf, disc, ts_size, ck, frag = parsed_h
                state_key = (direction, ck, disc)
                st = mp_state.setdefault(state_key, {
                    "received": set(), "size": 0, "tf": tf, "ts": ts_size,
                })
                st["received"].add(fi)
                st["size"] += len(inner)
                if len(st["received"]) >= st["tf"]:
                    score, _ = confidence_score("UDP", direction,
                                                 [outer, rt, disc])
                    type_key = f"UDP {direction} 0x{outer:02x}/0x{rt:02x}/0x{disc:02x}"
                    _record(by_score, bytes_by_score, by_type_score,
                            by_type_bytes, type_key, score, st["size"])
                    del mp_state[state_key]
            continue
        path = [outer, rt] if rt is not None else [outer]
        score, _ = confidence_score("UDP", direction, path)
        if rt is not None:
            type_key = f"UDP {direction} 0x{outer:02x}/0x{rt:02x}"
        else:
            type_key = f"UDP {direction} 0x{outer:02x}"
        _record(by_score, bytes_by_score, by_type_score, by_type_bytes,
                type_key, score, len(inner))


def _walk_tcp(payload, direction, by_score, bytes_by_score,
               by_type_score, by_type_bytes):
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
        score, _ = confidence_score("TCP", direction, [opcode])
        type_key = f"TCP {direction} 0x{opcode:04x}"
        _record(by_score, bytes_by_score, by_type_score, by_type_bytes,
                type_key, score, len(sub))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pcap", type=Path, required=True)
    args = ap.parse_args()

    by_score, bytes_by_score, by_type_score, by_type_bytes = walk(args.pcap)

    SCORE_NAMES = {4: "FULL", 3: "PARTIAL", 2: "HEADER ONLY", 1: "UNKNOWN"}

    total_pkts = sum(by_score.values())
    total_bytes = sum(bytes_by_score.values())

    print(f"\n=== Decode coverage for {args.pcap.name} ===\n")
    print(f"{'Score':<14} {'Packets':>10} {'%':>6}  {'Bytes':>12} {'%':>6}")
    for s in (4, 3, 2, 1):
        n = by_score[s]
        b = bytes_by_score[s]
        pp = n / total_pkts * 100 if total_pkts else 0
        pb = b / total_bytes * 100 if total_bytes else 0
        print(f"{SCORE_NAMES[s]:<14} {n:>10,} {pp:>5.1f}%  {b:>12,} {pb:>5.1f}%")
    print(f"{'TOTAL':<14} {total_pkts:>10,}        {total_bytes:>12,}")

    # Top contributors per category
    print("\n--- Top FULL-decode types ---")
    full_by_type = sorted(
        ((t, by_type_score[t][4], by_type_bytes[t][4]) for t in by_type_score
         if by_type_score[t][4] > 0),
        key=lambda x: -x[2])
    for t, n, b in full_by_type[:12]:
        print(f"  {t:<32} {n:>8,} pkts  {b:>10,}B")

    print("\n--- Top PARTIAL-decode types ---")
    partial_by_type = sorted(
        ((t, by_type_score[t][3], by_type_bytes[t][3]) for t in by_type_score
         if by_type_score[t][3] > 0),
        key=lambda x: -x[2])
    for t, n, b in partial_by_type[:12]:
        print(f"  {t:<32} {n:>8,} pkts  {b:>10,}B")

    print("\n--- HEADER-only / UNKNOWN types ---")
    header_unk = sorted(
        ((t, by_type_score[t][2] + by_type_score[t][1],
          by_type_bytes[t][2] + by_type_bytes[t][1]) for t in by_type_score
         if (by_type_score[t][2] + by_type_score[t][1]) > 0),
        key=lambda x: -x[2])
    for t, n, b in header_unk[:12]:
        print(f"  {t:<32} {n:>8,} pkts  {b:>10,}B")


if __name__ == "__main__":
    main()
