"""Per-packet field decoders for the strict byte annotator.

Each decoder is a function `decode(body: bytes) -> list[FieldSpan]`
that returns the bytes IT knows how to name. Anything not returned
is automatically marked UNKNOWN by the annotator — silence here is
NOT a license to skip bytes. If you don't know, don't claim.

Rules for adding a decoder:
1. Min 2 distinct retail captures showing the same field at the
   same offset. One sample is a hypothesis; ledger it, don't decode.
2. If a field width changes across samples, the decoder must read
   the actual width from the wire (length prefix, terminator, etc.)
   — never hard-code a maximum.
3. Constants are fields too (they carry "this is the right opcode"
   information). Label them `magic_const_<hex>` so the report
   surfaces them.
4. When you're unsure between two layouts, mark the WHOLE region
   UNKNOWN and add a hypothesis row to the ledger. Don't half-decode.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Tuple


@dataclass
class FieldSpan:
    """A named region of `length` bytes starting at `offset`.

    `kind` is the parse class: `u8`, `u16le`, `u32le`, `f32le`,
    `asciiz`, `bytes`, `magic`. `value` is the parsed value for
    scalar kinds, or the raw bytes for `bytes` kind, or None.
    `confidence` is one of `verified` (≥2-sample evidence),
    `hypothesis` (1-sample guess — still gets annotated but
    flagged), or `unknown` (only used internally by the
    annotator's auto-fill).
    """
    offset: int
    length: int
    name: str
    kind: str
    value: object = None
    confidence: str = "verified"


# Decoder signature: (direction, transport, body) -> [FieldSpan]
Decoder = Callable[[str, str, bytes], List[FieldSpan]]


# ─── Field-span helpers (keep decoders short) ───────────────────────────

def _u8(off: int, name: str, data: bytes) -> FieldSpan:
    return FieldSpan(off, 1, name, "u8", data[off])


def _u16le(off: int, name: str, data: bytes) -> FieldSpan:
    return FieldSpan(off, 2, name, "u16le",
                     int.from_bytes(data[off:off+2], "little"))


def _u32le(off: int, name: str, data: bytes) -> FieldSpan:
    return FieldSpan(off, 4, name, "u32le",
                     int.from_bytes(data[off:off+4], "little"))


def _f32le(off: int, name: str, data: bytes) -> FieldSpan:
    import struct
    return FieldSpan(off, 4, name, "f32le",
                     struct.unpack("<f", data[off:off+4])[0])


def _asciiz(off: int, name: str, data: bytes,
            max_len: Optional[int] = None) -> Optional[FieldSpan]:
    """ASCII string + null terminator. Returns None if no NUL found."""
    end = data.find(b"\x00", off,
                    off + max_len if max_len else None)
    if end < 0:
        return None
    s = data[off:end].decode("ascii", errors="replace")
    return FieldSpan(off, end - off + 1, name, "asciiz", s)


def _magic(off: int, name: str, data: bytes, length: int) -> FieldSpan:
    return FieldSpan(off, length, name, "magic",
                     data[off:off+length].hex())


# ─── TCP S→C decoders ──────────────────────────────────────────────────

def tcp_s2c_8001(direction, transport, body):
    # HandshakeA — fixed 3B `80 01 66`.
    if len(body) != 3 or body[:2] != b"\x80\x01":
        return []
    return [
        _magic(0, "opcode_8001", body, 2),
        _u8(2, "handshake_a_const_0x66", body),
    ]


def tcp_s2c_8003(direction, transport, body):
    # HandshakeC — fixed 3B `80 03 68`.
    if len(body) != 3 or body[:2] != b"\x80\x03":
        return []
    return [
        _magic(0, "opcode_8003", body, 2),
        _u8(2, "handshake_c_const_0x68", body),
    ]


def tcp_s2c_830c(direction, transport, body):
    # Location — `83 0c <zoneId:LE32> <reserved:LE32=0>
    # <spawnIdx:LE32> <ASCII\0>`. Verified across multiple
    # retail captures (RETRY3 2026-05-24 + earlier).
    if len(body) < 15 or body[:2] != b"\x83\x0c":
        return []
    out = [
        _magic(0, "opcode_830c", body, 2),
        _u32le(2, "zone_id", body),
        _u32le(6, "reserved_zero", body),
        _u32le(10, "spawn_idx", body),
    ]
    name = _asciiz(14, "world_path", body)
    if name is not None:
        out.append(name)
    return out


def tcp_s2c_830d(direction, transport, body):
    # LoadingBegin — fixed 4B `83 0d 00 00`.
    if len(body) != 4 or body[:2] != b"\x83\x0d":
        return []
    return [
        _magic(0, "opcode_830d", body, 2),
        _magic(2, "loading_begin_pad", body, 2),
    ]


def tcp_s2c_838f(direction, transport, body):
    # InteractionCommit / TCP keepalive — fixed 7B
    # `83 8f 00 00 00 00 00`.
    if len(body) != 7 or body[:2] != b"\x83\x8f":
        return []
    return [
        _magic(0, "opcode_838f", body, 2),
        _magic(2, "keepalive_pad", body, 5),
    ]


def tcp_s2c_a001(direction, transport, body):
    # SessionReady-S. Two retail variants: 2B `a0 01` and 10B
    # `a0 01 15 00 00 00 00 00 80 3f`.
    if not body or body[:2] != b"\xa0\x01":
        return []
    out = [_magic(0, "opcode_a001", body, 2)]
    if len(body) == 10:
        out.append(_u32le(2, "session_state_const_21", body))
        out.append(_f32le(6, "version_const_1_0", body))
    elif len(body) != 2:
        return []
    return out


def tcp_s2c_a002(direction, transport, body):
    # InteractionAck. Two retail variants: 2B `a0 02` and 10B
    # `a0 02 15 00 00 00 00 00 80 3f` (verified 2026-05-22+24).
    if not body or body[:2] != b"\xa0\x02":
        return []
    out = [_magic(0, "opcode_a002", body, 2)]
    if len(body) == 10:
        out.append(_u32le(2, "session_state_const_21", body))
        out.append(_f32le(6, "version_const_1_0", body))
    elif len(body) != 2:
        return []
    return out


# ─── TCP C→S decoders ──────────────────────────────────────────────────

def tcp_c2s_8000(direction, transport, body):
    # HandshakeB — fixed 3B `80 00 78`.
    if len(body) != 3 or body[:2] != b"\x80\x00":
        return []
    return [
        _magic(0, "opcode_8000", body, 2),
        _u8(2, "handshake_b_const_0x78", body),
    ]


# ─── UDP raw outers (1B opcode) ────────────────────────────────────────

def udp_raw_0b_cping(direction, transport, body):
    # CPing — both directions 5B `0b <ts_or_payload:LE32>`.
    # Multiple-of-1-sample fixed shape; we know the leading byte.
    if not body or body[0] != 0x0b or len(body) < 5:
        return []
    return [
        _magic(0, "opcode_0b", body, 1),
        _u32le(1, "ping_payload_le32", body),
    ]


def udp_raw_0c_timesync_c2s(direction, transport, body):
    # C→S GetTimeSync — 5B `0c <ts:LE32>`.
    if not body or body[0] != 0x0c or len(body) < 5:
        return []
    return [
        _magic(0, "opcode_0c", body, 1),
        _u32le(1, "client_timestamp_le32", body),
    ]


# ─── UDP reliable inner (0x03/<sub>) decoders ──────────────────────────
#
# These receive the INNER body — the bytes AFTER the `[03][seq:LE16]
# [sub]` 4-byte header has already been peeled off. So `body[0]` is
# the first inner byte (often the act_tag for the 0x1f sub-channel).

def udp_reliable_inner_08_ack(direction, transport, body):
    # 0x03/0x08 — explicit ReliableAck (C→S). Inner = `(seq-1) LE16`.
    if direction != "c2s" or len(body) != 2:
        return []
    return [_u16le(0, "ack_seq_minus_1", body)]


def udp_reliable_inner_2e_weather(direction, transport, body):
    # 0x03/0x2e WorldWeather — 13B retail. Catalog sample:
    #   01 01 00 00 00 00 f3 f0 01 00 f3 f0 01 00
    # Wait — that's 14 bytes; the catalog says 13. Recounting from
    # the catalog hex `0101000000f3f00100f3f00100` = 13 bytes:
    #   off 0: 01      weather_type
    #   off 1: 01      const
    #   off 2..5:  00 00 00 00       LE32 = 0
    #   off 6..9:  f3 f0 01 00       LE32 = 0x0001f0f3
    #   off 10..12: f3 f0 01        3 bytes — NOT a LE32. Layout
    #              unclear; could be 24-bit value, or two scalars,
    #              or truncated. INSUFFICIENT EVIDENCE — mark
    #              UNKNOWN for now and ledger it.
    if direction != "s2c" or len(body) != 13:
        return []
    return [
        _u8(0, "weather_type", body),
        _u8(1, "weather_const_0x01", body),
        _u32le(2, "weather_timer_a_le32", body),
        _u32le(6, "weather_timer_b_le32", body),
        # bytes 10..12 left UNKNOWN — see hypothesis ledger
        # entry HYP-0x03_2e-trailing-3B.
    ]


def udp_reliable_inner_27_request_world_info(direction, transport, body):
    # 0x03/0x27 RequestWorldInfo — C→S 4B fixed-shape:
    # `<entityId:LE32>`. 2,942 retail samples across 17/17 captures
    # with constant 4-byte width. Verified.
    if direction != "c2s" or len(body) != 4:
        return []
    return [_u32le(0, "entity_id", body)]


def udp_reliable_inner_26_remove_world_item(direction, transport, body):
    # 0x03/0x26 RemoveWorldItem — S→C 4B `<entityId:LE32>`. 253
    # retail samples across 10/17 captures, all 4B. Verified.
    if direction != "s2c" or len(body) != 4:
        return []
    return [_u32le(0, "removed_entity_id", body)]


def udp_reliable_inner_33_unknown(direction, transport, body):
    # 0x03/0x33 — S→C 2B `<u16le>`. 80 retail samples across 17/17
    # captures. Width verified, semantics unknown — pin the LE16
    # span so future analysis can correlate the value.
    if direction != "s2c" or len(body) != 2:
        return []
    return [_u16le(0, "unknown_le16_0x33", body)]


def udp_raw_20_movement(direction, transport, body):
    # 0x20 Movement — variable-length but always starts with
    # `20 <mapId_or_localId:u8> <type:u8>`. The trailing payload
    # depends on the `type` field — too many sub-shapes for one
    # decoder. Pin the 3-byte header here, leave rest UNKNOWN.
    # 49,847 S→C + 179,481 C→S samples; 3B header constant across
    # all of them.
    if not body or body[0] != 0x20 or len(body) < 3:
        return []
    return [
        _magic(0, "opcode_20", body, 1),
        _u8(1, "mov_local_id_or_count", body),
        _u8(2, "mov_type_byte", body),
    ]


def udp_raw_1b_movement_broadcast(direction, transport, body):
    # 0x1b S→C movement broadcast — 19B fixed in the canonical
    # case, with 30/49B variants. Header: `1b <flags:u8>
    # <map_id:LE16> <local_id:u8>` per `udp_s2c_1b.md`. The
    # trailing position+orientation bytes vary; leave for a
    # follow-up decoder.
    if direction != "s2c" or not body or body[0] != 0x1b:
        return []
    if len(body) < 5:
        return []
    return [
        _magic(0, "opcode_1b", body, 1),
        _u8(1, "flags", body),
        _u16le(2, "map_id_or_le16", body),
        _u8(4, "local_id_or_kind", body),
    ]


# ─── Decoder registry ──────────────────────────────────────────────────

TCP_S2C: Dict[int, Decoder] = {
    0x8001: tcp_s2c_8001,
    0x8003: tcp_s2c_8003,
    0x830c: tcp_s2c_830c,
    0x830d: tcp_s2c_830d,
    0x838f: tcp_s2c_838f,
    0xa001: tcp_s2c_a001,
    0xa002: tcp_s2c_a002,
}

TCP_C2S: Dict[int, Decoder] = {
    0x8000: tcp_c2s_8000,
}

# Map raw UDP outer-byte → decoder (applies to whole datagram body
# AFTER cipher decrypt; before reliable de-framing).
UDP_RAW: Dict[int, Decoder] = {
    0x0b: udp_raw_0b_cping,
    0x0c: udp_raw_0c_timesync_c2s,
    0x1b: udp_raw_1b_movement_broadcast,
    0x20: udp_raw_20_movement,
}

# Map reliable inner sub-type byte (the one after [03][seq:LE16])
# → decoder that receives the INNER body (post-4-byte-header).
UDP_RELIABLE_INNER: Dict[int, Decoder] = {
    0x08: udp_reliable_inner_08_ack,
    0x26: udp_reliable_inner_26_remove_world_item,
    0x27: udp_reliable_inner_27_request_world_info,
    0x2e: udp_reliable_inner_2e_weather,
    0x33: udp_reliable_inner_33_unknown,
}


def dispatch_tcp(direction: str, opcode: int, body: bytes
                  ) -> List[FieldSpan]:
    table = TCP_S2C if direction == "s2c" else TCP_C2S
    fn = table.get(opcode)
    if fn is None:
        return []
    return fn(direction, "tcp", body)


def dispatch_udp_raw(direction: str, body: bytes) -> List[FieldSpan]:
    if not body:
        return []
    fn = UDP_RAW.get(body[0])
    if fn is None:
        return []
    return fn(direction, "udp", body)


def dispatch_udp_reliable_inner(direction: str, sub_type: int,
                                  inner_body: bytes) -> List[FieldSpan]:
    fn = UDP_RELIABLE_INNER.get(sub_type)
    if fn is None:
        return []
    return fn(direction, "udp", inner_body)
