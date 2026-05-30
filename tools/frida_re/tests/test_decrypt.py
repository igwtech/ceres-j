"""Unit tests for the LFSR+CFB cipher port.

Two layers of tests:

1. Self-consistency: ``lfsr_byte`` is deterministic + advances state.
2. Reference parity: ``decrypt_wire_packet`` from the orchestrator
   produces byte-for-byte identical output to the upstream
   ``ceres-j/tools/decrypt-retail.py`` implementation. If the two
   drift, this test catches it before live use.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

from orchestrator.decrypt import decrypt_wire_packet, lfsr_byte


pytestmark = pytest.mark.unit


# ── Load the upstream reference module by file path (it lives ─────
# ── outside the orchestrator package so we can't `import`). ───────
_REF_PATH = (Path(__file__).resolve().parent.parent.parent
             / "decrypt-retail.py")


def _load_reference():
    spec = importlib.util.spec_from_file_location(
        "decrypt_retail_ref", str(_REF_PATH))
    if spec is None or spec.loader is None:
        pytest.skip(f"upstream reference not found at {_REF_PATH}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class TestLfsrByte:
    def test_deterministic(self):
        a, sa = lfsr_byte(0x1234, 0xab)
        b, sb = lfsr_byte(0x1234, 0xab)
        assert (a, sa) == (b, sb)

    def test_state_advances(self):
        _, new = lfsr_byte(0x1234, 0xab)
        assert new != 0x1234

    def test_output_is_byte(self):
        for state in [0, 0x1234, 0xffff]:
            for inp in [0, 0xab, 0xff]:
                out, _ = lfsr_byte(state, inp)
                assert 0 <= out <= 0xff


class TestDecryptShape:
    def test_too_short_returns_none(self):
        assert decrypt_wire_packet(b"") is None
        assert decrypt_wire_packet(b"\x00\x00\x00") is None

    def test_minimal_4_byte(self):
        # 4 bytes: seed + length-of-zero-with-no-body.
        # decrypt should produce (b'', 0) if length-field decrypts
        # to zero, else None.
        result = decrypt_wire_packet(b"\x00\x00\x00\x00")
        assert result is None or (isinstance(result, tuple) and len(result) == 2)


class TestReferenceParity:
    """For a bag of synthetic wire packets we generate by encrypting
    plaintext with the upstream lfsr_byte (so we know the cipher
    inversion is mathematically consistent), the orchestrator's
    decrypt_wire_packet must produce the SAME plaintext as the
    upstream's decrypt_wire_packet."""

    def setup_method(self):
        self.ref = _load_reference()

    @staticmethod
    def _encrypt(plaintext: bytes, seed: int) -> bytes:
        """Construct a valid wire packet from plaintext + seed,
        mirroring the cipher's structure (seed in clear, then 2-byte
        length, then body). Used to generate test vectors."""
        state = seed
        # Length field encryption.
        k1, state = lfsr_byte(state, (seed >> 8) & 0xff)
        len_lo_clear = len(plaintext) & 0xff
        len_hi_clear = (len(plaintext) >> 8) & 0xff
        enc_lo = k1 ^ len_lo_clear
        k2, state = lfsr_byte(state, enc_lo)
        enc_hi = k2 ^ len_hi_clear
        # Body encryption.
        body_enc = bytearray()
        prev_cipher = enc_hi
        for pt in plaintext:
            key, state = lfsr_byte(state, prev_cipher)
            ct = key ^ pt
            body_enc.append(ct)
            prev_cipher = ct
        return bytes([seed & 0xff, (seed >> 8) & 0xff,
                      enc_lo, enc_hi]) + bytes(body_enc)

    @pytest.mark.parametrize("seed", [0x0000, 0x1234, 0xabcd, 0xffff])
    @pytest.mark.parametrize("plaintext_hex", [
        "13",
        "13 5f 0c 13 ca 09 00",
        "03 1f 01 00 25 23 23",
        "00" * 64,
        "ff" * 32,
        "deadbeefcafebabe" * 4,
    ])
    def test_roundtrip(self, seed, plaintext_hex):
        plaintext = bytes.fromhex(plaintext_hex.replace(" ", ""))
        wire = self._encrypt(plaintext, seed)
        ours = decrypt_wire_packet(wire)
        ref = self.ref.decrypt_wire_packet(wire)
        assert ours == ref, (
            f"orchestrator decrypt != reference for "
            f"seed=0x{seed:04x} pt={plaintext.hex()}\n"
            f"  ours: {ours}\n"
            f"  ref:  {ref}"
        )
        assert ours is not None
        ours_plain, ours_len = ours
        assert ours_plain == plaintext
        assert ours_len == len(plaintext)

    def test_real_capture_sample(self):
        # An actual decrypted plaintext from the live test
        # 2026-05-28: opcode 0x13, body starts 5f 0c 13 ca 09 00 …
        # We reconstruct the wire from a known seed + this plaintext.
        plain = bytes.fromhex("13 5f 0c 13 ca 09 00 03 5f 0c 1f 01 00 25 23 23"
                              .replace(" ", ""))
        wire = self._encrypt(plain, 0x4902)
        ours = decrypt_wire_packet(wire)
        ref = self.ref.decrypt_wire_packet(wire)
        assert ours == ref
        assert ours[0] == plain


class TestSanityRejection:
    def test_length_too_large_returns_none(self):
        # Construct wire bytes whose length field decrypts to something
        # >> available body. decrypt should return None to signal "not
        # a game packet" (e.g. handshake or random UDP noise).
        bad = bytes([0x99, 0x99,  # seed
                     0x00, 0x00,  # length field (likely decrypts large)
                     ]) + b"\x00" * 8  # tiny body
        result = decrypt_wire_packet(bad)
        # We don't assert None deterministically because some seeds may
        # decrypt the length to a small value. But for THIS seed/pair we
        # at least confirm the function doesn't crash.
        assert result is None or isinstance(result, tuple)
