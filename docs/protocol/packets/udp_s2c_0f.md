# `UDP S->C 0x0f` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x0f`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **11**
- Captures with this packet: **5/17**
- Size (bytes): min **10**, avg **10**, max **10**
- Top markers (within ±2s):
  - DOOR_IF × 1
  - ZONING_AREAMC5_COMMANDUNIT × 1
  - ZONING_AREAMC5_EXIT × 1
- Per-capture counts:
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 4
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 2
  - `RETAIL_ODA_20260426_202428` × 2
  - `RETAIL_HANNIBAL_20260426_201501` × 2
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 1

Samples (first 32 bytes inner data):

```
#1: 0f0003fe001f01003804
```
```
#2: 0f0003ea021f01003804
```
```
#3: 0f000375001f01003804
```

<!-- /catalog-evidence -->

## Structure

10-byte body. Two sub-shapes observed across 5 captures:

### Dominant variant — sub-tag `0x38` (8 of 11 hits)

```
offset  size  field
   0      1   0x0f         outer tag
   1      1   0x00         envelope flag
   2      1   0x03         reliable channel
   3      2   seq LE16     reliable sequence
   5      1   0x1f         GamePacket marker
   6      2   txn LE16     0x0001
   8      1   0x38         sub-tag (constant)
   9      1   0x04         value byte (constant in this variant)
```

Captures: `RETAIL_CREATION_LEVELING_LONG`, `RETAIL_FULL_PCAP_TRACE`,
`RETAIL_HANNIBAL`, `RETAIL_ODA` — 4 of 5 captures.

### Alternate variant — reliable type `0x1b` (3 of 11 hits)

```
offset  size  field
   0      1   0x0f         outer tag
   1      1   0x00         envelope flag
   2      1   0x03         reliable channel
   3      2   seq LE16     reliable sequence
   5      1   0x1b         reliable type — raw position update
   6      4   tail         constant `82 00 00 00 00` (sample padding)
```

Captures: `CREATION_LEVELING_LONG` (×2),
`ZONING_AND_ITEMS_LONG` (×1).

## Variants

- **`0x38 0x04` (dominant)** — door / zone-transition state delta.
  Cross-capture top-markers `DOOR_IF`,
  `ZONING_AREAMC5_COMMANDUNIT`, `ZONING_AREAMC5_EXIT` all fire
  within ±2s of one of these hits.
- **`0x1b 82 …` (alternate)** — embedded raw position update;
  the `0x1b` reliable type is the project's existing
  PlayerPositionUpdate channel (see `Movement.java` notes about
  reliable `0x03→0x1b` from retail).

## Observed contexts

5 of 17 captures, 11 hits. Top markers (±2s): `DOOR_IF` (×1),
`ZONING_AREAMC5_COMMANDUNIT` (×1), `ZONING_AREAMC5_EXIT` (×1) —
all door / zone-edge events. The dominant variant clusters around
door/transition triggers, supporting a "door-state broadcast"
hypothesis.

## Open questions

- What does the `0x38` sub-tag mean? Door state, elevator state,
  or generic zone-edge transition?
- Why does the alternate `0x1b` variant only show up in two
  captures? Is it a fall-back path or a different event family
  that happens to share the outer tag?

## Server-side handler

[`server.gameserver.packets.client_udp.Sub0x0FRecognized`](../../../src/main/java/server/gameserver/packets/client_udp/Sub0x0FRecognized.java)
— recognise-only. Acting on the `0x38` sub-tag needs a
Ghidra cross-check of the client's dispatch table (most likely a
case in `FUN_0055ec10` session-dispatch).

