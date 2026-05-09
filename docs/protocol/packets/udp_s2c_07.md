# `UDP S->C 0x07` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x07`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **4**
- Captures with this packet: **1/17**
- Size (bytes): min **10**, avg **10**, max **10**
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 4

Samples (first 32 bytes inner data):

```
#1: 070003fc001f01004a3a
```
```
#2: 07000349081f01004a09
```
```
#3: 070003c4151f01004a09
```

<!-- /catalog-evidence -->

## Structure

10-byte body, layout mirrors a reliable-wrapper inside an outer
envelope:

```
offset  size  field
   0      1   0x07         outer tag
   1      1   0x00         envelope flag (always zero)
   2      1   0x03         reliable channel
   3      2   seq LE16     reliable sequence
   5      1   0x1f         GamePacket marker
   6      2   txn LE16     0x0001 in every observation
   8      1   0x4a         sub-tag (constant across the 4 hits)
   9      1   value byte   `0x3a` (1×) and `0x09` (3×)
```

The trailing `1f / 01 00 / 4a` shape is consistent with the
project's "channel-duality" rule (raw `0xN` ≡ reliable
`0x03/0xN`). Sub-tag `0x4a` only shows in this single
vehicle/drone capture.

## Variants

Single 10-byte shape, two value bytes observed (`0x3a` and `0x09`).

## Observed contexts

Only the `RETAIL_VEHICLE_DRONE` capture exercised this channel.
No marker correlation strong enough to commit a scenario.

## Open questions

- What does the `0x4a` sub-tag mean? Vehicle/drone state delta is
  a likely candidate but unverified.
- Why is this shape so rare? Captures involving more vehicle use
  would clarify whether `0x07 outer` is vehicle-specific.

## Server-side handler

[`server.gameserver.packets.client_udp.Sub0x07Recognized`](../../../src/main/java/server/gameserver/packets/client_udp/Sub0x07Recognized.java)
— recognise-only. Single-capture evidence is below the project's
"≥3 captures to commit field semantics" threshold.
