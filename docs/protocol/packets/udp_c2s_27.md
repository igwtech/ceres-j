# `UDP C->S 0x27` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x27`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **45**
- Captures with this packet: **7/17**
- Size (bytes): min **5**, avg **5**, max **5**
- Per-capture counts:
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 10
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 10
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 8
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 7
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 6
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 2
  - `RETAIL_HANNIBAL_20260426_201501` × 2

Samples (first 32 bytes inner data):

```
#1: 27fb030000
```
```
#2: 27fa030000
```
```
#3: 27f8030000
```

<!-- /catalog-evidence -->

## Structure

UDP C→S raw 0x27 — **RequestInfoAboutWorldID** (unreliable
variant). Fixed 5-byte body. Same wire format as
`udp_c2s_03_27` — see that doc for the reliable variant.

```
[0]      0x27                    sub-opcode (constant)
[1..2]   world_id LE16            entity ID being queried
                                  (0x03f7..0x03fc subway range
                                  observed; other ranges possible)
[3..4]   00 00                    CONSTANT — possibly reserved
                                  flags
```

Sample retail bytes:
```
27 fb 03 00 00     entity 0x03fb (subway train ID)
27 fa 03 00 00     entity 0x03fa (subway train ID)
27 f8 03 00 00     entity 0x03f8 (subway train ID)
```

All 45 retail observations target entities in the subway
range (0x03f7..0x03fc per `npc_spawn_status.md`).

## Variants

Single 5-byte form. Same payload as the unreliable
`0x00→0x27` form (5B inner) and the reliable
`0x03/0x27 RequestInfoAboutWorldID` form. Channel-duality:
client picks unreliable or reliable based on context.

## Observed contexts

Emitted when the client wants info about a specific entity ID
(typically a subway train when nearby). Concentrated in PvP
captures (LONG_PARTY_A/B = 10 each) and subway captures
(CHARDEL_SUBWAY = 7).

The 45 observations across 7 captures suggest this fires when
the client first becomes aware of an entity (proximity-based
trigger).

## Open questions

- Why use raw 0x27 instead of the reliable `0x03/0x27`? The
  reliable form is more common in retail. Possibly used when
  the client wants to suppress retransmission overhead for
  speculative entity probes (e.g. before scrolling the camera
  toward a distant subway).
- The world_id range 0x03f7..0x03fc is well-known subway, but
  this packet might also be used for other entity types in
  captures we haven't seen.

## Server-side handler

Same handler as `udp_c2s_03_27` — should respond with
`0x03/0x23 InfoResponse` describing the queried entity.

Currently `SubtagRouter` recognises 0x27 but doesn't have a
dedicated handler for the unreliable form. Implementing it
should mirror the reliable handler with seq/ack stripped.

See `udp_c2s_03_27.md` for the canonical handler.

