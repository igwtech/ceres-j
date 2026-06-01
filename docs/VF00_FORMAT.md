# VF00 `.act` Model Format

Reverse-engineered binary layout of Neocron 2's proprietary `.act` 3D model
container (the inner **VF00** payload). Authorized RE of the user's own game
client. Engine lineage: **Genesis3D**-derived (`nclib\genesis\...` source paths
in the binary).

Two evidence sources back every claim below:
1. **Ghidra static analysis** of the loader functions in `neocronclient.exe`.
2. **Byte-level cross-check** against three real decompressed models
   (`pak_longlegblade`, `pak_g71_pistol_3rd`, `pak_stiletto_lp`). Every inferred
   array length lands *exactly* on the next ASCII chunk tag — strong validation.

All integers little-endian. Floats are IEEE-754 32-bit.

---

## 0. PAK envelope (outer `.act` file on disk)

```
0x00  u32   sig = 0x883df70a       (PAK single-file magic, LE)
0x04  8B    ? (per-file bytes; byte[8..11] = decompressed size LE32 in samples)
0x10  ...   zlib stream (0x78 0x9c ...) -> inflate -> VF00 payload
```

Decompress: `zlib.decompress(data[0x10:])`. The result begins with `"VF00"`.
(`tools/PAK.class.php` / `launcher/pkg/pak/pak.go` do the same; for VF00 work a
one-liner `zlib.decompress(open(f,'rb').read()[0x10:])` is sufficient.)

---

## 1. Loader functions (in `neocronclient.exe`)

Chunk tags are compared/written as **little-endian u32 immediates**, not as
defined strings — that is why a plain string-xref scan misses them. Found via
scalar-immediate search (`ceres-j/tools/ghidra/FindVF00Scalars.java`):

| Function      | Role                                            | Evidence (tag immediate)                |
|---------------|-------------------------------------------------|-----------------------------------------|
| `FUN_0044e8d0`| VF00 container magic check                      | `CMP [EBP-0x40], 0x30304656` ("VF00")   |
| `FUN_0044f890`| VF00 container writer                           | `MOV [EBP-0x38], 0x30304656`            |
| `FUN_0047d220`| ACTR reader (actor wrapper)                     | `CMP [EBP-0x14], 0x52544341` ("ACTR")   |
| `FUN_0047d6d0`| ACTR writer                                     | `MOV [EBP-0x4],  0x52544341`            |
| **`FUN_00520080`** | **BOD^ geometry reader (vertices/normals/faces)** | `CMP [EBP-0x4], 0x5e444f42` ("BOD^") |
| `FUN_0051fdc0`| BOD^ sub-block reader (16-byte record arrays)   | `CMP [EBP-0x4], 0x5e444f42`              |
| `FUN_00521a10`/`FUN_00521c10` | BOD^ writers                    | `MOV [EBP-0xc], 0x5e444f42`              |
| `FUN_005ac270`| SBKB skin/bone block reader                     | `CMP EAX, 0x424b4253` ("SBKB")          |

`FUN_00520080` is the primary geometry parser. Key decompiled evidence:

* Reads the `"BOD^"` tag then a version u32 and asserts `== 0xf1` (241):
  `if (local_8 != 0x5e444f42) ...; if (local_8 != 0xf1) ...` (offsets 0x6fc/0x70f).
* Allocates per-element arrays with these strides, each preceded by a u16 count:
  * `local_8 = sVar1 * 0x18;` → **24-byte vertex records**, bulk-read via
    `istream::read(buf, count*0x18)` (≈ offset 0x7f9). *Not* parsed field-by-field
    on disk — read as a raw struct array.
  * `local_8 = (short)*p << 4;` → **16-byte records** (`<<4` = ×16) for the
    normal/aux arrays (offsets 0x753, 0x818, 0x83b).
  * `local_8 = sVar1 * 0x4c;` → **76-byte mesh-header records** (offset 0x76c),
    `memset` then read.
* A special case `((short)p[-3]==4) && ((short)p[3]==2)` does a 4-corner bounding
  box reduction — LOD/AABB bookkeeping, not part of the on-disk vertex stream.

The field *meaning* inside the 24-byte vertex and the face records (below) comes
from the byte cross-check, since the loader reads them as opaque blobs.

---

## 2. VF00 / ACTR container header (first 0x60 bytes)

