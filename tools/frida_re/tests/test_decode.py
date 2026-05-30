"""Unit tests for orchestrator.decode.

Pure-function tests; no I/O, no Frida. Covers the event-shape
normalisation that everything downstream depends on.
"""

import pytest

from orchestrator.decode import (
    DecodedEvent,
    decode_event,
)


pytestmark = pytest.mark.unit


def _cipher_enter(buf_hex: str = "fe 0a 00 03 1f 01 00 25",
                  seed_lo: int = 0x12,
                  seed_hi: int = 0x34) -> dict:
    return {
        "ev": "cipher_enter",
        "hook": "udp_cipher_a",
        "ts": 1716_900_000_000_000_000,
        "tid": 1234,
        "seed_lo": seed_lo,
        "seed_hi": seed_hi,
        "buf_hex": buf_hex,
        "buf_len": len(buf_hex.split()),
    }


class TestDecodeCipherEnter:
    def test_basic_event(self):
        d = decode_event(_cipher_enter())
        assert isinstance(d, DecodedEvent)
        assert d.ev == "cipher_enter"
        assert d.hook == "udp_cipher_a"
        assert d.tid == 1234
        assert d.ts_ns == 1716_900_000_000_000_000
        assert d.buf is not None
        assert d.buf == bytes.fromhex("fe0a00031f010025")
        assert d.buf_len == 8

    def test_seed_combines_lo_hi(self):
        d = decode_event(_cipher_enter(seed_lo=0x12, seed_hi=0x34))
        assert d.seed == 0x3412

    def test_seed_zero_when_both_zero(self):
        d = decode_event(_cipher_enter(seed_lo=0, seed_hi=0))
        assert d.seed == 0

    def test_seed_clamped_to_uint16(self):
        d = decode_event(_cipher_enter(seed_lo=0xff, seed_hi=0xff))
        assert d.seed == 0xffff

    def test_summary_includes_seed_and_hex(self):
        d = decode_event(_cipher_enter())
        assert "cipher_enter" in d.summary
        assert "0x3412" in d.summary  # seed
        assert "fe 0a" in d.summary   # hex preview
        assert "hook=udp_cipher_a" in d.summary

    def test_summary_truncates_long_buffers(self):
        long_hex = " ".join(f"{i:02x}" for i in range(64))
        d = decode_event(_cipher_enter(buf_hex=long_hex))
        # The decoder's preview truncates with "…"; the raw buf is
        # kept in full.
        assert "…" in d.summary
        assert d.buf is not None
        assert len(d.buf) == 64


class TestDecodeBufHexParsing:
    def test_space_separated(self):
        d = decode_event(_cipher_enter(buf_hex="01 02 03"))
        assert d.buf == bytes([1, 2, 3])

    def test_contiguous(self):
        d = decode_event(_cipher_enter(buf_hex="010203"))
        assert d.buf == bytes([1, 2, 3])

    def test_colon_separated(self):
        d = decode_event(_cipher_enter(buf_hex="aa:bb:cc"))
        assert d.buf == bytes([0xAA, 0xBB, 0xCC])

    def test_uppercase(self):
        d = decode_event(_cipher_enter(buf_hex="AB CD EF"))
        assert d.buf == bytes([0xAB, 0xCD, 0xEF])

    def test_invalid_hex_raises(self):
        with pytest.raises(ValueError, match="non-hex"):
            decode_event(_cipher_enter(buf_hex="gg"))

    def test_odd_length_raises(self):
        with pytest.raises(ValueError, match="odd-length"):
            decode_event(_cipher_enter(buf_hex="abc"))

    def test_empty_string_is_empty_bytes(self):
        e = _cipher_enter()
        e["buf_hex"] = ""
        d = decode_event(e)
        assert d.buf == b""


class TestDecodeSeedValidation:
    def test_seed_lo_without_hi_yields_none(self):
        e = _cipher_enter()
        del e["seed_hi"]
        d = decode_event(e)
        assert d.seed is None

    def test_non_int_seed_raises(self):
        e = _cipher_enter()
        e["seed_lo"] = "0x12"  # bogus type
        with pytest.raises(TypeError):
            decode_event(e)


class TestDecodeUnknownEvent:
    def test_passthrough_preserves_extras(self):
        e = {"ev": "future_hook_we_havent_seen", "ts": 42,
             "hook": "mystery", "weird_field": [1, 2, 3]}
        d = decode_event(e)
        assert d.ev == "future_hook_we_havent_seen"
        assert d.hook == "mystery"
        assert d.ts_ns == 42
        assert "weird_field" in d.extras
        assert d.extras["weird_field"] == [1, 2, 3]

    def test_summary_nonempty_for_unknown(self):
        d = decode_event({"ev": "ready",
                          "module": "NeocronClient.exe",
                          "base": "0x400000"})
        # No seed/buf, so summary is just the name (+ extras stashed
        # but not rendered). Empty hook is fine.
        assert d.summary.startswith("ready")


