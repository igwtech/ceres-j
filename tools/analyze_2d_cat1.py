#!/usr/bin/env python3
"""analyze_2d_cat1.py — deep per-sub-action structure for 0x03/0x2d.

For each (category, sub-action) of interest, collect every modal-length
(55B) sample across all 17 retail captures and emit:
  - per-offset: invariant byte (value) OR 'var' with distinct-count
  - LE32 float interpretation per 4-byte window (median magnitude)
  - which offsets hold the recurring entity-handle pattern
    `xx xx 2f 01` / `xx xx ?? 0c` (Neocron entity refs end 2f01/...0c)
  - 6 raw samples spread across distinct captures

Run after extract_2d_layouts.py (same decrypt path).
"""
from __future__ import annotations
import struct, sys, glob, statistics
from collections import defaultdict, Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")
burst_mod = import_module("parse-burst")
from scapy.all import PcapReader, IP, UDP

STRACE = "/home/javier/Documents/Projects/Neocron/ceres-j/strace"
EXCLUDE = ("CERESJ", "RETAIL_RETAIL_PLAZA_CROSSZONE_20260514")

# (cat, sub) groups to deep-analyze
TARGETS = set()
for s in (0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08,0x09,0x0a,0x0b,
          0x0c,0x0d,0x0e,0x0f,0x10,0x11,0x12,0x13,0x14):
    TARGETS.add((0x0001, s))
for s in (0xdb,0xee,0xf4,0xbd,0xe7,0xfb,0xfc,0x63):
    TARGETS.add((0x0003, s))


def server_ip(pcap):
    peers = Counter()
    with PcapReader(str(pcap)) as pr:
        for i, pkt in enumerate(pr):
            if i > 5000: break
            if IP not in pkt: continue
            for ip in (pkt[IP].src, pkt[IP].dst):
                if not (ip.startswith("127.") or ip.startswith("192.168.")
                        or ip.startswith("172.")):
                    peers[ip] += 1
    return peers.most_common(1)[0][0] if peers else None


def collect():
    caps = sorted(glob.glob(STRACE + "/nc2_strace_RETAIL_*.pcap"))
    caps = [c for c in caps if not any(x in c for x in EXCLUDE)]
    groups = defaultdict(list)  # (cat,sub) -> [(body, capname)]
    for c in caps:
        name = Path(c).stem.replace("nc2_strace_RETAIL_", "")
        sip = server_ip(Path(c))
        if not sip: continue
        with PcapReader(c) as pr:
            for pkt in pr:
                if IP not in pkt or UDP not in pkt: continue
                if pkt[IP].src != sip: continue
                pl = bytes(pkt[UDP].payload)
                if not pl: continue
                r = decrypt_mod.decrypt_wire_packet(pl)
                if not r: continue
                plain, _ = r
                if not plain or plain[0] != 0x13: continue
                pg = burst_mod.parse_gamedata(plain, len_bytes=2)
                if not pg: continue
                for s in pg["subs"]:
                    if s.get("reliable_type") != 0x2d: continue
                    body = bytes([0x2d]) + bytes(s.get("inner_data", b""))
                    if len(body) < 4: continue
                    key = (body[2] | (body[3] << 8), body[1])
                    if key in TARGETS:
                        groups[key].append((body, name))
    return groups


def f32(b):
    v = struct.unpack("<f", b)[0]
    return v


def main():
    groups = collect()
    for (cat, sub) in sorted(groups, key=lambda k: (k[0], k[1])):
        items = groups[(cat, sub)]
        bodies = [b for b, _ in items if len(b) == 55]
        if len(bodies) < 20:
            continue
        caps = sorted(set(n for b, n in items if len(b) == 55))
        print(f"\n=== cat=0x{cat:04x} sub=0x{sub:02x}  "
              f"n55={len(bodies)}/{len(items)}  {len(caps)} caps "
              f"({','.join(c[:14] for c in caps[:6])}) ===")
        # per-offset
        line = []
        for off in range(55):
            vals = Counter(b[off] for b in bodies)
            if len(vals) == 1:
                line.append("%02x" % next(iter(vals)))
            else:
                top, tc = vals.most_common(1)[0]
                frac = tc / len(bodies)
                if frac >= 0.97:
                    line.append("%02x*" % top)   # near-invariant
                else:
                    line.append("v%d" % min(len(vals), 99))
        for r in range(0, 55, 11):
            print("  [%2d] %s" % (r, " ".join(
                f"{x:>4}" for x in line[r:r+11])))
        # LE32 float windows: report ones where >=80% look like
        # plausible world floats AND not constant
        for off in range(0, 52):
            col_var = any("v" in line[off+k] for k in range(4))
            if not col_var:
                continue
            vs = [f32(b[off:off+4]) for b in bodies]
            good = [v for v in vs if v == v and (v == 0.0
                    or 1e-2 < abs(v) < 5e6)]
            if len(good) >= 0.8 * len(vs) and good:
                mags = sorted(abs(v) for v in good if v != 0)
                med = mags[len(mags)//2] if mags else 0.0
                print(f"  float@{off}: ~{med:.1f} "
                      f"ex={['%.2f'%v for v in vs[:3]]}")
        # entity-ref scan: offsets where bytes [off+2:off+4] frequently
        # == 2f 01 (the recurring NC entity-handle high half)
        for off in range(0, 52):
            hits = sum(1 for b in bodies
                       if b[off+2] == 0x2f and b[off+3] == 0x01)
            if hits >= 0.5 * len(bodies):
                print(f"  entity-ref@{off}: {hits}/{len(bodies)} "
                      f"end in 2f01 (LE32 handle)")
        # samples from distinct captures
        seen = set()
        for b, nm in items:
            if len(b) != 55 or nm in seen: continue
            seen.add(nm)
            print(f"  [{nm[:18]:18}] {b.hex()}")
            if len(seen) >= 5: break


if __name__ == "__main__":
    main()
