# `UDP S->C 0x00` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x00`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **147**
- Captures with this packet: **12/17**
- Size (bytes): min **1**, avg **10**, max **58**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_GENREP_OPEN × 6
  - ZONING_AREAMC5_COMMANDUNIT × 3
  - ZONING_AREAMC5_EXIT × 3
  - BEFORE_ENTER_SEWER × 1
  - KILL_MOB4 × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 44
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 39
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 13
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 13
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 11
  - `RETAIL_ODA_20260426_202428` × 9
  - `RETAIL_HANNIBAL_20260426_201501` × 6
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 5
  - `RETAIL_DRSTONE_20260501_175315` × 3
  - `RETAIL_AUGUSTO_20260426_201952` × 2
  - `RETAIL_DRSTONE4_20260501_193336` × 1
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 1

Samples (first 32 bytes inner data):

```
#1: 003c010009e8060000f81a00
```
```
#2: 002d00640100
```
```
#3: 003c01000671290000d52800
```

<!-- /catalog-evidence -->

## Structure

Heterogeneous family — observations group cleanly by length but
not by a single header layout. Size distribution from the corpus
(147 hits across 12/17 captures):

| Size | Count | Sample shape | Probable layout |
|------|------:|--------------|-----------------|
| 12B  | 101   | `00 3c 01 00 [LE32] 00 00 [LE16] 00` | tag-`3c` status update |
| 6B   | 26    | `00 2d 00 [byte] 00 00`              | tag-`2d` short-event |
| 4B   | 6     | `00 00 [byte] 00`                    | tag-`00` micro |
| 5B   | 4     | various                              | unclear |
| 9B   | 3     | various                              | unclear |
| 3B/1B/13B/19B/58B | ≤2 each | various                | rare |

The leading sub-tag byte (`3c` / `2d` / `00`) drives variant
selection and is the dominant covariant with size. The `0x3c`
variant (12B) is by far the most common.

## Variants

- **`00 3c …` (12B, 101 hits, 11 captures)** — the dominant
  variant. Markers in ±2s windows include
  `OUTSIDE_AREAM5_GENREP_OPEN`, `ZONING_AREAMC5_*`, and
  `MEDBED_DONE`, all of which touch the player's location/state.
  Likely a state-change broadcast keyed by an LE32 entity id.
- **`00 2d …` (6B, 26 hits, 5 captures)** — short payload
  variant. Sample bytes (`00 2d 00 64 01 00`,
  `00 2d 00 88 00 00`, `00 2d 00 ec 00 00`) suggest a 1B+LE16
  or LE16+LE16 inner pair.
- **`00 00 …` (4B, 6 hits, 3 captures)** — micro variant. Two
  payload bytes only.
- **`00 03 …` (variable, 9B/13B observed)** — looks like an
  embedded reliable wrapper (`03 [seq2] 1f 01 00 25 23 …`),
  consistent with channel-duality where raw `0x00` carries an
  inner `0x03/0x1f` GamePacket with sub-tag `0x25`.

## Observed contexts

Top markers (within ±2s) across the corpus:

- `OUTSIDE_AREAM5_GENREP_OPEN` × 6
- `ZONING_AREAMC5_COMMANDUNIT` × 3
- `ZONING_AREAMC5_EXIT` × 3
- `BEFORE_ENTER_SEWER`, `KILL_MOB4`, `LEAVE_TEAM`,
  `LOCK_UNLOCK_SEATS_VEHICLE`, `MEDBED_DONE` × 1 each

The bias toward zone/genrep markers points at
location/teleport-edge events for the dominant `0x3c` variant.

## Open questions

- What entity does the LE32 in the `0x3c` variant identify?
  Player id, spawn id, or zone id?
- Are the `0x2d` and `0x3c` variants truly distinct messages, or
  two sub-types of the same envelope?
- Why does the `0x00 / 0x03 / 0x1f / 0x25 / 0x23` shape sometimes
  appear here instead of as a regular `0x03`-channel reliable?

## Server-side handler

[`server.gameserver.packets.client_udp.Sub0x00Recognized`](../../../src/main/java/server/gameserver/packets/client_udp/Sub0x00Recognized.java)
— recognise-only. Per the project rule "no speculation under 3
captures per variant", we don't decode fields until each variant
is verified against multi-capture evidence.

