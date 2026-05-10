# `UDP S->C 0xf6` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0xf6`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **1**
- Captures with this packet: **1/17**
- Size (bytes): min **56**, avg **56**, max **56**
- Per-capture counts:
  - `RETAIL_HANNIBAL_20260426_201501` × 1

Samples (first 32 bytes inner data):

```
#1: f60f01793c4a0128f60f3a0003ba012d8d020000714071f8d845ffffffff0105
```

<!-- /catalog-evidence -->

## Structure

UDP S→C raw 0xf6 — single 56-byte retail sample. Looks like a
**compact-burst** carrying chained NPC/movement broadcasts.

```
[0..3]   f6 0f 01 79              counter+flag header?
[4..]    3c 4a 01 28 ... [03][seq=0x01ba][2d]   chained subs:
         - 0x3c entity-action (12B, see udp_s2c_3c.md)
         - 0x03/0x2d NPCData with position floats
```

The byte content includes a sub-packet boundary marker `f6 0f
3a` mid-stream — likely 2-3 sub-packets concatenated in one
0x13-less burst.

## Variants

Single 56-byte retail sample.

## Observed contexts

HANNIBAL only — single emission. Likely a one-off batched
NPC/entity event broadcast.

## Open questions

- Same compact-burst family as 0x44/0x45/0xc4/0xef/0x3a etc.
  byte[0]=0xf6 is just the counter low-byte coincidence.

## Server-side handler

Not handled. Falls into `UnknownClientUDPPacket`-like path.
**Low priority** parity gap (1 retail sample).