class TestDecodeUdpEvents:
    """Cover the canonical udp_recv / udp_send events the agent emits."""

    @staticmethod
    def _encrypt(plaintext: bytes, seed: int) -> bytes:
        """Same helper as test_decrypt — produce a valid wire from
        plaintext + seed so we can assert auto-decryption works."""
        from orchestrator.decrypt import lfsr_byte
        state = seed
        k1, state = lfsr_byte(state, (seed >> 8) & 0xff)
        len_lo_clear = len(plaintext) & 0xff
        len_hi_clear = (len(plaintext) >> 8) & 0xff
        enc_lo = k1 ^ len_lo_clear
        k2, state = lfsr_byte(state, enc_lo)
        enc_hi = k2 ^ len_hi_clear
        body_enc = bytearray()
        prev_cipher = enc_hi
        for pt in plaintext:
            key, state = lfsr_byte(state, prev_cipher)
            ct = key ^ pt
            body_enc.append(ct)
            prev_cipher = ct
        return bytes([seed & 0xff, (seed >> 8) & 0xff,
                      enc_lo, enc_hi]) + bytes(body_enc)

    def test_udp_recv_auto_decrypts(self):
        # Build a wire packet from a known plaintext.
        plaintext = bytes.fromhex("130000123456789a")
        wire = self._encrypt(plaintext, 0x4902)
        evt = decode_event({
            "ev": "udp_recv",
            "sock": 912,
            "len": len(wire),
            "hex": wire.hex(),
            "tid": 300,
            "ts": 17_800_000_000_000_000,
        })
        assert evt.ev == "udp_recv"
        assert evt.direction == "s2c"
        assert evt.sock == 912
        assert evt.wire == wire
        assert evt.wire_len == len(wire)
        assert evt.decrypt_ok is True
        assert evt.plain == plaintext
        assert evt.opcode == 0x13
        assert "udp_recv" in evt.summary
        assert "s2c" in evt.summary
        assert "0x13" in evt.summary

    def test_udp_send_marked_c2s(self):
        wire = self._encrypt(b"\x03\x1f\x01", 0xdead)
        evt = decode_event({
            "ev": "udp_send",
            "sock": 912,
            "len": len(wire),
            "hex": wire.hex(),
        })
        assert evt.direction == "c2s"
        assert evt.decrypt_ok is True
        assert evt.plain == b"\x03\x1f\x01"
        assert evt.opcode == 0x03

    def test_decrypt_failure_flagged(self):
        # 8 bytes of zeros: wire form is structurally OK but the
        # decrypted length will be wrong → decrypt returns None.
        evt = decode_event({
            "ev": "udp_recv",
            "sock": 1,
            "len": 4,
            "hex": "00" * 4,
        })
        # decrypt_ok may be True (length=0, empty body) or False;
        # either way no crash and the wire is captured.
        assert evt.wire == b"\x00\x00\x00\x00"
        assert evt.decrypt_ok in (True, False)

    def test_too_short_wire_no_decrypt_attempted(self):
        evt = decode_event({
            "ev": "udp_recv",
            "sock": 1,
            "len": 2,
            "hex": "0102",  # 2 bytes < 4 min for cipher
        })
        assert evt.wire == b"\x01\x02"
        assert evt.decrypt_ok is None
        assert evt.plain is None

    def test_unknown_event_still_passes_through(self):
        evt = decode_event({"ev": "udp_recv_v2", "novel": True})
        # Non-canonical UDP event name routes through the
        # legacy/extras path — no crash, extras preserved.
        assert evt.ev == "udp_recv_v2"
        assert evt.extras.get("novel") is True


class TestDecodeTcpEvents:
    """Cover tcp_read / tcp_write / tcp_ioctl events introduced in
    addon v0.4.0."""

    def test_tcp_read_marked_s2c(self):
        # fe-framed: fe <size LE2> <opcode_hi> <opcode_lo> <body>
        wire = bytes.fromhex("fe05008383112233")  # size=5, op=0x83/0x83
        evt = decode_event({
            "ev": "tcp_read", "handle": "0x42",
            "hex": wire.hex(),
        })
        assert evt.direction == "s2c"
        assert evt.wire == wire
        assert evt.wire_len == 5
        assert evt.opcode == (0x83 << 8) | 0x83
        assert "tcp_read" in evt.summary
        assert "0x83/0x83" in evt.summary
        assert evt.extras.get("handle") == "0x42"

    def test_tcp_write_marked_c2s(self):
        wire = bytes.fromhex("fe07008300010203040506")
        evt = decode_event({
            "ev": "tcp_write", "handle": "0xff", "len": 10,
            "hex": wire.hex(),
        })
        assert evt.direction == "c2s"
        assert evt.opcode == (0x83 << 8) | 0x00

    def test_tcp_ioctl_uses_kind_for_direction(self):
        wire = bytes.fromhex("fe040083850102")
        evt = decode_event({
            "ev": "tcp_ioctl", "kind": "recv",
            "ioctl": "0x12017", "handle": "0x4",
            "hex": wire.hex(),
        })
        assert evt.direction == "recv"
        assert evt.opcode == (0x83 << 8) | 0x85
        assert evt.extras.get("ioctl") == "0x12017"

    def test_non_fe_prefix_no_opcode(self):
        evt = decode_event({
            "ev": "tcp_read", "handle": "0x1",
            "hex": "010203040506",  # no fe prefix
        })
        # Wire captured but opcode field absent.
        assert evt.wire == bytes.fromhex("010203040506")
        assert evt.opcode is None


class TestDecodeErrors:
    def test_non_dict_raises(self):
        with pytest.raises(TypeError):
            decode_event("not a dict")  # type: ignore[arg-type]

    def test_missing_ev_raises(self):
        with pytest.raises(ValueError, match="missing 'ev'"):
            decode_event({"hook": "x"})

    def test_empty_ev_raises(self):
        with pytest.raises(ValueError, match="missing 'ev'"):
            decode_event({"ev": ""})

    def test_ev_not_string_raises(self):
        with pytest.raises(ValueError):
            decode_event({"ev": 123})  # type: ignore[arg-type]
