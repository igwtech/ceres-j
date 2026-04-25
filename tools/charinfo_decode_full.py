#!/usr/bin/env python3
"""Reassemble retail's CharInfo multipart stream and decode every section
byte-by-byte. Produce a side-by-side hex+annotated dump for retail vs
Ceres-J so we can see exactly which section's layout we're getting
wrong."""
import os, sys, struct
os.chdir('/home/javier/Documents/Projects/Neocron/ceres-j')
from pathlib import Path
import importlib.util

spec = importlib.util.spec_from_file_location('pb', 'tools/parse-burst.py')
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)


def reassemble_multipart(path: str, direction: str = 'recv') -> bytes:
    """Reassemble all 0x03→0x07 multipart fragments from a strace log
    in their declared frag_idx order."""
    wire = pb.extract_udp(Path(path), direction)
    fragments = []
    for w in wire:
        p = pb.decrypt_wire(w)
        if not p or p[0] != 0x13:
            continue
        parsed = pb.parse_gamedata(p, len_bytes=2)
        if not parsed:
            continue
        for s in parsed['subs']:
            if s['outer'] == 0x03 and s.get('reliable_type') == 0x07:
                inner = s.get('inner_data', b'')
                if len(inner) < 5:
                    continue
                frag_idx = inner[0] | (inner[1] << 8)
                payload = inner[5:]
                fragments.append((frag_idx, payload))
    fragments.sort(key=lambda x: x[0])
    return b''.join(p for _, p in fragments)


def parse_charinfo(data: bytes) -> dict:
    """Return {section_id: bytes} given the reassembled stream.

    Layout (per CharInfo.java + matched against retail capture):
      [0x22 0x02 0x01]   3-byte header
      [section1_data]    (no marker — data BEFORE first newSection() call
                          is dumped as section 1, because the constructor
                          only emits a section marker when transitioning
                          OUT of a non-zero section)
      [section_id][size_lo][size_hi][section_data] ...
    """
    if len(data) < 3:
        return {}
    if data[0:3] != b'\x22\x02\x01':
        # try to find it
        for i in range(min(20, len(data))):
            if data[i:i+3] == b'\x22\x02\x01':
                data = data[i:]
                break
        else:
            return {}

    sections = {}
    pos = 3

    # Section 1 has NO leading marker — it's the bytes between the
    # 3-byte header and the first encountered (id, size_lo, size_hi)
    # marker where id is in 1..13 and the size is plausible.
    sec1_start = pos
    while pos < len(data) - 2:
        sid = data[pos]
        size = data[pos+1] | (data[pos+2] << 8)
        if 1 <= sid <= 13 and 0 < size <= len(data) - pos - 3:
            # Looks like a marker.
            sections[1] = data[sec1_start:pos]
            break
        pos += 1
    else:
        sections[1] = data[sec1_start:]
        return sections

    # Now walk the rest as length-prefixed sections.
    while pos < len(data) - 2:
        sid = data[pos]
        size = data[pos+1] | (data[pos+2] << 8)
        if sid < 1 or sid > 13:
            break
        if size > len(data) - pos - 3:
            sections[sid] = data[pos+3:]
            break
        sections[sid] = data[pos+3:pos+3+size]
        pos += 3 + size
    return sections


def hexlines(data: bytes, base: int = 0, width: int = 16) -> list[str]:
    out = []
    for i in range(0, len(data), width):
        chunk = data[i:i+width]
        hex_part = ' '.join(f'{b:02x}' for b in chunk)
        ascii_part = ''.join(chr(b) if 32 <= b < 127 else '.' for b in chunk)
        out.append(f'{base+i:04x}: {hex_part:<{width*3}}  {ascii_part}')
    return out


def annotate_section1(d: bytes) -> list[str]:
    if len(d) < 10:
        return ['  (too short to annotate as section 1)']
    return [
        f'  byte 0:    {d[0]:#04x}        (literal 0xfa)',
        f'  byte 1:    {d[1]:#04x}        profession',
        f'  bytes 2-3: {d[1]|d[2]<<8 if len(d)>=4 else 0:#06x}     transaction id',
        f'  bytes 4-7: {struct.unpack("<I", d[4:8])[0]:>10}  char id',
        f'  bytes 8-9: {struct.unpack("<H", d[8:10])[0]:>10}  literal 17',
    ]


def annotate_section2(d: bytes) -> list[str]:
    if len(d) < 28:
        return ['  (too short to annotate as section 2)']
    s = [
        f'  byte 0:    {d[0]:#04x}      header (literal 0x04)',
        f'  byte 1:    {d[1]:#04x}      header (literal 0x04)',
    ]
    pairs = [
        ('cur HP',          2),
        ('max HP',          4),
        ('cur PSI',         6),
        ('max PSI',         8),
        ('cur STA',        10),
        ('max STA',        12),
        ('unknown_cur',    14),
        ('unknown_max',    16),
        ('lit 101 (a)',    18),
        ('lit 101 (b)',    20),
        ('lit 101 (c)',    22),
    ]
    for label, off in pairs:
        v = struct.unpack('<H', d[off:off+2])[0]
        s.append(f'  bytes {off}-{off+1}: {v:>5}     {label}')
    s += [
        f'  byte 24:   {d[24]:#04x}       synaptic',
        f'  byte 25:   {d[25]:#04x}       literal 128',
        f'  byte 26:   {d[26]:#04x}       padding',
        f'  byte 27:   {d[27]:#04x}       padding',
    ]
    return s


