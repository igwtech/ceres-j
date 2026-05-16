#!/usr/bin/env python3
"""gbsp_extract.py — decode NC2 GBSP (.bsp) geometry files.

NC2 .bsp = Genesis3D BSP ("GBSP") wrapped in the Neocron PAK
container. Layout (RE'd 2026-05-16, task #168):

  Outer (16 bytes):
    [12B NC magic 0a f7 3d 88 84 75 84 93 bd ef fd ab]
    [LE32 uncompressed size]
    [zlib stream  (78 9c …)]

  Inner (post-zlib) = a flat sequence of GBSP chunks, each:
    [int32 Type][int32 Size][int32 Elements][ Size*Elements bytes ]
  terminated by Type == 0xffff (END). The very first chunk is
  Type=0 (HEADER), Size=28, Elements=1, whose data starts with
  the ASCII tag "GBSP" + int32 version (0 in NC2).

Chunk-type map is stock Genesis3D; NC2 also emits MOTIONS
(type 24) carrying keyframed model animations (doors, lifts,
movers) as a structured text-ish stream.

NOTE ON COORDINATES: model/geometry coordinates are in the
*.dat design frame* (≈ ±3000), NOT the runtime wire frame
(≈ ±30000). The design→runtime scale is a separate open RE
item (task #172); this tool extracts the raw geometry as-is.

Usage:
  gbsp_extract.py <file.bsp> chunks
  gbsp_extract.py <file.bsp> models
  gbsp_extract.py <file.bsp> motions
  gbsp_extract.py <file.bsp> json [--out FILE]
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
import zlib
from dataclasses import dataclass, field, asdict
from pathlib import Path

NC_MAGIC = bytes([0x0a, 0xf7, 0x3d, 0x88, 0x84, 0x75,
                  0x84, 0x93, 0xbd, 0xef, 0xfd, 0xab])

CHUNK_NAMES = {
    0: "HEADER", 1: "MODELS", 2: "NODES", 3: "PORTALS", 4: "LEAFS",
    5: "CLUSTERS", 6: "AREAS", 7: "AREA_PORTALS", 8: "LEAF_SIDES",
    9: "FACES", 10: "LEAF_FACES", 11: "PLANES", 12: "VERTS",
    13: "VERT_INDEX", 14: "RGB_VERTS", 15: "ENTDATA", 16: "TEXINFO",
    17: "TEXTURES", 18: "TEXDATA", 19: "LIGHTDATA", 20: "VISDATA",
    21: "SKYDATA", 22: "PALETTES", 23: "MOTIONS", 24: "MOTIONS",
    0xffff: "END",
}

MODEL_STRUCT_SIZE = 80


@dataclass
class Chunk:
    type: int
    name: str
    offset: int          # offset of data (post 12B chunk header)
    size: int
    elements: int

    @property
    def byte_len(self) -> int:
        return self.size * self.elements


@dataclass
class Model:
    index: int
    mins: tuple
    maxs: tuple
    origin: tuple
    # trailing int/float fields kept raw — exact Genesis3D field
    # split for the NC2 80B variant is not fully pinned; expose
    # the words so downstream RE / the zone-portal bridge can
    # use them without this tool guessing wrong.
    raw_words: list = field(default_factory=list)


@dataclass
class MotionPath:
    model_num: int | None
    name: str
    rotation_keys: list = field(default_factory=list)
    translation_keys: list = field(default_factory=list)


def load_inner(path: str | Path) -> bytes:
    """Strip the 16B NC wrapper and inflate the zlib payload."""
    raw = Path(path).read_bytes()
    if raw[:12] != NC_MAGIC:
        raise ValueError(f"{path}: not an NC PAK file "
                         f"(magic {raw[:12].hex()})")
    usize = struct.unpack_from("<I", raw, 12)[0]
    inner = zlib.decompress(raw[16:])
    if len(inner) != usize:
        print(f"warn: inflated {len(inner)} != header {usize}",
              file=sys.stderr)
    return inner


def parse_chunks(inner: bytes) -> list[Chunk]:
    """Walk the flat [Type][Size][Elements][data] chunk list."""
    out: list[Chunk] = []
    off = 0
    while off + 12 <= len(inner):
        typ, size, elems = struct.unpack_from("<iii", inner, off)
        off += 12
        if typ == 0xffff:
            out.append(Chunk(typ, "END", off, 0, 0))
            break
        name = CHUNK_NAMES.get(typ, f"UNKNOWN_{typ}")
        blob = size * elems
        if blob < 0 or off + blob > len(inner):
            raise ValueError(
                f"chunk {name} size overflow at {off} "
                f"(blob={blob}, file={len(inner)})")
        out.append(Chunk(typ, name, off, size, elems))
        off += blob
    return out


def _chunk(chunks: list[Chunk], name: str) -> Chunk | None:
    for c in chunks:
        if c.name == name:
            return c
    return None


def extract_models(inner: bytes, chunks: list[Chunk]) -> list[Model]:
    c = _chunk(chunks, "MODELS")
    if c is None or c.size != MODEL_STRUCT_SIZE:
        return []
    models: list[Model] = []
    for i in range(c.elements):
        b = inner[c.offset + i * c.size: c.offset + (i + 1) * c.size]
        mins = struct.unpack_from("<3f", b, 0)
        maxs = struct.unpack_from("<3f", b, 12)
        org = struct.unpack_from("<3f", b, 24)
        words = list(struct.unpack_from("<11i", b, 36))
        models.append(Model(
            index=i,
            mins=tuple(round(v, 3) for v in mins),
            maxs=tuple(round(v, 3) for v in maxs),
            origin=tuple(round(v, 3) for v in org),
            raw_words=words,
        ))
    return models


def extract_motions(inner: bytes, chunks: list[Chunk]) -> list[MotionPath]:
    """Parse the MOTIONS stream. It is a structured text-ish blob:
    blocks of `FixedPath / PathArray / Rotation / Translation /
    Keys <n> .. / ModelNum <n> / MOTN / NameID …`. We extract the
    per-path ModelNum binding plus the rotation/translation
    keyframe tables (what binds an animation to a brush model)."""
    c = _chunk(chunks, "MOTIONS")
    if c is None:
        return []
    txt = inner[c.offset:c.offset + c.byte_len] \
        .decode("latin-1", "replace")
    lines = [ln.strip() for ln in txt.splitlines()]
    paths: list[MotionPath] = []
    cur: MotionPath | None = None
    mode = None          # 'rot' | 'trans' | None
    for ln in lines:
        if ln.startswith("FixedPath") or ln.startswith("PathArray"):
            if cur is not None:
                paths.append(cur)
            cur = MotionPath(model_num=None, name="")
            mode = None
        elif cur is None:
            continue
        elif ln.startswith("Rotation"):
            mode = "rot"
        elif ln.startswith("Translation"):
            mode = "trans"
        elif ln.startswith("ModelNum"):
            try:
                cur.model_num = int(ln.split()[1])
            except (IndexError, ValueError):
                pass
            mode = None
        elif ln.startswith("NameID"):
            cur.name = ln[6:].strip()
            mode = None
        elif ln.startswith("Keys"):
            pass  # header line for the following key rows
        else:
            parts = ln.split()
            if parts and _is_float(parts[0]) and len(parts) >= 4:
                row = [float(x) for x in parts if _is_float(x)]
                if mode == "rot":
                    cur.rotation_keys.append(row)
                elif mode == "trans":
                    cur.translation_keys.append(row)
    if cur is not None:
        paths.append(cur)
    return paths


def _is_float(s: str) -> bool:
    try:
        float(s)
        return True
    except ValueError:
        return False


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="NC2 GBSP extractor")
    ap.add_argument("file")
    ap.add_argument("cmd",
                    choices=["chunks", "models", "motions", "json"])
    ap.add_argument("--out")
    a = ap.parse_args(argv)

    inner = load_inner(a.file)
    chunks = parse_chunks(inner)

    if a.cmd == "chunks":
        print(f"{a.file}  inflated={len(inner)}  chunks={len(chunks)}")
        for c in chunks:
            print(f"  @{c.offset:<9} {c.name:<14} "
                  f"size={c.size:<8} elems={c.elements:<8} "
                  f"bytes={c.byte_len}")
    elif a.cmd == "models":
        ms = extract_models(inner, chunks)
        print(f"{len(ms)} models")
        for m in ms:
            print(f" M{m.index:<2} mins={m.mins} maxs={m.maxs} "
                  f"origin={m.origin}")
    elif a.cmd == "motions":
        ps = extract_motions(inner, chunks)
        print(f"{len(ps)} motion paths")
        for p in ps:
            print(f"  model={p.model_num} name={p.name!r} "
                  f"rotKeys={len(p.rotation_keys)} "
                  f"transKeys={len(p.translation_keys)}")
    elif a.cmd == "json":
        doc = {
            "file": str(a.file),
            "inflated": len(inner),
            "chunks": [asdict(c) for c in chunks],
            "models": [asdict(m)
                       for m in extract_models(inner, chunks)],
            "motions": [asdict(p)
                        for p in extract_motions(inner, chunks)],
        }
        js = json.dumps(doc, indent=2)
        if a.out:
            Path(a.out).write_text(js)
            print(f"wrote {a.out}")
        else:
            print(js)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