```
0x00  char[4]  "VF00"            container magic
0x04  u32      per-file value    (varies; checksum/flags — NOT a size)
0x08  u32      0                 reserved
0x0c  u32      total - 0xB1      section size (== 0x10 field; payload size)
0x10  u32      total - 0xB1      duplicate of 0x0c
0x14  u32      TOTAL FILE SIZE   == len(decompressed VF00)   [CONFIRMED]
0x18  char[4]  "ACTR"            actor chunk tag
0x1c  u32      0xf1 (241)        version  (was mis-scanned earlier as "241B size")
0x20  u32      1                 ? (actor flag / sub-object count)
0x24  u32      0                 reserved
0x28  char[4]  "VF00"            INNER container (geometry sub-file)
0x2c  u32      per-file value
0x30  u32      0
0x34  u32      inner section size
0x38  u32      inner section size (dup)
0x3c  u32      inner total
0x40  char[4]  "BOD^"            geometry/body chunk tag
0x44  u32      0xf1 (241)        BOD^ version  [CONFIRMED == loader assert]
0x48  24B      zero padding
0x60  ...      geometry begins (vertex count u16)
```

Cross-check: `0x14 == decompressed length` and `0x0c == 0x10 == total - 177`
held byte-exact for all 3 samples (6826 / 23404 / 74943 bytes). The earlier
"ACTR is ~241B" note was a misread of the `0xf1` **version** field.

---

## 3. BOD^ geometry body (the priority deliverable — CONFIRMED)

Immediately after the BOD^ header padding (`0x60`), three count-prefixed arrays
appear back-to-back. Each array = `u16 count` then `count * stride` bytes.

### 3a. Vertex array  — stride **24 bytes**

```
@0x60  u16   vertex_count
@0x62  vertex_count × {
          f32  x
          f32  y
          f32  z        # position; sample ranges -40..40, -147..152, -643..656
          f32  u
          f32  v        # texture UV; STRICTLY in [0,1] across all samples
       }                # 24 bytes, no per-vertex normal
```

Confidence: **HIGH.** Positions form a recognizable thin-blade silhouette;
both trailing floats land in [0,1] for every vertex → texture coordinates.
There is **no** packed normal or bone weight inside the vertex; normals are a
separate indexed array (3b) and skinning lives in the SBKB blocks (§5).

### 3b. Normal array — stride **16 bytes**

```
@next  u16   normal_count
       normal_count × {
          f32  nx
          f32  ny
          f32  nz        # UNIT vector: nx²+ny²+nz² == 1.0 for every record
          u8[4] tail      # constant per file (e.g. 01 cd 00 00); in-mem padding
       }                  # 16 bytes
```

Confidence: **HIGH.** Every record is unit length (verified len²≈1.000 across
samples). Faces index into this array (see `w/n` fields in 3d).

### 3c. Mesh-header array — stride **76 bytes** (`0x4c`)

```
@next  u16   mesh_count
       mesh_count × { 76 bytes }   # AABB min/max floats + LOD/material bookkeeping
```

Confidence: MEDIUM. Stride confirmed by the loader (`* 0x4c`) and by the array
ending **exactly** on the next chunk (`SBKB`) in all samples. First 12 bytes are
a float position (bounding-box min); remaining fields contain `0xcd` MSVC
uninit-fill in some slots → partly runtime-only. Internal sub-layout not fully
broken out (low priority).

This array ends exactly at the first `"SBKB"` tag.

### 3d. Face / triangle index array — stride **16 bytes**

Located after the bone-skin (SBKB) blocks, inside each material group. A
material group is: `"SBKB"` block(s) → material name (ASCII, null-terminated,
e.g. `"Material #4\0"` or `"4 - Default [Box...\0"`) → `u32 material_id` →
`u32 face_count` → `face_count × 16-byte` records:

```
       face_count × {
          u16  v0          # vertex index (0 .. vertex_count-1)
          u16  v1
          u16  v2          # triangle (CCW assumed)
          u16  n_face      # index into normal array (per-face / first-corner)
          u16  n1          # index into normal array (per-corner normal)
          u16  n2          # index into normal array (per-corner normal)
          u16  0           # pad
       }                   # 16 bytes
```

Confidence: **HIGH** for `v0/v1/v2`. Every triangle's max vertex index was
`vertex_count-1` exactly (21/22 for longlegblade, 224/225 for pistol). The block
ends exactly on the next `"BOD^"`. The `n_face/n1/n2` fields land in
`0..normal_count-1` → normal indices (per-corner). The trailing u16 is always 0.

> NOTE: A model has **multiple** material groups (one face array each). Sum the
> per-group face counts for the full mesh. The longlegblade has a single group
> (16 tris); the pistol has at least one group of 214 tris. Walking *all* groups
> requires iterating the post-geometry SBKB/material chain (see §5).

---

## 4. Cross-check results (real files)

| Model                  | bytes  | verts | normals | mesh hdrs | faces (1st mat) | max face idx | result |
|------------------------|-------:|------:|--------:|----------:|----------------:|-------------:|--------|
| `pak_longlegblade.act` |  6 826 |    22 |      10 |         2 |              16 | 21 (<22)     | ✅ all array ends hit `SBKB`/`BOD^` exactly |
| `pak_g71_pistol_3rd.act`| 23 404 |   225 |     165 |         4 |             214 | 224 (<225)   | ✅ ends exactly on 2nd `BOD^` |
| `pak_stiletto_lp.act`  | 74 943 |   147 |      92 |         2 |               — | —            | ✅ verts/normals/mesh-hdr all valid, normals unit-length |

