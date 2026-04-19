#!/usr/bin/env python3
"""Compare CharInfo sections byte-by-byte between retail and Ceres-J.
Focus on the section STRUCTURE, not the values — find missing sections
and size mismatches."""
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
                payload = inner[5:]
                fragments.append((frag_idx, payload))
    fragments.sort(key=lambda x: x[0])
    return b''.join(p for _, p in fragments)

def parse_sections(data):
    """Parse CharInfo section structure from PacketBuilderUDP130307.

    The data after the per-fragment header starts with 0x22 0x02 0x01.
    Sections are written by newSection(id):
      1. Write previous section's data to 'complete' buffer
      2. Write [current_section_id][size_lo][size_hi] header
      3. Start new section's data

    So the first section (implicit, no header) is everything from
    0x22 0x02 0x01 until the first section marker.

    The section marker format in 'complete' is:
      [section_id_byte][size_lo][size_hi]
    where size = number of bytes written in that section.
    """
    # Find 0x22 marker
    start = -1
    for i in range(min(20, len(data))):
        if data[i] == 0x22:
            start = i
            break
    if start < 0:
        return []

    ci = data[start:]

    # The PacketBuilderUDP130307.newSection() writes:
    # complete.write(currentSection); // section id of the JUST-FINISHED section
    # complete.write(this.count);     // data byte count lo
    # complete.write(this.count >> 8); // data byte count hi
    # Then appends the data of the just-finished section.
    # Then resets count for the new section.
    #
    # So the layout in 'complete' is:
    # [initial_data_bytes...][section1_id][section1_size_lo][section1_size_hi][section1_data...]
    # [section2_id][section2_size_lo][section2_size_hi][section2_data...]...
    #
    # The initial data (before any section marker) is the bytes written
    # before the first newSection() call. In CharInfo.java, this is:
    # 0x22 0x02 0x01 (3 bytes)
    #
    # After newSection(1), the header for section 0 (implicit) is written:
    # Wait — the first newSection(1) writes currentSection (which was 0
    # from the constructor) as the section id. But looking at the code:
    #
    # public PacketBuilderUDP130307(Player pl) {
    #     this.pl = pl;
    # }
    # public void newSection(int i) {
    #     if (currentSection != 0) {
    #         complete.write(currentSection);
    #         complete.write(this.count);
    #         complete.write(this.count >> 8);
    #     }
    #     complete.write(buf, 0, count);
    #     count = 0;
    #     currentSection = (byte) i;
    # }
    #
    # So: first call to newSection(1):
    #   currentSection == 0, so skip the header write
    #   write buf[0..count] to complete (this is the 0x22 0x02 0x01 + initial data)
    #   set currentSection = 1
    #
    # Second call to newSection(2):
    #   currentSection == 1, so write [0x01][size_lo][size_hi]
    #   write buf[0..count] to complete (section 1 data)
    #   set currentSection = 2
    #
    # So the layout is:
    # [initial_data][0x01][s1_size][s1_data][0x02][s2_size][s2_data]...

    # Let's manually parse this
    sections = []
    pos = 0

    # Initial data: 0x22 0x02 0x01 header + whatever comes before first section marker
    # The first section marker should be 0x01 (section 1) with a 2-byte size
    # Look for it by checking if byte value is a valid section id (1-13)
    # followed by a 2-byte size that makes sense

    # Actually, let's just read the CharInfo.java source to know the exact structure:
    # write(0x22); write(0x02); write(0x01); // 3 bytes
    # Then newSection(1) → appends these 3 bytes to complete, sets currentSection=1
    # Then writes: write(0xfa); write(profession); writeShort(transactionID);
    #              writeInt(charID); writeShort(17);
    # That's 1+1+2+4+2 = 10 bytes for section 1
    # Then newSection(2) → writes [0x01][10_lo][10_hi] + section1_data
    #
    # So the complete buffer starts with:
    # 0x22 0x02 0x01 | 0x01 0x0a 0x00 | [10 bytes of section 1]
    # | 0x02 [size_lo] [size_hi] | [section 2 data] | ...

    # Parse: initial 3 bytes, then section markers
    if len(ci) < 3:
        return []

    print(f"  Initial header: {ci[:3].hex()}")
    pos = 3

    while pos < len(ci) - 2:
        section_id = ci[pos]
        size = ci[pos+1] | (ci[pos+2] << 8)
        # Validate: section_id should be 1-13 and size should be reasonable
        if section_id < 1 or section_id > 13:
            break
        if size > len(ci) - pos - 3:
            # Might be end of data
            sections.append((section_id, size, ci[pos+3:pos+3+min(size, len(ci)-pos-3)]))
            break
        section_data = ci[pos+3:pos+3+size]
        sections.append((section_id, size, section_data))
        pos += 3 + size

    return sections

# Parse both
print("=" * 70)
print("RETAIL CharInfo sections")
print("=" * 70)
retail_data = reassemble_multipart('strace/nc2_strace_RETAIL_DEATH.log')
retail_sections = parse_sections(retail_data)
for sid, size, data in retail_sections:
    preview = data[:16].hex() if data else ''
    print(f"  Section {sid:2d} (0x{sid:02x}): {size:5d} bytes  preview: {preview}")

print()
print("=" * 70)
print("CERES-J CharInfo sections")
print("=" * 70)
ceresj_data = reassemble_multipart('/tmp/nc2_strace.log')
ceresj_sections = parse_sections(ceresj_data)
for sid, size, data in ceresj_sections:
    preview = data[:16].hex() if data else ''
    print(f"  Section {sid:2d} (0x{sid:02x}): {size:5d} bytes  preview: {preview}")

# Diff
print()
print("=" * 70)
print("SECTION SIZE COMPARISON")
print("=" * 70)
retail_map = {s[0]: s[1] for s in retail_sections}
ceresj_map = {s[0]: s[1] for s in ceresj_sections}
all_ids = sorted(set(list(retail_map.keys()) + list(ceresj_map.keys())))
print(f"{'Section':>10} {'Retail':>8} {'CeresJ':>8} {'Delta':>8}")
for sid in all_ids:
    r = retail_map.get(sid, 0)
    c = ceresj_map.get(sid, 0)
    marker = " <<<" if r > 0 and c == 0 else (" !!!" if abs(r-c) > 10 else "")
    print(f"  {sid:2d} (0x{sid:02x}) {r:>8} {c:>8} {c-r:>+8}{marker}")
