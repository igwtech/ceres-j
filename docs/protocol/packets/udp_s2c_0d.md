# `UDP S->C 0x0d` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x0d`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **4**
- Captures with this packet: **2/17**
- Size (bytes): min **10**, avg **10**, max **10**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_GENREP_OPEN × 2
- Per-capture counts:
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 2
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 2

Samples (first 32 bytes inner data):

```
#1: 0d000374031f01002d05
```
```
#2: 0d00035a001f01002d00
```
```
#3: 0d00039a391f01002d57
```

<!-- /catalog-evidence -->

## Structure

10-byte body, reliable-wrapper-shaped:

```
offset  size  field
   0      1   0x0d         outer tag
   1      1   0x00         envelope flag (always zero)
   2      1   0x03         reliable channel
   3      2   seq LE16     reliable sequence
   5      1   0x1f         GamePacket marker
   6      2   txn LE16     0x0001 in every observation
   8      1   0x2d         sub-tag (constant — NPC-data on C→S)
   9      1   value byte   varies (`0x05`, `0x00`, `0x57`)
```

The `0x2d` sub-tag matches the C→S NPC channel handled by the
existing `SubtagRouter` route (`0x03/0x2d`). The S→C direction
here looks like a 1-byte counterpart — likely an NPC-state delta
ping, but two-capture evidence is below the project's threshold
to commit field semantics.

## Variants

Single 10-byte shape across all 4 hits. Value byte at offset 9
is the only varying field.

## Observed contexts

2 of 17 captures, 4 hits. Top marker:
`OUTSIDE_AREAM5_GENREP_OPEN` (×2). The capture pair is
`ZONING_AND_ITEMS_LONG` (×2) and `CREATION_LEVELING_LONG` (×2),
both featuring zone transitions through Genrep terminals.

## Open questions

- What does the value byte at offset 9 represent? In one capture
  pair the values are `0x05` and `0x00`; in the other they jump
  to `0x57`. Could be an NPC-state delta enum.
- Is this the S→C peer of the C→S `0x03/0x2d` drone-control
  channel, or an unrelated server-pushed event reusing the
  sub-tag space?

## Server-side handler

[`server.gameserver.packets.client_udp.Sub0x0DRecognized`](../../../src/main/java/server/gameserver/packets/client_udp/Sub0x0DRecognized.java)
— recognise-only. Two-capture evidence is below the
"≥3 captures to commit field semantics" threshold.

