#!/usr/bin/env python3
"""Audit section 2 field-by-field across every retail capture we have,
plus Ceres-J's last capture if /tmp/nc2_strace.log exists.

Goal: figure out which bytes are LITERAL constants and which depend on
the character. The pre-existing CharInfo.java has 'literal 101' comments
on bytes 18-23 that don't match retail's actual values — we want to
know what those 6 bytes really are.
"""
import os, sys, struct, glob
os.chdir('/home/javier/Documents/Projects/Neocron/ceres-j')
import importlib.util
spec = importlib.util.spec_from_file_location('cf', 'tools/charinfo_decode_full.py')
cf = importlib.util.module_from_spec(spec); spec.loader.exec_module(cf)

paths = sorted(glob.glob('strace/nc2_strace_RETAIL_*.log'))
if os.path.exists('/tmp/nc2_strace.log'):
    paths.append('/tmp/nc2_strace.log')

field_layout = [
    ('header[0]',   0,  1),
    ('header[1]',   1,  1),
    ('cur_HP',      2,  2),
    ('max_HP',      4,  2),
    ('cur_PSI',     6,  2),
    ('max_PSI',     8,  2),
    ('cur_STA',    10,  2),
    ('max_STA',    12,  2),
    ('field@14',   14,  2),
    ('field@16',   16,  2),
    ('field@18',   18,  2),
    ('field@20',   20,  2),
    ('field@22',   22,  2),
    ('synaptic',   24,  1),
    ('field@25',   25,  1),
    ('field@26',   26,  1),
    ('field@27',   27,  1),
]

print(f'{"capture":<48} {"|".join(f"{n:>10}" for n,_,_ in field_layout)}')
print('-' * 200)
for p in paths:
    try:
        data = cf.reassemble_multipart(p, 'recv')
        secs = cf.parse_charinfo(data)
        s2 = secs.get(2, b'')
        if len(s2) < 28:
            print(f'{os.path.basename(p):<48} (no section 2)')
            continue
        cells = []
        for name, off, sz in field_layout:
            if sz == 1:
                v = s2[off]
            elif sz == 2:
                v = struct.unpack('<H', s2[off:off+2])[0]
            else:
                v = int.from_bytes(s2[off:off+sz], 'little')
            cells.append(f'{v:>10}')
        print(f'{os.path.basename(p):<48} {"|".join(cells)}')
    except Exception as e:
        print(f'{os.path.basename(p):<48} ERROR: {e}')
