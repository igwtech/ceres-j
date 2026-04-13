#!/usr/bin/env python3
"""
parse-burst.py — Parse the sub-packet structure of decrypted NC2 gamedata
packets and compare retail vs Ceres-J bursts.

Each 0x13 gamedata packet contains:
  byte 0:    0x13
  bytes 1-2: counter (LE)
  bytes 3-4: counter + sessionKey (LE)
  byte 5:    first sub-packet length
  bytes 6+:  sub-packets (length-prefixed: [len][data...])

Each sub-packet's first byte is its type. Type 0x03 is the "reliable"
wrapper with its own inner structure:
  byte 0:    0x03
  bytes 1-2: sequence counter (LE)
  byte 3:    reliable sub-type
"""

from __future__ import annotations
import argparse, re, sys
from collections import Counter
from pathlib import Path

# ── Strace extraction + cipher (reuse from decrypt-retail.py) ─────────

_RE = re.compile(
    r'(recvmsg|sendmsg)\(\d+<UDP[^>]*>.*?iov_base="((?:[^"\\]|\\.)*)".*?= (\d+)$',
    re.MULTILINE,
)

def decode_strace_bytes(s):
    out = bytearray()
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if c == "\\" and i+1 < n:
            nx = s[i+1]
            if nx == "x" and i+3 < n:
                try: out.append(int(s[i+2:i+4], 16)); i += 4; continue
                except ValueError: pass
            if nx == "n": out.append(0x0A); i+=2; continue
            if nx == "r": out.append(0x0D); i+=2; continue
            if nx == "t": out.append(0x09); i+=2; continue
            if nx == "0": out.append(0x00); i+=2; continue
            if nx == "\\": out.append(0x5C); i+=2; continue
            if nx == '"': out.append(0x22); i+=2; continue
            out.append(ord(c)); i+=1; continue
        out.append(ord(c) & 0xFF); i+=1
    return bytes(out)

def extract_udp(path, direction):
    with path.open("r", errors="replace") as f: content = f.read()
    out = []
    for m in _RE.finditer(content):
        call = m.group(1)
        if direction == "recv" and call != "recvmsg": continue
        if direction == "send" and call != "sendmsg": continue
        n = int(m.group(3))
        if n <= 0: continue
        data = decode_strace_bytes(m.group(2))
        if data: out.append(data)
    return out

def lfsr_byte(state, inp):
    output = 0
    for bit in range(8):
        hi = (state >> 8) & 0xFF; lo = state & 0xFF
        fb = ((hi>>6)^(hi>>5)^(hi>>3)^lo^((inp>>bit)&1)) & 1
        state = ((state<<1)|fb) & 0xFFFF
        output |= (fb << (7-bit))
    return output, state

def decrypt_wire(wire):
    if len(wire) < 4: return None
    seed = wire[0] | (wire[1]<<8); state = seed
    k, state = lfsr_byte(state, wire[1]); len_lo = k ^ wire[2]
    k, state = lfsr_byte(state, wire[2]); len_hi = k ^ wire[3]
    dlen = (len_hi<<8)|len_lo
    plain = bytearray(); prev = wire[3]
    for i in range(min(dlen, len(wire)-4)):
        cb = wire[4+i]; k, state = lfsr_byte(state, prev)
        plain.append(k ^ cb); prev = cb
    return bytes(plain)

# ── Sub-packet names ──────────────────────────────────────────────────

RELIABLE_SUBTYPES = {
    0x01: "Resend", 0x07: "Multipart", 0x08: "ZoningEnd",
    0x0d: "TimeSync", 0x1b: "Group1B/PosUpdate", 0x1f: "GamePackets",
    0x22: "CharInfo", 0x23: "InfoResponse", 0x25: "PlayerInfo",
    0x26: "RemoveWorldItem", 0x27: "RequestWorldInfo",
    0x28: "WorldInfo", 0x2b: "CityCom", 0x2c: "StartPos",
    0x2d: "NPCData", 0x2e: "Weather", 0x2f: "UpdateModel",
    0x30: "ShortPlayerInfo", 0x31: "RequestShortPlayer",
}

