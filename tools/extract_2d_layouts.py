#!/usr/bin/env python3
"""extract_2d_layouts.py — task #167.

Sweep every retail pcap, decrypt UDP S->C, parse 0x13 gamedata into
reliable sub-packets, isolate every 0x03/0x2d body, group by
(category LE16 @ body[2..3], sub-action @ body[1]). For each group:
  - sample count + which captures
  - exact-length histogram
  - per-offset byte-constancy map (which offsets are invariant vs vary)
  - float plausibility scan (offsets where the LE32 looks like a sane
    world coordinate / orientation float)

Pure observation — no semantic guessing baked into output.
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


def server_ip(pcap: Path):
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


def iter_2d_bodies(pcap: Path):
    """Yield 0x2d bodies (inner_data starting at the 0x2d byte) S->C."""
    sip = server_ip(pcap)
    if not sip:
        return
    with PcapReader(str(pcap)) as pr:
        for pkt in pr:
            if IP not in pkt or UDP not in pkt:
                continue
            if pkt[IP].src != sip:        # S->C only
                continue
            payload = bytes(pkt[UDP].payload)
            if not payload:
                continue
            res = decrypt_mod.decrypt_wire_packet(payload)
            if not res:
                continue
            plain, _ = res
            if not plain or plain[0] != 0x13:
                continue
            parsed = burst_mod.parse_gamedata(plain, len_bytes=2)
            if not parsed:
                continue
            for s in parsed["subs"]:
                if s.get("reliable_type") != 0x2d:
                    continue
                inner = s.get("inner_data", b"")
                # inner_data is everything after [03][seq2][2d]; the
                # documented body in the .md starts AT the 0x2d byte,
                # so re-prepend it for offset parity with the doc.
                body = bytes([0x2d]) + bytes(inner)
                yield body


def main():
    strace_dir = "/home/javier/Documents/Projects/Neocron/ceres-j/strace"
    caps = sorted(glob.glob(strace_dir + "/nc2_strace_RETAIL_*.pcap"))
    # Match the doc's exact 17-capture evidence base: exclude Ceres-J
    # test captures and the retail cross-zone test not in the catalog.
    EXCLUDE = ("CERESJ", "RETAIL_RETAIL_PLAZA_CROSSZONE_20260514")
    caps = [c for c in caps if not any(x in c for x in EXCLUDE)]
    print(f"# {len(caps)} retail captures\n")

    # group key = (category, sub_action) -> list of (body, capname)
    groups = defaultdict(list)
    cap_short = {}
    for c in caps:
        name = Path(c).stem.replace("nc2_strace_", "")
        cap_short[c] = name
        n = 0
        for body in iter_2d_bodies(Path(c)):
            n += 1
            if len(body) < 4:
                key = (None, body[1] if len(body) > 1 else None)
            else:
                cat = body[2] | (body[3] << 8)
                sub = body[1]
                key = (cat, sub)
            groups[key].append((body, name))
        print(f"  {name}: {n} 0x2d bodies", file=sys.stderr)

    # Report
    def is_float(b4):
        if len(b4) != 4:
            return False
        v = struct.unpack("<f", b4)[0]
        if v != v:  # nan
            return False
        a = abs(v)
        return v == 0.0 or (1e-3 < a < 1e7)

    order = sorted(groups.items(), key=lambda kv: -len(kv[1]))
    print(f"\n## (category, sub-action) groups — {len(order)} distinct\n")
    print(f"{'cat':>6} {'sub':>5} {'count':>7}  lens  caps")
    for (cat, sub), items in order:
        lens = Counter(len(b) for b, _ in items)
        capset = sorted(set(n for _, n in items))
        cs = ("0x%04x" % cat) if cat is not None else "  ?  "
        ss = ("0x%02x" % sub) if sub is not None else " ? "
        lensum = ",".join(f"{L}:{c}" for L, c in sorted(lens.items()))
        print(f"{cs:>6} {ss:>5} {len(items):>7}  [{lensum}]  {len(capset)}cap")

    # Per-offset constancy for the well-populated groups
    print("\n\n## Per-offset analysis (groups with >=20 samples)\n")
    for (cat, sub), items in order:
        if len(items) < 20:
            continue
        lens = Counter(len(b) for b, _ in items)
        modal_len, modal_n = lens.most_common(1)[0]
        sub_items = [b for b, _ in items if len(b) == modal_len]
        capset = sorted(set(n for _, n in items))
        print(f"### cat=0x{cat:04x} sub=0x{sub:02x} "
              f"n={len(items)} modal_len={modal_len}"
              f"({modal_n}/{len(items)})  {len(capset)} caps")
        # per-offset: constant value or 'varies'; flag float-ish
        col_const = []
        for off in range(modal_len):
            vals = set(b[off] for b in sub_items)
            if len(vals) == 1:
                col_const.append("%02x" % next(iter(vals)))
            else:
                col_const.append("..")
        # print as rows of 16
        for r in range(0, modal_len, 16):
            chunk = col_const[r:r+16]
            print(f"  [{r:3d}] " + " ".join(chunk))
        # float scan on 4-byte windows that VARY
        floaty = []
        for off in range(0, modal_len - 3):
            if all(col_const[off+k] != ".." for k in range(4)):
                continue  # constant -> not a live float field
            sample_vals = [struct.unpack("<f", b[off:off+4])[0]
                           for b in sub_items[:200]]
            ok = sum(1 for v in sample_vals
                     if v == v and (v == 0.0 or 1e-3 < abs(v) < 1e7))
            if ok >= 0.8 * len(sample_vals) and len(sample_vals) > 0:
                ex = sample_vals[:4]
                floaty.append((off, ["%.3f" % x for x in ex]))
        if floaty:
            print("  float-plausible LE32 windows (off: examples):")
            for off, ex in floaty:
                print(f"    @{off}: {ex}")
        # show 3 raw samples
        for b, nm in [(it[0], it[1]) for it in items
                      if len(it[0]) == modal_len][:3]:
            print(f"  ex: {b.hex()}")
        print()


if __name__ == "__main__":
    main()
