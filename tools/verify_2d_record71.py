#!/usr/bin/env python3
"""verify_2d_record71.py — prove the byte[5]=0x71 record layout.

Hypothesis (from byte-identical retail samples across captures):

  off 0    : 0x2d
  off 1    : sub-action
  off 2-3  : category LE16
  off 4    : 0x00
  off 5    : 0x71  record discriminator = "entity world-state"
  off 6    : route/sub-class byte (20/40/80/01/...)
  off 7-10 : entity handle LE32 (position-coded float OR id)
  off 11-14: 0xFFFFFFFF sentinel (no-target) OR a 2nd handle
  off 15-18: float  posX
  off 19-22: float  posY
  off 23-26: float  posZ
  off 27-30: float  heading/anim
  off 31-34: float  == off 19-22 (posY echoed)  <-- structural proof
  off 35   : 0xc5/0x43 ... start of invariant block
  off 35-46: INVARIANT 12B block (event-class constant)
  off 47-50: 0x81 0xca 0x09 0x00  tail marker
  off 51-54: 4B per-record tail

We test the structural invariant off[31:35]==off[19:23] and the
12B invariant block constancy, on every cat=0x0003 sub-action whose
modal records use byte[5]==0x71.
"""
from __future__ import annotations
import struct, sys, glob
from collections import defaultdict, Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")
burst_mod = import_module("parse-burst")
from scapy.all import PcapReader, IP, UDP

STRACE = "/home/javier/Documents/Projects/Neocron/ceres-j/strace"
EXCLUDE = ("CERESJ", "RETAIL_RETAIL_PLAZA_CROSSZONE_20260514")


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


def main():
    caps = sorted(glob.glob(STRACE + "/nc2_strace_RETAIL_*.pcap"))
    caps = [c for c in caps if not any(x in c for x in EXCLUDE)]
    r71 = []  # all 55B bodies with byte5==0x71
    r75 = []
    for c in caps:
        sip = server_ip(Path(c))
        if not sip: continue
        with PcapReader(c) as pr:
            for pkt in pr:
                if IP not in pkt or UDP not in pkt: continue
                if pkt[IP].src != sip: continue
                pl = bytes(pkt[UDP].payload)
                if not pl: continue
                rr = decrypt_mod.decrypt_wire_packet(pl)
                if not rr: continue
                plain, _ = rr
                if not plain or plain[0] != 0x13: continue
                pg = burst_mod.parse_gamedata(plain, len_bytes=2)
                if not pg: continue
                for s in pg["subs"]:
                    if s.get("reliable_type") != 0x2d: continue
                    b = bytes([0x2d]) + bytes(s.get("inner_data", b""))
                    if len(b) != 55: continue
                    if b[5] == 0x71: r71.append(b)
                    elif b[5] == 0x75: r75.append(b)

    print(f"byte5==0x71 records: {len(r71)}")
    print(f"byte5==0x75 records: {len(r75)}")

    # Structural invariant: off[31:35] == off[19:23] (posY echo)
    echo_hits = sum(1 for b in r71 if b[31:35] == b[19:23])
    print(f"\n[71] off[31:35]==off[19:23] (posY echo): "
          f"{echo_hits}/{len(r71)} "
          f"({100*echo_hits/max(1,len(r71)):.1f}%)")

    # off[11:15] == ffffffff fraction
    sent = sum(1 for b in r71 if b[11:15] == b"\xff\xff\xff\xff")
    print(f"[71] off[11:15]==ffffffff: {sent}/{len(r71)} "
          f"({100*sent/max(1,len(r71)):.1f}%)")

    # 12B invariant block: which offset window is most constant?
    # scan windows of length 12, pick the one with fewest distinct
    best = None
    for w in range(30, 44):
        vals = Counter(bytes(b[w:w+12]) for b in r71)
        top, tc = vals.most_common(1)[0]
        if best is None or tc > best[2]:
            best = (w, top, tc)
    w, blk, tc = best
    print(f"\n[71] most-constant 12B window @off{w}: "
          f"{blk.hex()} {tc}/{len(r71)} "
          f"({100*tc/max(1,len(r71)):.1f}%)")

    # tail marker 81 ca 09 00 location
    tm = Counter()
    for b in r71:
        idx = b.find(b"\x81\xca\x09\x00")
        tm[idx] += 1
    print(f"[71] '81 ca 09 00' tail-marker offset histogram: "
          f"{dict(tm.most_common(5))}")

    # restrict to records where off[11:15]==ffffffff AND echo holds:
    clean = [b for b in r71 if b[11:15] == b"\xff\xff\xff\xff"
             and b[31:35] == b[19:23]]
    print(f"\n[71] CLEAN subset (sentinel+echo): {len(clean)}")
    if clean:
        # per-offset constancy on the clean subset
        line = []
        for off in range(55):
            vals = Counter(b[off] for b in clean)
            if len(vals) == 1:
                line.append("%02x" % next(iter(vals)))
            else:
                top, c = vals.most_common(1)[0]
                line.append("%02x*" % top if c/len(clean) >= .97
                            else "..")
        for r in range(0, 55, 11):
            print("  [%2d] %s" % (r, " ".join(
                f"{x:>4}" for x in line[r:r+11])))
        # float plausibility 15..34
        for off in (15, 19, 23, 27, 31):
            vs = [struct.unpack("<f", b[off:off+4])[0] for b in clean]
            ok = sum(1 for v in vs if v == v and (v == 0.0
                     or 1e-2 < abs(v) < 5e6))
            print(f"  float@{off}: {ok}/{len(clean)} plausible "
                  f"ex={['%.2f'%v for v in vs[:3]]}")
        print(f"  ex: {clean[0].hex()}")
        print(f"  ex: {clean[len(clean)//2].hex()}")


if __name__ == "__main__":
    main()
