# `UDP S->C 0x3a` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x3a`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **1**
- Captures with this packet: **1/17**
- Size (bytes): min **49**, avg **49**, max **49**
- Per-capture counts:
  - `RETAIL_HANNIBAL_20260426_201501` × 1

Samples (first 32 bytes inner data):

```
#1: 3a0003b9012d7e0200007500000080404433f3d7c3003022450048813f000000
```

<!-- /catalog-evidence -->

## Structure

UDP S→C raw 0x3a — sole 49-byte retail sample. Looks like a
**compact-burst** carrying an NPCData (0x2d) sub-packet with
position floats.

```
[0..1]   3a 00                 byte[0]=counter low?, [1]=flag
[2..]    [03][seq=0x01b9][2d]   reliable sub-packet:
         7e 02 00 00            entity ID 0x0000027e
         75 00 00 00            type byte 0x75
         80 40 44 33            LE32 float = ~3.06e7 (or 0x33444080)
         f3 d7 c3 00            LE32 float
         30 22 45 00            LE32 float
         48 81 3f 00            LE32 float
         00 00 ...
         fcf50f01 dedb4e01 f04d8e11 ffffffff
                                    additional fields
```

The `[03][seq=0x01b9][2d]` inner sub-packet is identical in
shape to a reliable `0x03/0x2d NPCData`. The outer 0x3a may be
acting as a "delivery wrapper" for batched NPCData broadcasts.

## Variants

Single 49-byte retail sample.

## Observed contexts

HANNIBAL only — single emission. Likely a one-off broadcast
event (NPC spawn / state-change burst).

## Open questions

- Is the 0x3a outer a true distinct opcode or another
  compact-burst variant where byte[0] = counter?
- The inner NPCData layout matches `0x03/0x2d` reliable —
  suggests this is a delivery channel for the same data.

## Server-side handler

Not handled. **Low priority** parity gap (1 retail sample).