For longlegblade the full chain validated to the byte:
`0x62 +22×24 = 0x272` (→ normal count) `+10×16 = 0x314` (→ mesh count)
`+2×76 = 0x3ae` = **`SBKB`**; faces `0x406 +16×16 = 0x506` = **2nd `BOD^`**.

---

## 5. Still OPEN (bones / skinning / animation — lower priority)

* **SBKB blocks** (`FUN_005ac270`): header `SBKB | u32 a | u32 b | u32 c | name…`.
  Carry `BONE01..BONEnn` ASCII names + per-bone floats (rest pose / bind matrices)
  and the skin weights that bind vertices to bones. Layout of the weight records
  not yet fully pinned. `CSBK` is the compressed/secondary skin block.
* **Multi-material walk:** material-group naming differs between models
  (`"Material #4"` vs `"4 - Default [Box..."`); a robust parser must scan for the
  SBKB→name→`u32 matid`→`u32 facecount` pattern rather than a fixed offset.
* **Second BOD^** per file (LOD mesh or animation body) — same vertex/normal/face
  grammar, re-parse recursively.
* **`Geom` / `Moti`(ons) / `Body` / `Head` tail chunks** near EOF: animation
  channels and attachment metadata. Untouched.
* VF00 header `0x04` per-file value: checksum vs flags — undetermined.

---

## 6. Python parser sketch (CONFIRMED geometry only)

```python
import zlib, struct

def load_act(path):
    raw = open(path, 'rb').read()
    assert struct.unpack_from('<I', raw, 0)[0] == 0x883df70a, "not a PAK .act"
    return zlib.decompress(raw[0x10:])            # -> VF00 payload

def parse_vf00_geometry(r):
    assert r[0:4]  == b'VF00'
    assert r[0x14:0x18] == struct.pack('<I', len(r)), "size mismatch"  # 0x14 = total
    assert r[0x18:0x1c] == b'ACTR'
    assert r[0x40:0x44] == b'BOD^'
    o = 0x60

    # --- vertices: u16 count, then count * 24B (pos.xyz + uv) ---
    (vcount,) = struct.unpack_from('<H', r, o); o += 2
    verts = []
    for _ in range(vcount):
        x, y, z, u, v = struct.unpack_from('<3f2f', r, o); o += 24
        verts.append((x, y, z, u, v))

    # --- normals: u16 count, then count * 16B (nx,ny,nz + 4B tail) ---
    (ncount,) = struct.unpack_from('<H', r, o); o += 2
    normals = []
    for _ in range(ncount):
        nx, ny, nz = struct.unpack_from('<3f', r, o); o += 16
        normals.append((nx, ny, nz))

    # --- mesh headers: u16 count, then count * 76B (AABB/LOD; skipped) ---
    (mcount,) = struct.unpack_from('<H', r, o); o += 2
    o += mcount * 76
    assert r[o:o+4] == b'SBKB', "geometry walk lost alignment"

    # --- faces: per material group  (SBKB… -> name\0 -> u32 matid, u32 count -> 16B tris) ---
    faces = []
    pos = o
    while True:
        mi = r.find(b'SBKB', pos)
        if mi < 0:
            break
        # material name follows the SBKB header; find its null terminator,
        # then matid + facecount + face records.
        # (naming offset varies between models — locate the name, then read
        #  u32 matid, u32 face_count immediately after the name's NUL.)
        nul = r.index(b'\x00', mi + 16)            # name is ASCII, NUL-terminated
        matid, fcount = struct.unpack_from('<II', r, nul + 1)
        fo = nul + 1 + 8
        ok = all(struct.unpack_from('<3H', r, fo + i*16)[0] < vcount
                 for i in range(min(fcount, 4)))   # sanity gate
        if 0 < fcount < 100000 and ok:
            for i in range(fcount):
                v0, v1, v2, nf, n1, n2 = struct.unpack_from('<6H', r, fo + i*16)
                faces.append((v0, v1, v2))
            pos = fo + fcount * 16
        else:
            pos = mi + 4
        if r[pos:pos+4] == b'BOD^':                # next LOD body
            break
    return verts, normals, faces
```

This reproduces 22/10/16 (longlegblade) and 225/165/214 (pistol) with every
face index in range. Bones/animation parsing is left as future work (§5).

---

## 7. `GeBm` — embedded texture bitmap (CONFIRMED)

