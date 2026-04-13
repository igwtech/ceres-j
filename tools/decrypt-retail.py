#!/usr/bin/env python3
"""
decrypt-retail.py — Decrypt Neocron 2 retail UDP captures.

Cipher reverse-engineered from neocronclient.exe:
  - FUN_00560090 (sendto wrapper): encrypts with per-packet random seed
  - FUN_0055ff30 (recvfrom wrapper): decrypts
  - FUN_004e36e0: 16-bit LFSR PRNG with cipher-feedback

Wire format per sub-packet:
  [seed_lo][seed_hi][enc_len_lo][enc_len_hi][enc_data_0..enc_data_N-1]

The seed (bytes 0-1) is sent in the clear. Bytes 2+ are encrypted using
the LFSR PRNG in cipher-feedback mode (each key byte depends on the
previous ciphertext byte).
"""

from __future__ import annotations

import argparse
import math
import re
import sys
from collections import Counter
from pathlib import Path

KNOWN_HEADERS = {0x01, 0x03, 0x04, 0x08, 0x13}

_RECVMSG_RE = re.compile(
    r'(recvmsg|sendmsg)\(\d+<UDP[^>]*>.*?iov_base="((?:[^"\\]|\\.)*)".*?= (\d+)$',
    re.MULTILINE,
)


def decode_strace_bytes(s: str) -> bytes:
    out = bytearray()
    i = 0
    n = len(s)
    while i < n:
        ch = s[i]
        if ch == "\\" and i + 1 < n:
            nxt = s[i + 1]
            if nxt == "x" and i + 3 < n:
                try:
                    out.append(int(s[i + 2 : i + 4], 16))
                    i += 4
                    continue
                except ValueError:
                    pass
            if nxt == "n": out.append(0x0A); i += 2; continue
            if nxt == "r": out.append(0x0D); i += 2; continue
            if nxt == "t": out.append(0x09); i += 2; continue
            if nxt == "0": out.append(0x00); i += 2; continue
            if nxt == "\\": out.append(0x5C); i += 2; continue
            if nxt == '"': out.append(0x22); i += 2; continue
            out.append(ord(ch)); i += 1; continue
        out.append(ord(ch) & 0xFF); i += 1
    return bytes(out)


def extract_udp(path: Path, direction: str) -> list[bytes]:
    if not path.exists(): return []
    with path.open("r", errors="replace") as f:
        content = f.read()
    out = []
    for m in _RECVMSG_RE.finditer(content):
        call = m.group(1)
        if direction == "recv" and call != "recvmsg": continue
        if direction == "send" and call != "sendmsg": continue
        n = int(m.group(3))
        if n <= 0: continue
        data = decode_strace_bytes(m.group(2))
        if data: out.append(data)
    return out


# ── LFSR PRNG (FUN_004e36e0) ─────────────────────────────────────────

