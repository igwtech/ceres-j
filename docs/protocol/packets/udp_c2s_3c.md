# `UDP C->S 0x3c` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x3c`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **60**
- Captures with this packet: **9/17**
- Size (bytes): min **12**, avg **12**, max **12**
- Top markers (within ±2s):
  - TALKED_NPC2_CLICKDIALOG4 × 1
  - OUTSIDE_AREAM5_TRADING_PLAYER × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 27
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 17
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 4
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 3
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 3
  - `RETAIL_ODA_20260426_202428` × 2
  - `RETAIL_HANNIBAL_20260426_201501` × 2
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 1
  - `RETAIL_AUGUSTO_20260426_201952` × 1

Samples (first 32 bytes inner data):

```
#1: 3c010001ffffffff00e8b845
```
```
#2: 3c0100073800000000005945
```
```
#3: 3c010001580000000060cf45
```

<!-- /catalog-evidence -->

## Structure

C→S raw 0x3c — same wire shape as S→C 0x3c (see
`udp_s2c_3c.md`). Verified 2026-05-10 against 45 cross-pcap
samples from 17/17 retail captures.

```
[0]      0x3c                   sub-opcode (constant)
[1]      0x01                   CONSTANT (44/45 samples; 0x02 in
                                 DRSTONE/PLAZA, 0x04 in PLAZA — rare)
[2]      0x00                   CONSTANT
[3]      sub-action              0x00..0x09 (10 distinct values)
[4..7]   LE32 #1                 entity ref or sentinel `ffffffff`
                                 (sentinel for sub-actions 0x05, 0x09)
[8..11]  LE32 #2 float           clean LE32 float — values like
                                  1593.0, 19712.0, 22193.0, 31113.0
                                  (likely coords / distances / scales)
```

Sample retail bytes:
```
3c 01 00 03 47 00 00 00 00 62 ad 46    sub=3, ref=71, float=22193.0
3c 01 00 09 fe ff ff ff 00 20 c7 44    sub=9, ref=ffffffff, float=1593.0
3c 01 00 05 ff ff ff ff 00 18 0e 46    sub=5, ref=ffffffff, float=9094.0
```

## Variants

Single 12-byte form across all 60 retail observations. NO size
variation. The C→S form is the COMPLEMENT of S→C 0x3c — same
wire shape, opposite direction. Used for entity-action
notifications (mouseover, target-acquired, distance-update?).

## Observed contexts

Client emits during gameplay events involving a target entity.
The float at [8..11] looks like a distance or coord value —
varies between samples. Sub-actions 0x05/0x09 use `ffffffff`
sentinel suggesting "no specific entity / global event".

## Open questions

- Sub-action enum (0x00..0x09) mapping to specific gameplay
  events not yet pinned.
- LE32 float at [8..11] semantic: distance to target? Position
  coord? Combat range?
- Why do sub-actions 0x05 and 0x09 use `ffffffff` sentinel —
  these specific sub-actions don't reference a target entity?

## Server-side handler

Not currently decoded by Ceres-J. Recognised in
`GamePacketReaderUDP.decode()` but body is unparsed. The
fire-and-forget nature (no observed S→C response in pcaps)
suggests the server only logs / ignores it.