Most models (~74% of the 1458 in `models.pak`; 136/280 in the local `models/`
dir) embed their texture **inside the `.act`** as a `GeBm` ("Genesis Bitmap")
chunk. Decoded + validated against `pak_stiletto_lp` (256×256 pal8),
`pak_longlegblade` (64×64 pal8), `pak_snake green` (256×256 rgb24),
`pak_hacktool_fp` (512×512 rgb24), and multi-chunk `pak_buggy` (3×512² rgb24).

```
@i    char[4]  "GeBm"            tag (LE u32 0x6d426547)
@i+4  u8       0x40              bank/marker (constant across all samples)
@i+5  u8       ?                 per-file (mip/lod hint; ignored)
@i+6  u8       format            0x01 = 8-bit palettized, 0x0a = 24-bit RGB
@i+7  u8       0x00
@i+8  u8       ?                 per-file (ignored)
@i+9  u8       ?                 per-file
@i+10 u8       ?                 (palettized: 0xff marker; rgb: first data byte)
        -- 8-bit palettized (format 0x01) --
@i+11 256 × [B, G, R, 0]         1024-byte palette, Windows BGR0 order
      W*H × u8                   palette indices, row-major, top-left origin
        -- 24-bit RGB (format 0x0a) --
@i+11 W*H × [R, G, B]            raw 24-bit, row-major, top-left origin
```

* **Width/height are NOT stored in an explicit field.** Textures are square
  powers of two; recover the side from the pixel-block length (bounded by the
  next chunk tag — `GeBm`/`Geom`/`Moti`/`Body`/`Head`/`BOD^`/`Bitmaps`) snapped to
  the largest power-of-two square that fits. Residual = the following chunk's
  header, never a partial row (verified exact on all samples).
* **Palette colour order is BGR** (verified: a beard texture renders auburn not
  blue; a stiletto handle renders leather not blue). **Raw 24-bit order is RGB**
  (verified: "snake green" renders green not blue). This BGR-palette / RGB-direct
  split is the classic Windows-DIB palette convention.
* **No-palette detection:** for format 0x01 the palette is located by scanning
  i+4..i+40 for a 256-entry block whose every 4th byte (the pad) is 0x00. A model
  whose `GeBm` has no such block is format 0x0a (RGB).
* A model may carry **several** `GeBm` chunks (e.g. a vehicle with 3 body-panel
  textures); material group *N* binds to `GeBm` *N* in file order.

Decoder: `tools/nc2_model/act_textures.py` (`decode_all_gebm`, `decode_gebm_at`).

### External (non-embedded) textures

The ~26% with no `GeBm` reference textures data-driven:
* **Characters** — the body mesh declares one SBKB block with sub-materials
  `head`/`body`/`leg`; each face's trailing `u16` (the field documented as "pad"
  in §3d) selects the sub-material (0/1/2). The per-character skin is chosen by
  `defs.modeltextures` (`headtextureid` / `torsotextureid` / `legtextureid`,
  30 variant slots each). The texture-id → filename hop is **client-internal**
  (not in shipped `.def` tables) — needs a Ghidra trace of the loader.
* **Generic-material items** (`"Material #N"`, `"4 - Default [Box"`, …) bind via
  `defs.items` `ModelID` → `BitmapNumber`/`BitmapIndex` (schema in
  `defs\rsctables.def`), also resolved at runtime by the client.

The loose `gfx/modeltextures/*.dds|*.bmp` files are themselves PAK single-file
compressed (sig `0x883df70a`); inflate before opening as DDS/BMP.

---

## 8. Skeleton & skin weights — CONFIRMED

The `.act` is a **Genesis3D `geActor`**: a `geVFile` (virtual file system) whose
directory lives at the very tail of the payload. The actor's named sub-files are
`Header` (ACTR), `Body` (`geBody`), and `Motions` (a directory of N `geMotion`
sub-files indexed `"0".."N-1"`). Ghidra evidence:

| Function       | Role (source path baked into binary error strings)              |
|----------------|-----------------------------------------------------------------|
| `FUN_0047ca50` | geActor reader — opens `Header`/`Body`/`Motions` VFS dirs        |
| `FUN_0051ea50` | geBody reader — opens `Geometry`/`Geometry2`/`Bitmaps`          |
| `FUN_00520080` | binary geBody geometry/bone reader (`genesis\Actor\body.cpp`)   |
| `FUN_005ac130` | binary `SBKB` string-block reader                              |
| `FUN_00518290` | binary `MTNB` geMotion reader (`genesis\Actor\motion.cpp`)      |
| `FUN_00515300` | binary `gePath` reader (`genesis\Actor\path.cpp`)               |
| `FUN_005b0960` | binary `QKFrame` rotation-key reader (`genesis\Actor\QKFrame.cpp`)|
| `FUN_005af180` | binary `VKFrame` translation-key reader (`genesis\Actor\vkframe.cpp`)|