OUTER_SUBTYPES = {
    0x03: "Reliable", 0x0b: "CPing", 0x0c: "TimeSync",
    0x20: "Movement", 0x2a: "RequestPos",
}

# ── Parser ────────────────────────────────────────────────────────────

def parse_gamedata(plain, len_bytes=None):
    """Parse a 0x13 gamedata packet into sub-packets.

    len_bytes: how many bytes per sub-packet length field.
        None = auto-detect (2 for retail encrypted, 1 for Ceres-J plaintext)
        1 = Ceres-J format (1-byte sub-packet lengths)
        2 = retail format (2-byte LE sub-packet lengths)
    """
    if not plain or plain[0] != 0x13: return None
    if len(plain) < 6: return None
    counter = int.from_bytes(plain[1:3], "little")
    counter_key = int.from_bytes(plain[3:5], "little")
    subs = []
    pos = 5

    if len_bytes is None:
        # Auto-detect: try 2-byte first, check if first sub-packet
        # starts with a known type. If not, fall back to 1-byte.
        if pos + 2 <= len(plain):
            test_len = plain[pos] | (plain[pos+1] << 8)
            if (pos + 2 + test_len <= len(plain) and test_len > 0
                    and test_len < len(plain)
                    and pos + 2 + test_len >= pos + 3
                    and plain[pos+2] in (0x03, 0x0b, 0x0c, 0x20, 0x2a)):
                len_bytes = 2
            else:
                len_bytes = 1
        else:
            len_bytes = 1

    while pos < len(plain):
        if len_bytes == 2:
            if pos + 2 > len(plain): break
            sub_len = plain[pos] | (plain[pos+1] << 8)
            pos += 2
        else:
            sub_len = plain[pos]
            pos += 1
        if sub_len == 0 or pos + sub_len > len(plain): break
        sub_data = plain[pos:pos+sub_len]; pos += sub_len
        outer_type = sub_data[0] if sub_data else 0
        info = {"outer": outer_type, "len": sub_len, "data": sub_data}
        if outer_type == 0x03 and len(sub_data) >= 4:
            seq = int.from_bytes(sub_data[1:3], "little")
            rel_type = sub_data[3]
            info["reliable_seq"] = seq
            info["reliable_type"] = rel_type
            info["reliable_name"] = RELIABLE_SUBTYPES.get(rel_type, f"0x{rel_type:02x}")
            info["inner_data"] = sub_data[4:]
        subs.append(info)
    return {"counter": counter, "counter_key": counter_key, "subs": subs,
            "len_bytes": len_bytes}

# ── Main ──────────────────────────────────────────────────────────────

