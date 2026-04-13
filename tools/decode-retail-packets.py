#!/usr/bin/env python3
"""
decode-retail-packets.py — Apply the known PacketObfuscator XOR cipher to
retail UDP packets captured via strace, and try to reveal plaintext.

The hypothesis: NC2's UDP cipher (ObfuscateStreamBuf) is the per-packet
XOR-with-seed scheme documented in
ceres-j/src/main/java/server/networktools/PacketObfuscator.java.

Algorithm:
  For each byte at index i:
    s = (i + 1) * seed
    key_byte = ((s >> 16) & 0xff) ^ (s & 0xff)
    plain[i] = cipher[i] ^ key_byte

The seed is encoded in byte 0: since s=seed for i=0 (seed < 256),
key_byte[0] = seed. So:
    seed = cipher[0] ^ expected_plaintext_first_byte

We try each of the five known headers {0x01, 0x03, 0x04, 0x08, 0x13} as
the expected first byte. If the resulting plaintext is "reasonable" (first
byte matches the header we assumed) we declare a match.

Output: for each retail S→C UDP packet, print size, guessed header, and
the first 32 bytes of decoded plaintext in hex. At the end, print a
first-byte histogram of the decoded plaintext — this is the test. If the
cipher is correct, the histogram should be concentrated on the 5 known
headers. If it's a DIFFERENT cipher, the histogram will be uniform
(= cipher guess failed to recover anything).

Usage:
  python3 tools/decode-retail-packets.py /tmp/nc2_strace_RETAIL_ACC1_CHAR1.log
  python3 tools/decode-retail-packets.py /tmp/nc2_strace_RETAIL_ACC1_CHAR1.log --dump 20
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from pathlib import Path

KNOWN_HEADERS = (0x01, 0x03, 0x04, 0x08, 0x13)

# Match strace recvmsg/sendmsg lines with iov_base payload and return byte
# count. We grab the iov_base string and the terminating `= N` return
# value, just like analyze-retail-packets.py.
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
            if nxt == "n":
                out.append(0x0A); i += 2; continue
            if nxt == "r":
                out.append(0x0D); i += 2; continue
            if nxt == "t":
                out.append(0x09); i += 2; continue
            if nxt == "0":
                out.append(0x00); i += 2; continue
            if nxt == "\\":
                out.append(0x5C); i += 2; continue
            if nxt == '"':
                out.append(0x22); i += 2; continue
            out.append(ord(ch))
            i += 1
            continue
        out.append(ord(ch) & 0xFF)
        i += 1
    return bytes(out)


def extract_udp(path: Path, direction: str) -> list[bytes]:
    """direction: 'recv' or 'send' or 'both'."""
    if not path.exists():
        return []
    with path.open("r", errors="replace") as f:
        content = f.read()
    out: list[bytes] = []
    for m in _RECVMSG_RE.finditer(content):
        call = m.group(1)  # recvmsg or sendmsg
        if direction == "recv" and call != "recvmsg":
            continue
        if direction == "send" and call != "sendmsg":
            continue
        data = decode_strace_bytes(m.group(2))
        if data:
            out.append(data)
    return out


def apply_xor(data: bytes, seed: int) -> bytes:
    out = bytearray(len(data))
    for i, b in enumerate(data):
        s = (i + 1) * seed
        key = ((s >> 16) & 0xFF) ^ (s & 0xFF)
        out[i] = b ^ key
    return bytes(out)


def decrypt(data: bytes) -> tuple[int, bytes] | None:
    """Try each known header, pick the one that yields the LOWEST-entropy
    decoded payload. If the cipher is correct, the right header will
    produce structured plaintext (low entropy); wrong headers will leave
    the payload looking random (high entropy). If NO header produces
    meaningfully lower entropy than the others, the cipher guess is wrong.
    """
    if not data:
        return None
    best = None  # (entropy, header, plain)
    for header in KNOWN_HEADERS:
        seed = (data[0] ^ header) & 0xFF
        plain = apply_xor(data, seed)
        # Skip the fingerprint byte when scoring, since it's identically
        # `header` by construction for all 5 candidates.
        e = entropy(plain[1:]) if len(plain) > 1 else 0.0
        if best is None or e < best[0]:
            best = (e, header, plain)
    if best is None:
        return None
    return (best[1], best[2])


def entropy(data: bytes) -> float:
    import math
    if not data:
        return 0.0
    c = Counter(data)
    n = len(data)
    return -sum((v / n) * math.log2(v / n) for v in c.values())


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("log", type=Path)
    ap.add_argument("--direction", choices=("recv", "send", "both"),
                    default="recv",
                    help="recv = S→C, send = C→S, both = all UDP")
    ap.add_argument("--dump", type=int, default=0,
                    help="print the first N packets with their decoded "
                         "plaintext (hex)")
    ap.add_argument("--header", type=lambda x: int(x, 0), default=None,
                    help="assume this specific header byte (e.g. 0x13) "
                         "instead of auto-detecting")
    args = ap.parse_args(argv[1:])

    packets = extract_udp(args.log, args.direction)
    print(f"Loaded {len(packets)} {args.direction} packets from {args.log}")
    if not packets:
        return 1

    decoded_first_bytes: list[int] = []
    decoded_entropies: list[float] = []
    raw_entropies: list[float] = []
    decoded_samples: list[tuple[int, int, bytes, bytes]] = []  # size, header, raw[:32], plain[:32]
    all_raw = bytearray()
    all_plain = bytearray()

    for pkt in packets:
        if args.header is not None:
            seed = (pkt[0] ^ args.header) & 0xFF
            plain = apply_xor(pkt, seed)
            chosen_header = args.header
        else:
            r = decrypt(pkt)
            if r is None:
                continue
            chosen_header, plain = r
            seed = (pkt[0] ^ chosen_header) & 0xFF
        decoded_first_bytes.append(plain[0])
        decoded_entropies.append(entropy(plain))
        raw_entropies.append(entropy(pkt))
        all_raw.extend(pkt)
        all_plain.extend(plain)
        if len(decoded_samples) < args.dump:
            decoded_samples.append((len(pkt), chosen_header,
                                    bytes(pkt[:32]), plain[:32]))

    # If we auto-detected, the first byte is trivially the header (since
    # decrypt() only returns matches where plain[0] == header). The real
    # test is: are there any packets that DON'T decode with a header at
    # all? If the cipher is right, all packets should decode (because
    # seed is recoverable for any of the 5 headers). If the cipher is
    # WRONG, we'll still "succeed" because we only check first-byte
    # identity.
    #
    # The more meaningful test is byte 1: the decryption key at byte 1 is
    # ((2*seed)>>16 ^ 2*seed) & 0xff. If the plaintext is really a NC2
    # packet starting with 0x13, then byte 1 is the packet counter
    # (low byte) which can be anything. So we can't discriminate at byte
    # 1 either.
    #
    # The ULTIMATE test is entropy: if the decryption is correct, the
    # decoded bytes should have LOWER entropy than the raw bytes (because
    # plaintext is structured). If entropy is unchanged, the cipher guess
    # was wrong.

    print(f"\nSuccessfully decoded: {len(decoded_entropies)} / {len(packets)}")
    if not decoded_entropies:
        return 2
    avg_raw = sum(raw_entropies) / len(raw_entropies)
    avg_dec = sum(decoded_entropies) / len(decoded_entropies)
    global_raw = entropy(bytes(all_raw))
    global_dec = entropy(bytes(all_plain))
    null_raw = sum(1 for b in all_raw if b == 0) / len(all_raw) * 100
    null_dec = sum(1 for b in all_plain if b == 0) / len(all_plain) * 100
    print(f"Per-packet avg raw entropy    : {avg_raw:.3f} bits/byte")
    print(f"Per-packet avg decoded entropy: {avg_dec:.3f} bits/byte")
    print(f"Global raw entropy    : {global_raw:.3f} bits/byte (expected ~7.99 if encrypted)")
    print(f"Global decoded entropy: {global_dec:.3f} bits/byte (expected <6 if cipher recovered plaintext)")
    print(f"Null byte % raw    : {null_raw:.2f}% (encrypted ~0.4%)")
    print(f"Null byte % decoded: {null_dec:.2f}% (plaintext often 10-30%)")

    hdr_hist = Counter(decoded_first_bytes)
    print(f"\nDecoded first-byte histogram (should be concentrated on {KNOWN_HEADERS}):")
    for b, c in sorted(hdr_hist.items(), key=lambda kv: -kv[1]):
        flag = " <-- known header" if b in KNOWN_HEADERS else ""
        print(f"  0x{b:02x}: {c}{flag}")

    if args.dump:
        print(f"\nFirst {args.dump} decoded packets:")
        for size, hdr, raw, plain in decoded_samples:
            print(f"  size={size:4d} header=0x{hdr:02x}")
            print(f"    raw   : {raw.hex()}")
            print(f"    plain : {plain.hex()}")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