### 8a. geBody layout (the `Body/Geometry` sub-file) — CONFIRMED

The primary BOD^ (from `0x60`) is a `geBody`. The "vertex array" of §3a is in
fact the **XSkinVertexArray**; its 24-byte record carries the per-vertex bone
binding in its trailing 4 bytes. After it come Normals, then the **BoneArray**,
then the bone-name **SBKB** string-block. `FUN_00520080` reads these in order
with strides `0x18 / 0x10 / 0x4c`; the walk lands **exactly** on `SBKB`.

```
@0x60   u16 xskin_count   ; XSkinVertexArray, stride 0x18
        u16 normal_count  ; NormalArray,      stride 0x10
        u16 bone_count    ; BoneArray,        stride 0x4c  (geBody_Bone)
        "SBKB" …          ; bone-name geStrBlock (bone_count names)
```

### 8b. XSkinVertex (24 B) — CONFIRMED (rigid, 1 bone/vertex)

```
f32  x, y, z          ; position  (== the §3a positions, byte-identical)
f32  u, v             ; texture UV
u8   influence_count  ; always 1 (rigid skinning)
u8   marker           ; per-file constant (0xBA male, 0x16 doberman); bookkeeping
u8   bone_index       ; index into BoneArray  ← THE skin binding
u8   0
```

Every `bone_index` is in `[0, bone_count)` for both test models; the binding is
spatially sane (lower-body verts → `BIP01 PELVIS`). Critically,
`xskin_count == BOD^ vertex_count == max(face vertex idx)+1` (545/545, 529/529).
So the §3a "vertices" the geometry reader already parses ARE these skin vertices.

### 8c. geBody_Bone (76 B) — CONFIRMED

```
f32[6]  bbox_min(xyz), bbox_max(xyz)  ; ±8.99e9 sentinel = uninitialised box
f32[9]  rot 3x3 row-major             ; bone-to-PARENT rotation
f32[3]  translation                   ; bone-to-PARENT translation
u16     parent_index                  ; 0xFFFF = root
u16     pad (0xCDCD / uninitialised)
```

Bones are stored parent-before-child; exactly one root (`0xFFFF`). Male model →
a textbook 3DS-Max **Biped** (`BIP01`, `PELVIS`, `SPINE`..`SPINE3`, `NECK`,
`HEAD`, L/R `CLAVICLE/UPPERARM/FOREARM/HAND/FINGER`, L/R `THIGH/CALF/FOOT/TOE`,
holsters). Spine translations step sanely (108→165→192); rotations orthonormal.

### 8d. geStrBlock (`SBKB` chunk, binary form — `FUN_005ac130`) — CONFIRMED

```
"SBKB"
u32  count
u32  offset[0]            ; = total blob size, measured from this field's start
u32  offset[1..count]     ; per-string offsets, base = tag_off + 0x0C
char[] NUL-terminated strings
```

(`CSBK` before a material block is a 4-byte wrapper immediately followed by a
material `SBKB`; not a separate format.)

### 8e. Skinning convention — CONFIRMED (the "exploded mesh" fix)

These three conventions are now pinned by reproducing, in pure Python, exactly
what a glTF importer (Blender) composes — and cross-checked against the
anatomically-correct assembled figure. Getting any one wrong "explodes" the mesh
into per-bone spikes. Validated by `tools/nc2_model/validate_skin.py` on both the
38-bone male and the 34-bone doberman.

1. **Rotation 3×3 major order = ROW-major, applied as `M · v` (column vectors).**
   The geBody 3×3 is `R[row][col]`; the bone-to-parent local matrix is
   `[ R | t ; 0 0 0 1 ]` (row-major) and a point is transformed `p' = M · p`. No
   transpose is needed — transposing the rotation is the classic explode, and it
   is **wrong** here.

2. **Translation space = PARENT-RELATIVE (bone-local).** The `translation` field
   is already the bone-to-parent offset; the node's world transform is the running
   product `world(b) = world(parent) · local(b)` down from the root. Bone world
   origins so composed are anatomically sane (male: HEAD ≈ z 2290, feet ≈ z 0,
   hands out to x ≈ ±450). Do **not** treat the translation as model/world space.