def annotate_section3(d: bytes) -> list[str]:
    """Section 3 = skills.
       Header: [0x06][0x09]
       Then 6 skill blocks of 9 bytes each:
         [lvl(1)][pts_lo(1)][pts_hi(1)][xp(4LE)][rate(1)][max(1)]
       Then 4 bytes WoC trailer.
    """
    if len(d) < 60:
        return ['  (too short for section 3)']
    out = [f'  bytes 0-1: {d[0]:#04x} {d[1]:#04x}     header (6 skills, 9B each)']
    skill_names = ['STR', 'DEX', 'CON', 'INT', 'PSI', 'X???']
    pos = 2
    for i in range(6):
        lvl = d[pos]
        pts = struct.unpack('<H', d[pos+1:pos+3])[0]
        xp = struct.unpack('<i', d[pos+3:pos+7])[0]
        rate = d[pos+7]
        mx = d[pos+8]
        out.append(f'  +{pos:02d} {skill_names[i]:>4}: lvl={lvl:>3} pts={pts:>5} '
                   f'xp={xp:>10} rate={rate:#04x} max={mx:#04x}')
        pos += 9
    out.append(f'  +{pos:02d} WoC trailer: {" ".join(f"{b:02x}" for b in d[pos:pos+4])}')
    return out


def annotate_section4(d: bytes) -> list[str]:
    """Section 4 = subskills. Header [0x2e][0x02], then 0x2e (46) entries
    of 2 bytes each = 92 bytes. Total 94 bytes."""
    if len(d) < 94:
        return ['  (too short for section 4)']
    out = [f'  bytes 0-1: {d[0]:#04x} {d[1]:#04x}     header (46 subskills, 2B each)']
    # Names from PlayerCharacter / TinNS docs; NC2 has 46 subskills:
    names = ['MC','HC','TRA','PC','RC','TC','VHC','AGL','REP','REC',
             'RCL','ATL','END','FOR','FIR','ENR','XRR','POR','HLT','HCK',
             'BRT','PSU','WEP','CST','RES','IMP','PPU','APU','MST','PPW',
             'PSR','WPW','sub32','sub33','sub34','sub35','sub36','sub37',
             'sub38','sub39','sub40','sub41','sub42','sub43','sub44','sub45']
    for i in range(46):
        off = 2 + i*2
        lvl = d[off]
        pts = d[off+1]
        if lvl or pts:
            out.append(f'  +{off:02d} {names[i]:>5}: lvl={lvl:>3} pts={pts:>3}')
    return out


def annotate_section5(d: bytes) -> list[str]:
    """Section 5 = F2 inventory. Layout: [count_lo(1)][count_hi(1)] then
    for each item one PACKET_CHARINFOF2 blob whose size is encoded in
    the data itself (item-type-dependent). We only print the count and
    a hex dump of the first 64 bytes."""
    if len(d) < 2:
        return [f'  (empty: {len(d)} bytes)']
    n = d[0] | (d[1] << 8)
    out = [f'  count: {n} items', f'  total bytes: {len(d)}']
    if n > 0 and len(d) > 2:
        out.append('  first 64 bytes of item data:')
        for line in hexlines(d[2:2+64]):
            out.append('    ' + line)
    return out


def render_section(sid: int, d: bytes) -> list[str]:
    annotators = {
        1: annotate_section1,
        2: annotate_section2,
        3: annotate_section3,
        4: annotate_section4,
        5: annotate_section5,
    }
    out = [f'-- section {sid:>2} ({len(d)} bytes) ' + '-' * 40]
    if sid in annotators:
        out.extend(annotators[sid](d))
    out.append('  raw:')
    for line in hexlines(d[:96]):
        out.append('    ' + line)
    if len(d) > 96:
        out.append(f'    ... {len(d) - 96} more bytes')
    return out


def main():
    paths = {
        'RETAIL': 'strace/nc2_strace_RETAIL_DEATH.log',
        'CERES-J': '/tmp/nc2_strace.log',
    }
    if len(sys.argv) > 1:
        paths['CERES-J'] = sys.argv[1]

    parsed = {}
    for label, path in paths.items():
        if not os.path.exists(path):
            print(f'(missing {label} strace at {path})')
            continue
        data = reassemble_multipart(path, 'recv')
        sections = parse_charinfo(data)
        parsed[label] = (data, sections)
        print(f'\n{"="*72}\n{label} CharInfo  ({len(data)} bytes reassembled)'
              f'\n{"="*72}')
        for sid in sorted(sections.keys()):
            for line in render_section(sid, sections[sid]):
                print(line)

    # Diff per section
    if len(parsed) == 2:
        rs = parsed['RETAIL'][1]
        cs = parsed['CERES-J'][1]
        all_ids = sorted(set(rs.keys()) | set(cs.keys()))
        print(f'\n{"="*72}\nSECTION DIFF (retail vs ceres-j)\n{"="*72}')
        for sid in all_ids:
            r = rs.get(sid, b'')
            c = cs.get(sid, b'')
            if r == c:
                print(f'  section {sid}: identical ({len(r)} bytes)')
                continue
            print(f'  section {sid}: retail={len(r)}B  ceres-j={len(c)}B  '
                  f'first_diff_offset='
                  f'{next((i for i in range(min(len(r),len(c))) if r[i]!=c[i]), min(len(r),len(c)))}')


if __name__ == '__main__':
    main()
