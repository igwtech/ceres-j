#!/usr/bin/env python3
"""Decode retail CharInfo multipart stream structure and compare to Ceres-J.

The CharInfo multipart (0x03→0x07) is the largest data block in the
initial burst. Retail sends 4280 bytes across 10 fragments; Ceres-J
sends ~1000 bytes across 3-6 fragments. The hypothesis is that the
client requires more complete character data to consider the session
initialized."""
import os; os.chdir('/home/javier/Documents/Projects/Neocron/ceres-j')
from pathlib import Path
import importlib.util
spec = importlib.util.spec_from_file_location('pb', 'tools/parse-burst.py')
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

def reassemble_multipart(path, direction='recv'):
    """Extract and reassemble multipart fragments from a capture."""
    wire = pb.extract_udp(Path(path), direction)
    fragments = []
    for w in wire:
        p = pb.decrypt_wire(w)
        if not p or p[0] != 0x13: continue
        parsed = pb.parse_gamedata(p)
        if not parsed: continue
        for s in parsed['subs']:
            if s['outer'] == 0x03 and s.get('reliable_type') == 0x07:
                inner = s.get('inner_data', b'')
                if len(inner) < 5: continue
                frag_idx = inner[0] | (inner[1] << 8)
                total = inner[2] | (inner[3] << 8)
                chain_key = inner[4]
                payload = inner[5:]
                fragments.append((frag_idx, total, chain_key, payload))

    if not fragments:
        return b''

    # Sort by fragment index and concatenate
    fragments.sort(key=lambda x: x[0])
    return b''.join(p for _, _, _, p in fragments)

def decode_sections(data):
    """Parse the section-based structure of CharInfo.

    CharInfo uses a section system where each section starts with:
      [section_marker_byte][section_id][size_lo][size_hi]

    But first, let's just dump the raw bytes with annotations."""
    print(f"\nTotal reassembled data: {len(data)} bytes")
    print(f"First 100 bytes hex: {data[:100].hex()}")

    # The per-fragment header is [0x00][discriminator][total_size LE4]
    # In retail, this is the SAME on every fragment. Let's check if the
    # reassembled stream starts with it or if it's stripped.
    print(f"\nByte 0: 0x{data[0]:02x}")
    print(f"Byte 1: 0x{data[1]:02x}")

    # If byte 0 is 0x00 and byte 1 is 0x01, this is the per-fragment
    # header repeated. The actual CharInfo data starts after it.
    # Let's find where the 0x22 header byte appears.
    for i in range(min(20, len(data))):
        if data[i] == 0x22:
            print(f"\nFound 0x22 (CharInfo marker) at offset {i}")
            print(f"  Bytes around it: {data[max(0,i-4):i+10].hex()}")
            break

    # Dump in 16-byte rows with ASCII
    print(f"\nFull hex dump (first 500 bytes):")
    for row in range(0, min(500, len(data)), 16):
        hex_part = ' '.join(f'{data[row+i]:02x}' if row+i < len(data) else '  '
                           for i in range(16))
        ascii_part = ''.join(chr(data[row+i]) if 32 <= data[row+i] < 127 else '.'
                            for i in range(16) if row+i < len(data))
        print(f"  {row:04x}: {hex_part}  {ascii_part}")

# Retail
print("=" * 70)
print("RETAIL ACC1_CHAR1 CharInfo multipart")
print("=" * 70)
retail_data = reassemble_multipart('strace/nc2_strace_RETAIL_ACC1_CHAR1.log')
decode_sections(retail_data)

print("\n\n")

# Also check ACC2_CHAR1 for comparison
print("=" * 70)
print("RETAIL ACC2_CHAR1 CharInfo multipart")
print("=" * 70)
retail2_data = reassemble_multipart('strace/nc2_strace_RETAIL_ACC2_CHAR1.log')
decode_sections(retail2_data)

# Ceres-J
print("\n\n")
print("=" * 70)
print("CERES-J CharInfo multipart")
print("=" * 70)
ceresj_data = reassemble_multipart('/tmp/nc2_strace.log')
decode_sections(ceresj_data)
