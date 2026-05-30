"""LFSR+CFB cipher applied to NC2 UDP wire packets.

Ported verbatim from ``ceres-j/tools/decrypt-retail.py``. Single
authoritative implementation lives there (the .py used by pcap
analysis tooling). This module is a copy intentionally — keeping a
local copy means the orchestrator works as a self-contained tree and
is unit-testable without crossing tool boundaries.

Wire format per UDP datagram:

    [seed_lo][seed_hi][enc_len_lo][enc_len_hi][enc_data_0..enc_data_N]

* Bytes 0–1: per-packet 16-bit LFSR seed, sent in the clear.
* Bytes 2–3: encrypted length (LE16) of the plaintext body.
* Bytes 4+: encrypted body, decrypted with CFB-mode LFSR keystream.

Cipher reverse-engineered from neocronclient.exe FUN_004e36e0.
"""

from __future__ import annotations

from typing import Optional, Tuple


__all__ = ["decrypt_wire_packet", "lfsr_byte"]


def lfsr_byte(state: int, input_byte: int) -> Tuple[int, int]:
    """One LFSR step over an 8-bit input.

    From the Ghidra decompile of FUN_004e36e0:

        for each of 8 bits:
            hi = state >> 8
            feedback = (hi>>6 ^ hi>>5 ^ hi>>3 ^ (state & 0xFF)
                        ^ (input>>bit)) & 1
            state = (state << 1) | feedback   (16-bit)
            output_bit[7-bit] = feedback      (MSB first)

    Returns ``(output_byte, new_state)``.
    """
    output = 0
    for bit in range(8):
        hi = (state >> 8) & 0xFF
        lo = state & 0xFF
        data_bit = (input_byte >> bit) & 1
        feedback = ((hi >> 6) ^ (hi >> 5) ^ (hi >> 3) ^ lo ^ data_bit) & 1
        state = ((state << 1) | feedback) & 0xFFFF
        output |= (feedback << (7 - bit))
    return output, state


def decrypt_wire_packet(wire: bytes) -> Optional[Tuple[bytes, int]]:
    """Decrypt one full UDP datagram.

    Returns ``(plaintext, declared_length)`` on success, or ``None``
    if the buffer is too short or the declared length doesn't match
    the wire size (which usually means it isn't an NC2 game packet —
    handshake or unrelated traffic).

    ``declared_length`` is the encrypted-length field from bytes 2–3;
    ``plaintext`` is the decrypted body. The caller should slice the
    plaintext to ``[:declared_length]`` if they want the body exactly
    as the client sees it.
    """
    if len(wire) < 4:
        return None

    seed = wire[0] | (wire[1] << 8)
    state = seed

    # Decrypt length field (bytes 2-3) — keystream depends on the
    # previously-seen cipher byte (CFB), which for byte 2 is wire[1].
    key1, state = lfsr_byte(state, wire[1])
    len_lo = key1 ^ wire[2]
    key2, state = lfsr_byte(state, wire[2])
    len_hi = key2 ^ wire[3]
    data_len = (len_hi << 8) | len_lo

    if data_len > len(wire) - 4:
        return None

    plaintext = bytearray()
    prev_cipher = wire[3]
    for i in range(min(data_len, len(wire) - 4)):
        cipher_byte = wire[4 + i]
        key, state = lfsr_byte(state, prev_cipher)
        plaintext.append(key ^ cipher_byte)
        prev_cipher = cipher_byte

    return bytes(plaintext), data_len
