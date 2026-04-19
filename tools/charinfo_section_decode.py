#!/usr/bin/env python3
"""Byte-level decode of CharInfo multipart sections.
Compare retail vs Ceres-J section by section."""
import os, struct; os.chdir('/home/javier/Documents/Projects/Neocron/ceres-j')
from pathlib import Path
import importlib.util
spec = importlib.util.spec_from_file_location('pb', 'tools/parse-burst.py')
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

def reassemble_multipart(path, direction='recv'):
    wire = pb.extract_udp(Path(path), direction)
    fragments = []
    for w in wire:
        p = pb.decrypt_wire(w)
        if not p or p[0] != 0x13: continue
        parsed = pb.parse_gamedata(p, len_bytes=2)
        if not parsed: continue
        for s in parsed['subs']:
            if s['outer'] == 0x03 and s.get('reliable_type') == 0x07:
                inner = s.get('inner_data', b'')
                if len(inner) < 5: continue
                frag_idx = inner[0] | (inner[1] << 8)
                payload = inner[5:]  # skip frag_idx(2) + total(2) + chain_key(1)
                fragments.append((frag_idx, payload))
    fragments.sort(key=lambda x: x[0])
    return b''.join(p for _, p in fragments)

def decode_charinfo_sections(data, label):
    """Parse the section-based CharInfo structure.

    The reassembled multipart data has a 6-byte per-fragment header
    repeated for each fragment: [0x00][discriminator][total_size LE4].
    After stripping these, the actual CharInfo data starts with:
    0x22 0x02 0x01 (CharsysInfo header)

    Sections are delimited by newSection() calls in CharInfo.java:
    Each section starts with [section_marker][section_id][size_lo][size_hi]
    written by PacketBuilderUDP130307.newSection().
    """
    print(f"\n{'='*70}")
    print(f"{label}: {len(data)} bytes reassembled")
    print(f"{'='*70}")

    # The first 6 bytes of each fragment are the per-fragment header
    # [0x00][0x01][total_size LE4]. Since fragments are concatenated,
    # we need to strip the header from each fragment's contribution.
    # But since we concatenated raw payloads, the headers are embedded.

    # Find the 0x22 marker
    start = -1
    for i in range(min(20, len(data))):
        if data[i] == 0x22:
            start = i
            break

    if start < 0:
        print("  ERROR: 0x22 marker not found")
        return

    print(f"  Per-fragment header: {data[:start].hex()}")
    print(f"  CharInfo starts at offset {start}")

    # The CharInfo data structure (from CharInfo.java):
    # Header: 0x22 0x02 0x01
    # Then sections written by newSection(id):
    #   Each newSection writes: [prev_section_id][size_lo][size_hi]
    #   then starts new data

    # Let's just dump the raw data in aligned rows with section markers
    ci = data[start:]
    print(f"  CharInfo data: {len(ci)} bytes")
    print(f"  Header: {ci[:3].hex()} ({'0x22 0x02 0x01 = CharsysInfo' if ci[:3] == bytes([0x22,0x02,0x01]) else 'UNEXPECTED'})")

    # Dump first 200 bytes in 16-byte rows
    print(f"\n  Hex dump (first 300 bytes):")
    for row in range(0, min(300, len(ci)), 16):
        hex_part = ' '.join(f'{ci[row+i]:02x}' if row+i < len(ci) else '  '
                           for i in range(16))
        ascii_part = ''.join(chr(ci[row+i]) if 32 <= ci[row+i] < 127 else '.'
                            for i in range(16) if row+i < len(ci))
        print(f"  {row:04x}: {hex_part}  {ascii_part}")

    # Try to find section boundaries by looking for the newSection pattern
    # In PacketBuilderUDP130307, newSection(id) writes:
    #   complete.write(currentSection); // previous section marker byte
    #   complete.write(this.count);     // size lo
    #   complete.write(this.count >> 8); // size hi
    # Then the new section's data follows.
    #
    # Section IDs used in CharInfo.java:
    # 1 (profession), 2 (pools), 3 (skills), 4 (subskills),
    # 5 (F2 inventory), 6 (QB), 7 (unknown), 0x0c (gogu),
    # 8 (buddies/GR), 9 (factions), 0x0a (clan), 0x0b (unknown),
    # 0x0d (repeat profession)

    print(f"\n  Looking for section markers...")
    # The section marker byte appears where a section ID byte is
    # followed by a 2-byte size that makes sense
    for i in range(3, len(ci) - 2):
        section_id = ci[i]
        size = ci[i+1] | (ci[i+2] << 8)
        if section_id in (1, 2, 3, 4, 5, 6, 7, 8, 9, 0x0a, 0x0b, 0x0c, 0x0d):
            if 1 <= size <= 2000 and i + 3 + size <= len(ci) + 50:
                next_bytes = ci[i+3:i+3+min(8, size)].hex() if i+3 < len(ci) else ''
                print(f"    offset {i:4d}: section={section_id:2d} (0x{section_id:02x}) size={size:4d}  next8={next_bytes}")

# Decode both
retail_data = reassemble_multipart('strace/nc2_strace_RETAIL_DEATH.log')
decode_charinfo_sections(retail_data, "RETAIL (death capture)")

ceresj_data = reassemble_multipart('/tmp/nc2_strace.log')
decode_charinfo_sections(ceresj_data, "CERES-J (latest)")