def main(argv):
    ap = argparse.ArgumentParser(description="Parse NC2 gamedata sub-packets")
    ap.add_argument("--retail", "-r", type=Path, required=True,
                    help="Retail strace (encrypted)")
    ap.add_argument("--ceres", "-c", type=Path, default=None,
                    help="Ceres-J strace (plaintext)")
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args(argv[1:])

    # ── Decrypt and parse retail ──────────────────────────────────────
    retail_wire = extract_udp(args.retail, "recv")
    print(f"Retail: {len(retail_wire)} wire packets")
    retail_plains = []
    for w in retail_wire:
        p = decrypt_wire(w)
        if p: retail_plains.append(p)
    print(f"Retail: {len(retail_plains)} decrypted")

    # ── Parse Ceres-J (plaintext, no encryption header) ───────────────
    ceres_plains = []
    if args.ceres:
        ceres_wire = extract_udp(args.ceres, "recv")
        print(f"Ceres-J: {len(ceres_wire)} plaintext packets")
        ceres_plains = ceres_wire  # already plaintext

    # ── Parse and display retail ──────────────────────────────────────
    print(f"\n{'='*72}")
    print("RETAIL BURST — sub-packet breakdown")
    print(f"{'='*72}\n")

    retail_types = Counter()
    limit = args.limit if args.limit > 0 else len(retail_plains)
    for i, plain in enumerate(retail_plains[:limit]):
        parsed = parse_gamedata(plain)
        if not parsed:
            print(f"  pkt#{i+1:3d} {len(plain):4d}B  (not gamedata, byte0=0x{plain[0]:02x})")
            continue
        cnt = parsed["counter"]
        subs = parsed["subs"]
        sub_summary = []
        for s in subs:
            if s["outer"] == 0x03:
                name = s.get("reliable_name", "?")
                rtype = s.get("reliable_type", 0)
                inner_len = len(s.get("inner_data", b""))
                sub_summary.append(f"0x03→{name}({inner_len}B)")
                retail_types[f"0x03→0x{rtype:02x} {name}"] += 1
            else:
                oname = OUTER_SUBTYPES.get(s["outer"], f"0x{s['outer']:02x}")
                sub_summary.append(f"{oname}({s['len']}B)")
                retail_types[f"0x{s['outer']:02x} {oname}"] += 1
        print(f"  pkt#{i+1:3d} cnt={cnt:4d} {len(plain):4d}B  "
              f"[{len(subs)} sub] {', '.join(sub_summary)}")

    print(f"\n{'='*72}")
    print("RETAIL sub-packet type histogram")
    print(f"{'='*72}\n")
    for typ, count in sorted(retail_types.items(), key=lambda x: -x[1]):
        print(f"  {count:4d}x  {typ}")

    # ── Parse and display Ceres-J ─────────────────────────────────────
    if ceres_plains:
        print(f"\n{'='*72}")
        print("CERES-J BURST — sub-packet breakdown")
        print(f"{'='*72}\n")
        ceres_types = Counter()
        for i, plain in enumerate(ceres_plains[:limit]):
            if not plain: continue
            if plain[0] == 0x04:
                print(f"  pkt#{i+1:3d}  {len(plain):4d}B  UDPAlive (0x04)")
                ceres_types["0x04 UDPAlive"] += 1
                continue
            parsed = parse_gamedata(plain)
            if not parsed:
                print(f"  pkt#{i+1:3d}  {len(plain):4d}B  (byte0=0x{plain[0]:02x})")
                continue
            cnt = parsed["counter"]
            subs = parsed["subs"]
            sub_summary = []
            for s in subs:
                if s["outer"] == 0x03:
                    name = s.get("reliable_name", "?")
                    rtype = s.get("reliable_type", 0)
                    inner_len = len(s.get("inner_data", b""))
                    sub_summary.append(f"0x03→{name}({inner_len}B)")
                    ceres_types[f"0x03→0x{rtype:02x} {name}"] += 1
                else:
                    oname = OUTER_SUBTYPES.get(s["outer"], f"0x{s['outer']:02x}")
                    sub_summary.append(f"{oname}({s['len']}B)")
                    ceres_types[f"0x{s['outer']:02x} {oname}"] += 1
            print(f"  pkt#{i+1:3d} cnt={cnt:4d} {len(plain):4d}B  "
                  f"[{len(subs)} sub] {', '.join(sub_summary)}")

        print(f"\n{'='*72}")
        print("CERES-J sub-packet type histogram")
        print(f"{'='*72}\n")
        for typ, count in sorted(ceres_types.items(), key=lambda x: -x[1]):
            print(f"  {count:4d}x  {typ}")

        # ── Diff ──────────────────────────────────────────────────────
        print(f"\n{'='*72}")
        print("DIFF — types retail has that Ceres-J doesn't")
        print(f"{'='*72}\n")
        all_types = set(retail_types) | set(ceres_types)
        for typ in sorted(all_types):
            r = retail_types.get(typ, 0)
            c = ceres_types.get(typ, 0)
            if r > 0 and c == 0:
                print(f"  MISSING IN CERES:  {r:4d}x  {typ}")
            elif c > 0 and r == 0:
                print(f"  EXTRA IN CERES:    {c:4d}x  {typ}")
            elif r != c:
                print(f"  COUNT DIFF:        retail={r:4d} ceres={c:4d}  {typ}")

    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv))
