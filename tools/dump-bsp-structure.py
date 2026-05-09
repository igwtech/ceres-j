#!/usr/bin/env python3
"""dump-bsp-structure.py — Walk an NC2 .bsp file and identify
the GBSP geometry section.

Usage:
  ./tools/dump-bsp-structure.py /path/to/world.bsp [--show-bytes 256]

KEY FINDING (2026-05-09 RE):

NC2 .bsp files use a 12-byte NC-specific header followed by raw
GBSP (Genesis3D BSP) geometry data. They do NOT contain the
PWorldFileHeader / Section / Element streams that .dat files have.

  Outer wrapping (16 bytes):
    [12B magic 0a f7 3d 88 ...][LE32 uncompressed size][zlib stream]

  Inner stream (post-zlib):
    [12B NC header: 00 00 00 00 1c 00 00 00 01 00 00 00]
    [GBSP magic "GBSP" (4B)]
    [LE32 version (= 0 in NC2 dumps)]
    [LE32 chunk_count (= 17 across all dumped BSPs)]
    [chunks data — format TBD]

So the existing WorldDatParser correctly bails on .bsp files (no
elements found) but should be re-documented to clarify .bsp =
geometry-only, .dat = element-only.

Verified against 16 .bsp files in /home/javier/Neocron2/worlds/:
  - All start with GBSP magic at inner offset 12
  - All have version 0, chunk_count 17
  - pak_matrix_*.bsp are all 909,146 bytes inner (identical
    procedural geometry across matrix levels)
  - pak_subway.bsp is largest at 13,790,464 bytes inner

CHUNK FORMAT (TBD):
The 32 bytes after the GBSP+version+chunk_count header don't
parse as straightforward (type, count, size) triplets. They may
be a Genesis3D GE_Date (16B timestamp) followed by the actual
chunks, OR a fixed array of LE32 chunk sizes (17 × 4 = 68B), OR
a different layout entirely. Needs deeper RE — file as TODO.

USEFUL CHUNKS (for server purposes, when decoded):
  - GBSP_PLANES + GBSP_BRUSHES — collision detection, anti-cheat
  - GBSP_NODES + GBSP_LEAFS    — BSP tree for spatial queries
  - GBSP_PORTALS               — visibility / line-of-sight
  - GBSP_ENTDATA               — text entity definitions
                                  (may overlap with .dat)
  - GBSP_VISDATA               — PVS for AI culling
"""
from __future__ import annotations
import argparse, struct, sys, zlib
from pathlib import Path

MAGIC = bytes([0x0a, 0xf7, 0x3d, 0x88, 0x84, 0x75, 0x84, 0x93,
               0xbd, 0xef, 0xfd, 0xab])
