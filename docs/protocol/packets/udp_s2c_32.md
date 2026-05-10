# `UDP S->C 0x32` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x32`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **2335**
- Captures with this packet: **7/17**
- Size (bytes): min **9**, avg **13**, max **19**
- Top markers (within ±2s):
  - IN_SUBWAY_CAR × 10
  - LEAVE_SUBWAY_CAR × 9
  - ENTER_SUBWAY_CAR × 7
  - AT_DEST_SUBWAY_CAR × 6
  - NPC_TALK2 × 3
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 1588
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 222
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 179
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 179
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 72
  - `RETAIL_HANNIBAL_20260426_201501` × 66
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 29

Samples (first 32 bytes inner data):

```
#1: 32fb03000000c84100
```
```
#2: 32fb03000000c84100
```
```
#3: 32fb03000000c84100
```

<!-- /catalog-evidence -->

## Structure

**Same packet as {@code 0x03/0x32}** — see `udp_s2c_03_32.md`
for the verified 9-byte structure (subway/transit state). The
catalog double-indexes 0x32 packets under both
`udp_s2c_32.md` (raw inner-body view, post-`[03][seq LE2]` strip)
and `udp_s2c_03_32.md` (full reliable form).

The body bytes are identical — what looks like a "raw 0x32"
packet here is the 0x32 inner sub-packet body of a
{@code 0x03/0x32} reliable. There's no separate raw 0x32
emission path on the wire.

```
[0]      0x32                   sub-opcode
[1..2]   subway_entity LE16     0x03f7..0x03fc range
[3]      action_byte             0x00 dominant
[4..7]   speed LE32 float        clean discrete values (0, 20, 25,
                                  30, 40, 60 km/h-like)
[8]      door/state byte         0x00 / 0x01
```

See `udp_s2c_03_32.md` for full retail-sample analysis (23
samples from 7 captures), variants table, and open questions.

## Variants

Single 9-byte form. See `udp_s2c_03_32.md`.

## Observed contexts

Subway/transit broadcasts during travel. Concentrated in PLAZA,
SUBWAY, and CHARDEL_SUBWAY captures.

## Open questions

See `udp_s2c_03_32.md` open questions (action enum, speed unit,
door-state interpretation).

## Server-side handler

See `udp_s2c_03_32.md` — Ceres-J does not currently emit subway
state. The C→S 0x03/0x32 path is decoded by SubtagRouter
(subway-related) but no S→C emitter exists.

