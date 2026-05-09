# `UDP S->C 0x11` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x11`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **2**
- Captures with this packet: **2/17**
- Size (bytes): min **10**, avg **10**, max **10**
- Top markers (within ±2s):
  - USE_ELEVATOR × 1
- Per-capture counts:
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 1
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 1

Samples (first 32 bytes inner data):

```
#1: 1100036e021f01003801
```
```
#2: 11000354031f01003801
```

<!-- /catalog-evidence -->

## Structure

10-byte body, identical layout to the `0x38` variant of
`UDP S->C 0x0f` but with value byte `0x01` instead of `0x04`:

```
offset  size  field
   0      1   0x11         outer tag
   1      1   0x00         envelope flag
   2      1   0x03         reliable channel
   3      2   seq LE16     reliable sequence
   5      1   0x1f         GamePacket marker
   6      2   txn LE16     0x0001
   8      1   0x38         sub-tag
   9      1   0x01         value byte (constant in this variant)
```

## Variants

Single 10-byte shape. Both observed hits identical apart from
the LE16 sequence number.

## Observed contexts

2 of 17 captures, 2 hits. Top marker: `USE_ELEVATOR` (×1).

The `USE_ELEVATOR` correlation, together with `0x0f`'s door /
zone-transition markers, supports the hypothesis that
`0x0f` and `0x11` are a related pair where the value byte
encodes door/elevator state (`0x04` "open"? `0x01` "engaged"?).

## Open questions

- Same as `0x0f` — meaning of the `0x38` sub-tag is unverified.
- Are `0x0f` and `0x11` two halves of a single state-machine
  (e.g. open + close), or different door types (one-shot vs
  recurring elevator)?

## Server-side handler

[`server.gameserver.packets.client_udp.Sub0x11Recognized`](../../../src/main/java/server/gameserver/packets/client_udp/Sub0x11Recognized.java)
— recognise-only. Two-capture evidence is below threshold.