3. **XSkinVertex positions are stored in each bone's LOCAL frame — NOT model
   space.** This is the crux of the explosion bug. A bone's verts cluster near
   that bone's local origin; only `model_pos = bind_world(b) · v_local`
   reassembles the standing figure (male bbox grows from a 828-unit bone-local
   pile to a 2615-unit standing body; doberman 2923 → 6108). glTF, however,
   requires **POSITION in the skin's MODEL (bind) space**, with the inverse-bind
   matrix mapping model → bone-local. Therefore the exporter must:
   * emit `POSITION = bind_world(b) · v_local` (pre-transform to model space),
   * emit `IBM(b) = inverse(bind_world(b))`,
   * where `bind_world(b)` is the **quaternion-derived node-hierarchy world** —
     i.e. compose from the SAME `translation` + `rotation`(quaternion) TRS the
     node emits, not the raw 3×3. (Building the IBM from the raw 3×3 while the
     node emits a quaternion drifts `node_world·IBM` from identity by ~4e-4 and
     compounds down the chain.)

   Emitting the raw bone-local positions with `IBM = inverse(bind_world)` makes
   the skin compute `world · world⁻¹ · v = v_local` for every vertex — i.e. every
   vertex stays in its bone's local frame, collapsed together = the spikes. The
   prior internal check (`IBM · worldBind = I`, weights sum to 1) is **vacuous**:
   it holds for ANY positions because the IBM is literally the inverse of the
   world matrix; it never tests that the emitted node hierarchy reassembles the
   figure. `validate_skin.py` does test that (bind round-trip error 4.5e-13 after
   the fix, plus an "assembly gain" gate proving the figure is tall, not a pile).

4. **Handedness.** Genesis3D is left-handed, glTF right-handed Y-up; the export
   keeps the **raw Genesis coordinates unchanged** (no axis flip) — Blender's
   import-axis options reorient. The point is that NO per-array flip is applied,
   so vertices, bones, IBMs and animation all stay in ONE consistent space; a flip
   applied to one array but not the others would explode the mesh.

---

## 9. Animation — CONFIRMED

Each animation is a `geMotion`, stored as a binary **`MTNB`** chunk. The actor's
motion **count** is the `u32` at payload `0x24` (= ACTR header +0x0c): male = 91,
doberman = 10 — both reproduced exactly by counting `MTNB` tags.

### 9a. MTNB (binary geMotion) — CONFIRMED

```
"MTNB"
u32  version (== 0xF0)
u32  hdr     ; low16 = name buffer length (incl NUL); byte3 = path-storage type
char[name_len] motion name (NUL-terminated)   ; e.g. "walk", "attack1", "OJUMP"
-- geMotion body (type 2) --
u32  path_count            ; == animated bone count
u32  name_checksum
u32  maintain_names flag
"SBKB" NameArray           ; bone names this motion drives (path_count names)
path_count × gePath
```

### 9b. gePath header (u32) — CONFIRMED

```
high16 == 0x1001           ; "binary path" magic
bit0   = has rotation channel    (QKFrame, read SECOND)
bit1   = has translation channel (VKFrame, read FIRST)
```

### 9c. QKFrame / VKFrame keyframe block — CONFIRMED

```
u32  block_size            ; byte length of everything after this field
u32  flags                 ; bit9 (0x200) = uniform time (t0,dt vs explicit times)
                           ; bit8 (0x100) = compressed value (QKFrame: LogQuat)
                           ; (>>16)&0xff  = interpolation type (runtime hint)
u32  key_count
[uniform]  f32 t0, f32 dt      ELSE  f32 times[key_count]
key_count × value:
    QKFrame rotation    = quaternion XYZW (4 f32)   [LogQuat 3 f32 if bit8]
    VKFrame translation = vec3 XYZ        (3 f32)
```

Over **all** motions: male = 91 motions / 2134 paths / 33 501 rotation keys /
53 077 translation keys; doberman = 10 / 340 / 6 742 / 9 133. Every key block
consumes **exactly** to the next path/MTNB boundary. 0 non-monotonic time arrays;
max `|quat|-1` = 0.00000 across all 86 578 keys.

### 9d. geMotion is NOT in the bind frame — retarget on export — CONFIRMED

The QKFrame quaternion is **XYZW** (glTF order), unit-normalised, applied as a
rotation matrix `q → R`; LogQuat (bit8) decompresses via `axis·sin(θ)/θ, cos(θ)`.
The byte read is correct. **But the motion's local TRS is NOT expressed in the
geBody bind frame.** A motion's t=0 local transform does NOT equal the bone's
bind local transform (e.g. male `BIP01` bind quat ≈ `(0,0,-0.707,0.707)` vs walk
t=0 ≈ identity), and applying a motion's raw local TRS directly **re-collapses
the figure to the exploded bone-local layout** (assembled extent drops 2615 → 827
again). The motion is a pose in its OWN rest frame; its t=0 is that rest.

To drive the bind-posed skin, retarget every keyframe into the bind frame:

```
node_anim_local(b, t) = bind_local(b) · motion_local(b, 0)⁻¹ · motion_local(b, t)
```