def lfsr_byte(state: int, input_byte: int) -> tuple[int, int]:
    """16-bit LFSR with cipher-feedback.

    From Ghidra decompile of FUN_004e36e0:
    For each of 8 bits:
        hi = state >> 8
        feedback = (hi>>6 ^ hi>>5 ^ hi>>3 ^ (state & 0xFF) ^ (input>>bit)) & 1
        state = (state << 1) | feedback   (16-bit)
        output_bit[7-bit] = feedback      (MSB first)

    Returns (output_byte, new_state).
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


# ── Decrypt one wire packet ───────────────────────────────────────────

def decrypt_wire_packet(wire: bytes) -> tuple[bytes, int] | None:
    """Decrypt a single wire packet.

    Wire format: [seed_lo][seed_hi][enc_len_lo][enc_len_hi][enc_data...]

    Returns (plaintext, data_length) or None if too short.
    """
    if len(wire) < 4:
        return None

    # Seed from first 2 bytes (unencrypted)
    seed = wire[0] | (wire[1] << 8)
    state = seed

    # Decrypt length (bytes 2-3)
    key1, state = lfsr_byte(state, wire[1])  # input = seed_hi
    len_lo = key1 ^ wire[2]

    key2, state = lfsr_byte(state, wire[2])  # input = prev cipher byte
    len_hi = key2 ^ wire[3]

    data_len = (len_hi << 8) | len_lo

    # Sanity check
    if data_len > len(wire) - 4:
        # Try without the +0x0F byte offset (raw WinSockMGR packets
        # have byte 0 adjusted by -0x0F before dispatch)
        return None

    # Decrypt data (bytes 4+)
    plaintext = bytearray()
    prev_cipher = wire[3]
    for i in range(min(data_len, len(wire) - 4)):
        cipher_byte = wire[4 + i]
        key, state = lfsr_byte(state, prev_cipher)
        plaintext.append(key ^ cipher_byte)
        prev_cipher = cipher_byte

    return bytes(plaintext), data_len


# ── Output ────────────────────────────────────────────────────────────

def entropy(data: bytes) -> float:
    if not data: return 0.0
    c = Counter(data)
    n = len(data)
    return -sum((v / n) * math.log2(v / n) for v in c.values())


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="Decrypt NC2 retail UDP")
    ap.add_argument("--input", "-i", type=Path, required=True)
    ap.add_argument("--direction", "-d", choices=("recv", "send"),
                    default="recv")
    ap.add_argument("--dump", type=int, default=0)
    args = ap.parse_args(argv[1:])

    packets = extract_udp(args.input, args.direction)
    print(f"Loaded {len(packets)} {args.direction} packets")
    if not packets: return 1

    decoded = []
    lengths = []
    for pkt in packets:
        result = decrypt_wire_packet(pkt)
        if result:
            plain, dlen = result
            decoded.append(plain)
            lengths.append(dlen)
        else:
            decoded.append(None)
            lengths.append(0)

    # Stats
    valid = sum(1 for d in decoded if d and len(d) > 0 and d[0] in KNOWN_HEADERS)
    total = len(decoded)
    all_plain = b"".join(d for d in decoded if d)
    all_raw = b"".join(packets)

    print(f"\nValid first-byte headers: {valid}/{total}"
          f" ({'ALL MATCH' if valid == total else 'PARTIAL'})")
    if all_plain:
        print(f"Decoded entropy: {entropy(all_plain):.3f} "
              f"(raw: {entropy(all_raw):.3f})")
        null_pct = sum(1 for b in all_plain if b == 0) / len(all_plain) * 100
        print(f"Null byte %: {null_pct:.1f}%")

    # First-byte histogram
    hdr_hist = Counter(d[0] for d in decoded if d and len(d) > 0)
    print(f"\nFirst-byte histogram:")
    for b, c in sorted(hdr_hist.items(), key=lambda x: -x[1]):
        tag = " ← KNOWN" if b in KNOWN_HEADERS else ""
        print(f"  0x{b:02x}: {c}{tag}")

    # Dump
    n = args.dump if args.dump > 0 else len(decoded)
    print(f"\nDecoded packets (first {min(n, len(decoded))}):")
    for i in range(min(n, len(decoded))):
        wire = packets[i]
        plain = decoded[i]
        dlen = lengths[i]
        seed = (wire[0] | (wire[1] << 8)) if len(wire) >= 2 else 0

        if plain and len(plain) > 0:
            ptype = {0x01:"HANDSHAKE", 0x03:"SYNC", 0x04:"KEEPALIVE",
                     0x08:"ABORT", 0x13:"GAMEDATA"}.get(plain[0], "UNK")
            extra = ""
            if plain[0] == 0x13 and len(plain) > 5:
                cnt = int.from_bytes(plain[1:3], "little")
                sub_len = plain[5]
                extra = f" cnt={cnt}"
                if len(plain) > 6:
                    sub0 = plain[6]
                    extra += f" sub=0x{sub0:02x}"
                    if sub0 == 0x03 and len(plain) > 9:
                        extra += f"→0x{plain[9]:02x}"
            print(f"  #{i+1:3d} wire={len(wire):4d}B data={dlen:4d}B "
                  f"seed=0x{seed:04x} {ptype:10s}{extra}")
            print(f"       plain: {plain[:40].hex()}")
        else:
            print(f"  #{i+1:3d} wire={len(wire):4d}B (decrypt failed)")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
