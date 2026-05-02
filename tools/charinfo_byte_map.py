#!/usr/bin/env python3
"""charinfo_byte_map.py — exhaustive byte-by-byte identification across
multiple CharInfo captures.

For each section, dumps a side-by-side hex matrix of all chars'
section bodies. Annotates each byte position as:
  - CONSTANT  : same byte across all chars (structural / fixed tag)
  - VARIES    : different bytes -> candidate field data
  - KNOWN     : matches a known field value at expected encoding

Output is a single text report suitable for inclusion in PROTOCOL.md.
"""
from __future__ import annotations
import sys, json, struct, argparse
from pathlib import Path
from collections import defaultdict

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
correlate = import_module("correlate_charinfo")

# Known field positions (verified by 5/5 differential)
KNOWN = {
    (2, 4, "u16le"): "HP_max",
    (2, 6, "u16le"): "PSI_cur",
    (2, 8, "u16le"): "PSI_max",
    (2, 10, "u16le"): "STA_cur",
    (2, 12, "u16le"): "STA_max",
    (8, 1, "u32le"): "Cash",
}


def extract_field_at(body: bytes, off: int, enc: str):
    if enc == "u8" and off < len(body):
        return body[off]
    if enc == "u16le" and off + 2 <= len(body):
        return body[off] | (body[off+1] << 8)
    if enc == "u32le" and off + 4 <= len(body):
        return struct.unpack_from("<I", body, off)[0]
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", "-j", type=Path, required=True)
    ap.add_argument("--strace-dir", type=Path,
                    default=Path("/home/javier/Documents/Projects/Neocron/ceres-j/strace"))
    ap.add_argument("--out", type=Path, default=Path("ceres-j/docs/charinfo_byte_map.md"))
    args = ap.parse_args()

    spec = json.loads(args.json.read_text())
    chars = spec["characters"]
    parsed = []
    for ch in chars:
        pcap = args.strace_dir / ch["capture_pcap"]
        if not pcap.exists(): continue
        body = correlate.reassemble_charinfo(pcap)
        if not body: continue
        sections = correlate.parse_charinfo_sections(body)
        parsed.append({
            "name": ch["name"], "spec": ch,
            "body": body, "sections": sections,
        })

    if not parsed:
        sys.exit("no parseable captures")

    names = [pc["name"] for pc in parsed]
    short = {n: n.split()[0][:5] for n in names}

    out = []
    out.append("# CharInfo byte-by-byte map\n")
    out.append(f"Differential analysis of {len(parsed)} retail CharInfo captures.\n")
    out.append(f"Chars: {', '.join(names)}\n\n")
    out.append("Each section is shown as a side-by-side hex matrix per byte position. ")
    out.append("Bytes that are identical across all chars are CONSTANT (structural). ")
    out.append("Bytes that differ are candidate field data.\n\n")

    out.append("## Section presence and sizes\n\n")
    out.append("| Section | " + " | ".join(short[n] for n in names) + " |\n")
    out.append("|---" * (len(names) + 1) + "|\n")
    all_sids = set()
    for pc in parsed:
        all_sids |= set(pc["sections"].keys())
    for sid in sorted(all_sids):
        row = [f"S{sid}"]
        for pc in parsed:
            if sid in pc["sections"]:
                _, body = pc["sections"][sid]
                row.append(str(len(body)))
            else:
                row.append("—")
        out.append("| " + " | ".join(row) + " |\n")
    out.append("\n")

    out.append("## Per-section byte matrix\n\n")
    out.append("Format: `+OFF  K=XX N=XX H=XX A=XX O=XX  [annotation]`. ")
    out.append("Annotations: `CONST` = same byte all chars; "
               "`KNOWN field` = position matches a verified known field; "
               "blank = varies, candidate field.\n\n")

    for sid in sorted(all_sids):
        out.append(f"### Section {sid}\n\n")
        # Find max common length
        bodies = []
        for pc in parsed:
            if sid in pc["sections"]:
                _, b = pc["sections"][sid]
                bodies.append((pc["name"], b, pc["spec"]))
        if not bodies: continue

        # Total byte counts
        sizes = [len(b) for _, b, _ in bodies]
        out.append(f"Sizes: {dict(zip([short[n] for n,_,_ in bodies], sizes))}\n\n")

        max_len = max(sizes)
        common_len = min(sizes)

        out.append("```\n")
        # Show all bytes up to max_len (chars without that byte get '..')
        for off in range(max_len):
            cells = []
            seen_vals = set()
            for n, b, _ in bodies:
                if off < len(b):
                    v = b[off]
                    cells.append(f"{short[n][0]}={v:02x}")
                    seen_vals.add(v)
                else:
                    cells.append(f"{short[n][0]}=..")
            anno = ""
            if len(seen_vals) == 1 and not any(c.endswith("..") for c in cells):
                anno = "CONST"
            # Check known field positions at this offset under multiple encodings
            for enc in ("u8", "u16le", "u32le"):
                key = (sid, off, enc)
                if key in KNOWN:
                    anno = f"KNOWN {KNOWN[key]} ({enc})"
                    break

            out.append(f"+{off:04x}  {' '.join(cells)}  {anno}\n")
        out.append("```\n\n")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("".join(out))
    print(f"Wrote {args.out} ({sum(len(s) for s in out):,} bytes)")
    # Quick stats
    total_const = 0
    total_var = 0
    for sid in sorted(all_sids):
        bodies = [pc["sections"][sid][1] for pc in parsed if sid in pc["sections"]]
        if not bodies: continue
        cl = min(len(b) for b in bodies)
        for off in range(cl):
            seen = {b[off] for b in bodies}
            if len(seen) == 1:
                total_const += 1
            else:
                total_var += 1
    print(f"Stats over common-length section bodies: {total_const} CONST, {total_var} VARYING")


if __name__ == "__main__":
    main()