At `t=0` this is exactly `bind_local(b)` → the sane standing figure; later frames
apply the motion's delta **relative to its own rest** on top of the bind pose.
Resample rotation + translation onto the union of their key times, compose the
retargeted local matrix per time, and decompose back to the node's
rotation(quat)/translation channels. Verified across full durations: walk bobs
(z 2610 → 2683), `die_*` falls flat (figure rotates to horizontal), holds stay
put — never a spike. (glTF needs strictly-increasing input times, so dedup any
two channel times that collapse to the same float32.)

The exporter (`tools/nc2_model/act_gltf.py`) and `validate_skin.py` implement and
assert all of §8e + §9d (bind round-trip ≤ 1e-4, anim t=0 bbox ≤ 2× bind, and
gltf-validator 0 errors on the welded `.glb`).

---

## 10. Unified single-file glTF export — IMPLEMENTED & VALIDATED

`tools/nc2_model/act_gltf.py` (`write_gltf_full`) emits ONE self-contained
glTF 2.0 file that merges **everything** the `.act` carries — geometry, UVs, PBR
textures, skeleton, skinning and animation — into a single document. It is the
default `--gltf` behaviour and supersedes the two earlier split paths
(`act_to_obj.write_gltf` = textures only; `act_gltf_skinned` = skin+anim only,
now a legacy `--skinned-gltf` alias):

```
python -m nc2_model <model.act> --gltf out.glb           # RECOMMENDED: binary .glb
python -m nc2_model <model.act> --gltf out.gltf          # base64-embedded JSON
python -m nc2_model <model.act> --gltf out.glb --no-skin # static textured (weapon)
python -m nc2_model <model.act> --gltf out.gltf --external-textures  # PNGs on disk
python -m nc2_model <model.act> --gltf out.glb --scale 1 # ORIGINAL (unscaled) size
```

**Import scale (`--scale`, default `0.1`).** NC2/Genesis3D actor coordinates are
~10× Blender's metre, so an unscaled model imports ~10× too large (the user had
to hand-set the object scale to 0.1 on every import). The exporter therefore
applies a default uniform import scale of `0.1` as a single cosmetic **root
node** wrapping the whole scene — `nodes[root] = {scale:[s,s,s], children:[mesh
node + bone roots]}`, with `scenes[0].nodes = [root]`. Vertex/IBM bytes are
unchanged; the scale is non-destructive and matches the manual rescale the user
did before. `--scale 1` emits **no** root node and reproduces the original output
byte-for-byte; any other float sets a different uniform scale. A uniform scale
ABOVE the joints is the correct place for a skinned mesh: the skin matrix is
`jointWorld·IBM`, and with the root on top `jointWorld = rootScale·origJointWorld`
while `IBM = inverse(origJointWorld)`, so the product factors to `rootScale` — the
mesh scales uniformly and does NOT re-explode (verified by `validate_skin.py`,
whose bone-level bind round-trip is composed WITHOUT the cosmetic root and stays
at ~4.5e-13 error). Effective world bbox ≈ 1/10 of the model-space figure:

| Model | model-space tallest extent | effective world extent (×0.1) |
|---|---|---|
| `pak_mesh_private_male` | 2614.9 | **261.5** |
| `pak_doberman_black`    | 6107.9 | **610.8** |
| `pak_stiletto_lp`       |  468.6 |  **46.9** |

A skinned `.glb` carries one benign validator WARNING `NODE_SKINNED_MESH_NON_ROOT`
(the now-non-root mesh node's own transform is ignored by skinning — which is
exactly why the scale rides the joints, not the mesh node); still **0 errors**. A
static (weapon) mesh inherits the scale as the root's child with **0 warnings**.

**Output format** is chosen from the extension: `.glb` → a binary glTF (one
packed buffer chunk, **no base64**, smallest — recommended/default); `.gltf` →
JSON with the buffer embedded as a base64 data URI (~33 % larger). `--glb`
forces binary regardless of extension. `--external-textures` writes the PNGs next
to the file and references them by URI instead of embedding them.

What one unified file contains:
* **mesh** split into one primitive per material group, **welded + indexed** on
  the pair `(position_index, normal_index)` (VF00 normals are a separate index
  space, §3d, so a position can pair with several normals at hard edges; uv and
  bone-binding ride the position index). A `(pos, normal)` tuple that recurs is
  shared, and a `UNSIGNED_SHORT`/`UNSIGNED_INT` index buffer references them —
  this roughly **thirds** the vertex data versus the previous de-indexed
  (one-vertex-per-corner) emit, while staying byte-correct. Each primitive holds
  `POSITION` / `NORMAL` / `TEXCOORD_0`;
* **PBR materials** — `pbrMetallicRoughness.baseColorTexture` per group, embedded
  as a base64 PNG data URI (decoded from the `GeBm` chunk §7, or a representative
  `modeltextures` preview for characters; unresolved → material **without** a
  baseColorTexture, still valid);
