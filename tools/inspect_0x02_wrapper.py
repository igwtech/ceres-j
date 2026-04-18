#!/usr/bin/env python3
"""Decode 0x02 wrapper sub-packets from retail — what data do they carry?"""
import os; os.chdir('/home/javier/Documents/Projects/Neocron/ceres-j')
from pathlib import Path
import importlib.util
spec = importlib.util.spec_from_file_location('pb', 'tools/parse-burst.py')
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

for cap_name, cap_path in [
    ("ACC1_CHAR1", "strace/nc2_strace_RETAIL_ACC1_CHAR1.log"),
    ("ACC2_CHAR1", "strace/nc2_strace_RETAIL_ACC2_CHAR1.log"),
]:
    print(f"\n{'='*60}\n{cap_name}\n{'='*60}")
    wire = pb.extract_udp(Path(cap_path), 'recv')
    for w in wire:
        p = pb.decrypt_wire(w)
        if not p or p[0] != 0x13: continue
        parsed = pb.parse_gamedata(p, len_bytes=2)
        if not parsed: continue
        for s in parsed['subs']:
            if s['outer'] == 0x02:
                d = s['data']
                # 0x02 wrapper: [0x02][seq_lo][seq_hi][inner_sub_type][inner_data...]
                if len(d) >= 4:
                    seq = d[1] | (d[2] << 8)
                    inner_type = d[3]
                    inner_name = pb.RELIABLE_SUBTYPES.get(inner_type, f'0x{inner_type:02x}')
                    inner_data = d[4:]
                    print(f"  0x02→0x{inner_type:02x} {inner_name} seq={seq} inner={len(inner_data)}B: {inner_data[:30].hex()}")
