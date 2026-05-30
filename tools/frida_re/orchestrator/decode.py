"""Pure decoders for events emitted by the Frida agent.

The agent emits events as JSON-serialisable dicts with at minimum:

    {"ev": "<event_name>", "ts": <ns_since_epoch>, ...}

Canonical hook events (current v0.3 architecture):

    {"ev": "udp_recv", "sock": 912, "len": 20, "hex": "0249...",
     "tid": 300, "ts": 17800...}

    {"ev": "udp_send", "sock": 912, "len":  8, "hex": "1234...",
     "tid": 300, "ts": 17800...}

Legacy cipher-function events (still handled for back-compat):

    {"ev": "cipher_enter", "hook": "udp_cipher_a",
     "seed_lo": 0x12, "seed_hi": 0x34, "buf_hex": "fe0a00...",
     "buf_len": 10, "tid": 1234}

This module turns the raw event into a ``DecodedEvent`` with typed
fields ready for the recorder + pretty-printer. UDP events are
auto-decrypted via ``orchestrator.decrypt.decrypt_wire_packet`` so the
JSONL trace contains plaintext alongside the wire bytes.

Everything here is **pure** — no I/O, no global state.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, Optional

from .decrypt import decrypt_wire_packet


@dataclass
class DecodedEvent:
    """A normalised event ready for recording / display."""
    ev: str
    hook: Optional[str] = None
    ts_ns: Optional[int] = None
    tid: Optional[int] = None
    # Cipher-event-specific (legacy path)
    seed: Optional[int] = None
    buf: Optional[bytes] = None
    buf_len: Optional[int] = None
    # UDP-event-specific (canonical path)
    sock: Optional[int] = None
    wire: Optional[bytes] = None
    wire_len: Optional[int] = None
    plain: Optional[bytes] = None
    plain_len: Optional[int] = None
    direction: Optional[str] = None     # "c2s" / "s2c"
    decrypt_ok: Optional[bool] = None
    opcode: Optional[int] = None        # plain[0] for routing
    # Generic passthrough for hook-specific fields
    extras: Dict[str, Any] = field(default_factory=dict)
    # Pretty one-liner suitable for stdout
    summary: str = ""


_HEX_CHARS = set("0123456789abcdefABCDEF")


def _parse_hex(s: Any) -> Optional[bytes]:
    """Parse a hex string (space-separated or contiguous) into bytes."""
    if not isinstance(s, str):
        return None
    cleaned = s.replace(" ", "").replace(":", "").replace("-", "")
    if not cleaned:
        return b""
    if any(c not in _HEX_CHARS for c in cleaned):
        raise ValueError(f"non-hex character in buf_hex: {s!r}")
    if len(cleaned) % 2 != 0:
        raise ValueError(f"odd-length hex string: {s!r}")
    return bytes.fromhex(cleaned)


def _seed_from(evt: Dict[str, Any]) -> Optional[int]:
    """Combine seed_lo / seed_hi from the agent event, if present."""
    lo = evt.get("seed_lo")
    hi = evt.get("seed_hi")
    if lo is None or hi is None:
        return None
    if not isinstance(lo, int) or not isinstance(hi, int):
        raise TypeError("seed_lo / seed_hi must be ints")
    return ((hi & 0xFF) << 8) | (lo & 0xFF)


def _hexdump(buf: Optional[bytes], limit: int = 32) -> str:
    """Compact hex preview for log lines. Truncates with ``…``."""
    if not buf:
        return ""
    if len(buf) <= limit:
        return buf.hex(" ")
    return buf[:limit].hex(" ") + " …"


def decode_event(evt: Dict[str, Any]) -> DecodedEvent:
    """Normalise a raw agent event dict into a ``DecodedEvent``."""
    if not isinstance(evt, dict):
        raise TypeError(f"event must be a dict, got {type(evt).__name__}")
    name = evt.get("ev")
    if not isinstance(name, str) or not name:
        raise ValueError(f"event missing 'ev' field: {evt!r}")

    decoded = DecodedEvent(
        ev=name,
        hook=evt.get("hook"),
        ts_ns=evt.get("ts"),
        tid=evt.get("tid"),
    )

    # ── TCP frames (v0.4.0/v0.5.0) ──────────────────────────────────
    # Format on the wire: [fe][size LE2][opcode_hi][opcode_lo][body...]
    # No cipher — TCP is unencrypted; size already exposed by framing.
    # tcp_read/write/ioctl from v0.4.0; nt_read/write added v0.5.0
    # for kernel-transition path.
    if name in ("tcp_read", "tcp_write", "tcp_ioctl",
                "nt_read", "nt_write"):
        decoded.wire = _parse_hex(evt.get("hex"))
        decoded.direction = ("s2c" if name == "tcp_read"
                             else "c2s" if name == "tcp_write"
                             else evt.get("kind", "?"))
        # Parse the FE frame structure for the opcode field.
        if decoded.wire is not None and len(decoded.wire) >= 5 \
                and decoded.wire[0] == 0xfe:
            sz = decoded.wire[1] | (decoded.wire[2] << 8)
            decoded.wire_len = sz
            decoded.opcode = (decoded.wire[3] << 8) | decoded.wire[4]
        decoded.extras = {k: v for k, v in evt.items()
                          if k not in {"ev", "hook", "ts", "tid", "hex",
                                       "handle", "len", "kind", "ioctl"}}
        # Stash handle / ioctl info as visible extras.
        for k in ("handle", "ioctl", "kind"):
            if k in evt:
                decoded.extras[k] = evt[k]
        decoded.summary = _summarise_tcp(decoded)
        return decoded

    # ── Canonical UDP path ──────────────────────────────────────────
    if name in ("udp_recv", "udp_send"):
        decoded.sock = evt.get("sock")
        decoded.wire_len = evt.get("len")
        decoded.wire = _parse_hex(evt.get("hex"))
        decoded.direction = "s2c" if name == "udp_recv" else "c2s"
        if decoded.wire is not None and len(decoded.wire) >= 4:
            result = decrypt_wire_packet(decoded.wire)
            if result is not None:
                plain, plain_len = result
                decoded.plain = plain
                decoded.plain_len = plain_len
                decoded.decrypt_ok = True
                if plain:
                    decoded.opcode = plain[0]
            else:
                decoded.decrypt_ok = False
        # Stash anything we didn't explicitly map.
        known = {"ev", "hook", "ts", "tid", "sock", "len", "hex"}
        decoded.extras = {k: v for k, v in evt.items() if k not in known}
        decoded.summary = _summarise_udp(decoded)
        return decoded

    # ── Legacy cipher-event path ────────────────────────────────────
    buf_hex = evt.get("buf_hex")
    if buf_hex is not None:
        decoded.buf = _parse_hex(buf_hex)
        decoded.buf_len = evt.get("buf_len")
        if decoded.buf is not None and decoded.buf_len is None:
            decoded.buf_len = len(decoded.buf)
    decoded.seed = _seed_from(evt)
    known = {"ev", "hook", "ts", "tid",
             "seed_lo", "seed_hi", "buf_hex", "buf_len"}
    decoded.extras = {k: v for k, v in evt.items() if k not in known}
    decoded.summary = _summarise_cipher(decoded)
    return decoded


def _summarise_tcp(d: DecodedEvent) -> str:
    """One-line summary for a TCP frame event."""
    parts = [d.ev]
    if d.direction:
        parts.append(d.direction)
    if d.opcode is not None:
        op_hi, op_lo = (d.opcode >> 8) & 0xff, d.opcode & 0xff
        parts.append(f"op=0x{op_hi:02x}/0x{op_lo:02x}")
    if d.wire_len is not None:
        parts.append(f"sz={d.wire_len}")
    if d.wire:
        parts.append(_hexdump(d.wire, 16))
    return " ".join(parts)


def _summarise_udp(d: DecodedEvent) -> str:
    """One-line summary for a UDP capture event."""
    parts = [d.ev]
    if d.direction:
        parts.append(d.direction)
    if d.sock is not None:
        parts.append(f"sock={d.sock}")
    if d.wire_len is not None:
        parts.append(f"wire_len={d.wire_len}")
    if d.decrypt_ok is True and d.opcode is not None:
        parts.append(f"opcode=0x{d.opcode:02x}")
        if d.plain:
            parts.append("plain=" + _hexdump(d.plain, 16))
    elif d.decrypt_ok is False:
        parts.append("decrypt=FAIL")
        if d.wire:
            parts.append("wire=" + _hexdump(d.wire, 16))
    return " ".join(parts)


def _summarise_cipher(d: DecodedEvent) -> str:
    """One-line summary for a legacy cipher-function event."""
    parts = [d.ev]
    if d.hook:
        parts.append(f"hook={d.hook}")
    if d.seed is not None:
        parts.append(f"seed=0x{d.seed:04x}")
    if d.buf is not None:
        parts.append(f"len={d.buf_len}")
        parts.append(_hexdump(d.buf))
    if d.tid is not None:
        parts.append(f"tid={d.tid}")
    return " ".join(parts)
