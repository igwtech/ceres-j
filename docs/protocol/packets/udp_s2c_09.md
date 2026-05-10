# `UDP S->C 0x09` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x09`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **1**
- Captures with this packet: **1/17**
- Size (bytes): min **17**, avg **17**, max **17**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_GENREP_OPEN × 1
- Per-capture counts:
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 1

Samples (first 32 bytes inner data):

```
#1: 0900039d391f010025232913001b010100
```

<!-- /catalog-evidence -->

## Structure

UDP S→C raw 0x09 — likely a **compact-burst variant** like
`udp_c2s_44`. Sole 17-byte retail sample:
`09 00 03 9d 39 1f 01 00 25 23 29 13 00 1b 01 01 00`.

```
[0]      0x09                  byte[0] (counter low-byte?)
[1..2]   00 03                 ??? (counter / flag)
[3..4]   9d 39                 LE16 = 0x399d (seq?)
[5..]    1f 01 00 25 23 29 13 00 1b 01 01 00   GamePackets payload
```

Or alternatively this is the ServerReliableAck variant for raw
0x09 (parallel to 0x03/0x09 ack). Without more samples,
ambiguous.

## Variants

Single 17-byte retail sample.

## Observed contexts

CREATION_LEVELING_LONG only. Top marker
`OUTSIDE_AREAM5_GENREP_OPEN` (genrep teleporter activation).

## Open questions

- Is this a compact-burst variant, or the raw form of
  `0x03/0x09 ServerReliableAck`?
- Without more retail samples, the structure is ambiguous.

## Server-side handler

Not specifically handled. **Low priority** parity gap (1
retail sample only).

