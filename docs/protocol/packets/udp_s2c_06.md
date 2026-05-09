# `UDP S->C 0x06` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x06`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **6**
- Captures with this packet: **4/17**
- Size (bytes): min **1**, avg **1**, max **1**
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 3
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 1
  - `RETAIL_NORMAN_20260426_200458` × 1
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 1

Samples (first 32 bytes inner data):

```
#1: 06
```
```
#2: 06
```
```
#3: 06
```

<!-- /catalog-evidence -->

## Structure

Single byte, no payload:

```
offset  size  field
   0      1   0x06   tag
```

All 6 observations across 4 captures are exactly the byte
`06`. No timestamp / marker correlation strong enough to point
at a specific gameplay event.

## Variants

None — every observed instance is the same 1-byte frame.

## Observed contexts

4 of 17 captures, 6 hits. No top markers — the packet appears
during steady-state gameplay rather than at named scenario edges.
Captures with hits include `RETAIL_VEHICLE_DRONE` (×3),
`RETAIL_CHARDEL_SUBWAY` (×1), `NORMAN` (×1) and
`CREATION_LEVELING_LONG` (×1).

## Open questions

- Is this a transport-level beacon (channel poke / keepalive)?
- Or a session-state notifier (e.g. "drop pending reliable", as
  the symmetric C→S `0x06` would suggest)?

## Server-side handler

[`server.gameserver.packets.client_udp.Sub0x06Recognized`](../../../src/main/java/server/gameserver/packets/client_udp/Sub0x06Recognized.java)
— recognise-only. No structure to act on yet.