FILE_HEADER_SIG = 0x000fcfcf
SECTION_HEADER_SIG = 0x0000ffcf
ELEMENT_HEADER_SIG = 0x0ffefef1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("bsp", type=Path)
    ap.add_argument("--show-bytes", type=int, default=128,
                    help="Bytes of post-terminator hex to show")
    ap.add_argument("--max-elements", type=int, default=5,
                    help="Max elements per section to print")
    args = ap.parse_args()

    raw = args.bsp.read_bytes()
    if raw[:12] != MAGIC:
        sys.exit(f"NOT a NC2 wrapped file: bad magic at start")
    uncompressed_size = struct.unpack_from("<I", raw, 12)[0]
    print(f"Outer wrapper: magic OK, "
          f"uncompressed size = {uncompressed_size} bytes")

    inner = zlib.decompress(raw[16:])
    print(f"Decompressed inner stream: {len(inner)} bytes "
          f"(expected {uncompressed_size})")

    pos = 0
    # PWorldFileHeader: size=0x08 sig section
    if len(inner) < 12:
        sys.exit("Inner stream too short for PWorldFileHeader")
    sz = struct.unpack_from("<I", inner, pos)[0]
    sig = struct.unpack_from("<I", inner, pos + 4)[0]
    section = struct.unpack_from("<I", inner, pos + 8)[0]
    print(f"\nPWorldFileHeader @0: size={sz:#x} sig={sig:#x} "
          f"section={section}")
    if sig != FILE_HEADER_SIG:
        print(f"  WARNING: expected sig={FILE_HEADER_SIG:#x}")
    pos += 12

    # Walk sections
    section_count = 0
    while pos + 16 <= len(inner):
        sec_sz = struct.unpack_from("<I", inner, pos)[0]
        sec_sig = struct.unpack_from("<I", inner, pos + 4)[0]
        sec_section = struct.unpack_from("<I", inner, pos + 8)[0]
        sec_data_size = struct.unpack_from("<I", inner, pos + 12)[0]
        if sec_sig != SECTION_HEADER_SIG:
            print(f"\n@{pos}: NOT a section header "
                  f"(sig={sec_sig:#x}, expected {SECTION_HEADER_SIG:#x})")
            print(f"  This is likely the start of GBSP geometry data.")
            break

        if sec_section == 0:
            # Terminator
            print(f"\n@{pos}: SECTION TERMINATOR "
                  f"(section=0, sig={sec_sig:#x})")
            pos += 16
            break

        section_count += 1
        print(f"\nSection #{sec_section} @{pos}: "
              f"size={sec_sz} dataSize={sec_data_size}")
        pos += 16
        end = pos + sec_data_size

        elem_count = 0
        while pos + 12 <= end and pos + 12 <= len(inner):
            el_sz = struct.unpack_from("<I", inner, pos)[0]
            el_sig = struct.unpack_from("<I", inner, pos + 4)[0]
            el_type = struct.unpack_from("<I", inner, pos + 8)[0]
            if el_sig != ELEMENT_HEADER_SIG:
                print(f"  @{pos}: not an element "
                      f"(sig={el_sig:#x}); stopping section walk")
                break
            el_data_size = struct.unpack_from("<I", inner,
                                              pos + el_sz)[0] \
                if el_sz >= 16 else 0
            # Element format: header (sz) then payload (data_size)
            # Actually based on TinNS format: element header is
            # [size LE4][sig LE4][type LE4][dataSize LE4]
            # let me re-read the parser comment
            el_data_size = struct.unpack_from("<I", inner,
                                              pos + 12)[0]
            elem_count += 1
            if elem_count <= args.max_elements:
                print(f"  Element #{elem_count} @{pos}: "
                      f"type={el_type} (size={el_sz}, "
                      f"data={el_data_size}B)")
            pos += 16 + el_data_size
        if elem_count > args.max_elements:
            print(f"  ... (+{elem_count - args.max_elements} more "
                  f"elements)")

    # Post-terminator: GBSP geometry
    print(f"\n=== POST-TERMINATOR (geometry section) ===")
    print(f"Bytes consumed by element streams: {pos}")
    print(f"Bytes remaining: {len(inner) - pos}")
    if pos < len(inner):
        print(f"\nFirst {args.show_bytes} bytes after terminator "
              f"(hex):")
        chunk = inner[pos:pos + args.show_bytes]
        for i in range(0, len(chunk), 32):
            row = chunk[i:i + 32]
            ascii_repr = "".join(
                chr(b) if 32 <= b < 127 else "." for b in row)
            print(f"  +{i:04d}  {row.hex()}  {ascii_repr}")
        # Look for known GBSP magic / version
        # GBSP files typically start with "GBSP" (0x47425350) + version
        if len(chunk) >= 4:
            first_u32 = struct.unpack_from("<I", chunk, 0)[0]
            print(f"\nFirst u32 LE: {first_u32:#x} "
                  f"({first_u32})")
            ascii_4 = chunk[:4].decode("ascii", errors="replace")
            print(f"First 4 bytes as ASCII: {ascii_4!r}")


if __name__ == "__main__":
    main()