* **skin** — joints + `inverseBindMatrices`, a node per bone (local TRS), and
  `JOINTS_0` / `WEIGHTS_0` (rigid, weight 1.0) **on the same textured
  primitives** — so textures, UVs and skin weights coexist on one mesh (the thing
  neither old exporter did alone);
* **animations** — one glTF clip per `geMotion` (rotation + translation samplers).

Graceful degradation: a body with no usable skeleton (or `--no-skin`) drops the
skin/anim and emits a **static textured** glTF (the weapon case); a texture that
binds at runtime (`ModelID`/`BitmapNumber`, §7) yields an untextured-but-valid
material rather than failing.

Validation (Khronos `gltf-validator` 2.0.0-dev.3.10) — both `.glb` and `.gltf`:

| Model | prims | mats (tex) | bones | anims | de-idx→welded verts | validator |
|---|---|---|---|---|---|---|
| `pak_mesh_private_male` | 3 (head/body/leg) | 3 (3) | 38 | 91 | 2112 → 545 | **0 / 0 / 0 / 0** |
| `pak_doberman_black`    | 1 | 1 (1) | 34 | 10 | 1800 → 529 | **0 / 0 / 0 / 0** |
| `pak_stiletto_lp`       | 1 | 1 (1) | 2 | 0 |  492 → 150 | **0 / 0 / 0 / 0** |

(errors / warnings / infos / hints). Welding cuts the vertex data ~3× while the
index buffer (`UNSIGNED_SHORT` here) is small; weights still sum to 1.0, all
joints in range, IBM count == joint count, indices in range, and every animation
sampler stays monotonic — welding keeps `JOINTS_0`/`WEIGHTS_0` aligned with the
welded positions. File-size before/after (old de-indexed base64 `.gltf` → new
welded `.glb` / welded `.gltf`):

| Model | old `.gltf` | welded `.glb` | welded `.gltf` |
|---|---|---|---|
| `pak_mesh_private_male` | 4 100 929 | **3 224 256** | 3 800 076 |
| `pak_doberman_black`    |   851 537 |   **598 120** |   726 278 |
| `pak_stiletto_lp`       |   129 086 |    **89 672** |   118 797 |

(`pak_mesh_private_male`'s `.glb` is dominated by its 91 baked animation clips in
the JSON chunk, not geometry; the geometry/base64 savings are proportionally
larger on the simpler models.) `--external-textures` on a `.glb` emits a benign
`URI is used in GLB container` INFO (still 0 errors); the default embedded `.glb`
is 0/0/0/0.

**Still not unifiable byte-exact:** character textures (`head`/`body`/`leg`) are
runtime-bound — the numeric `defs.modeltextures` id → on-disk filename hop lives
inside `neocronclient.exe` and is not in any shipped table (§7). The unified
export binds a *representative* `modeltextures` preview so characters look right,
but the exact per-character skin needs a Ghidra trace of the loader. Likewise the
generic-material items (`"Material #N"`, runtime `ModelID`/`BitmapNumber`) render
untextured-but-valid. These are the only gaps; everything else is single-file.

### Confidence summary

| Item                                   | Confidence | Basis                              |
|----------------------------------------|------------|------------------------------------|
| geBody array order/strides             | **HIGH**   | Ghidra `FUN_00520080` + exact walk |
| XSkinVertex bone binding (byte +22)    | **HIGH**   | both models, in-range + sane bind  |
| geStrBlock (`SBKB`) binary layout      | **HIGH**   | byte-exact names, ends on next tag |
| geBody_Bone rot/translation/parent     | **HIGH**   | Biped names + sane chain + ortho   |
| Bone bbox fields (first 6 floats)      | MEDIUM     | ±9e9 sentinel confirms; exact use rt-only |
| MTNB / gePath / QK·VKFrame layout      | **HIGH**   | Ghidra source paths + exact spans  |
| LogQuaternion (compressed) decode      | MEDIUM     | code path confirmed; neither test model uses it (all keys are the 4-f32 quat form) |
| translation bone-to-parent vs world    | MEDIUM-HIGH| hierarchy accumulation yields sane world bind; not cross-checked vs an in-engine render |
| VFS tail directory (offsets/sizes)     | LOW        | not fully parsed — motions/bones located by tag scan instead, which is robust and sufficient |

### Blocks for skinned write-back (writer team)

The §3a "vertex array" **is** the XSkinVertexArray: changing vertex **count** now
additionally requires (a) rewriting each new vertex's `bone_index` (+22), (b) the
count-prefixed XSkin array, (c) leaving BoneArray/SBKB intact. Position/UV-only
edits at fixed count remain safe (the writer splices only the float fields and
preserves the trailing 4 skin bytes). Motions reference bones by **name**
(NameArray), so renaming/reordering bones desyncs every `geMotion` PathArray —
avoid.
