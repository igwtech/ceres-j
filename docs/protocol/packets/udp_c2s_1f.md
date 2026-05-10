# `UDP C->S 0x1f` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x1f`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **3424**
- Captures with this packet: **16/17**
- Size (bytes): min **3**, avg **4**, max **30**
- Top markers (within ±2s):
  - BEFORE_INTERACT_VENDOR_NPC × 6
  - AFTER_INTERACT_VENDOR_NPC × 6
  - MOB_AGGRO × 6
  - DRONE_EQUIPED_SLOT5 × 6
  - NPC_VENDOR_CLOSE × 6
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 1561
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 782
  - `RETAIL_DRSTONE_20260501_175315` × 200
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 186
  - `RETAIL_ODA_20260426_202428` × 160
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 89
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 89
  - `RETAIL_NORMAN_20260426_200458` × 89
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 76
  - `RETAIL_AUGUSTO_20260426_201952` × 74
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 51
  - `RETAIL_HANNIBAL_20260426_201501` × 42
  - `RETAIL_DRSTONE_20260501_172522` × 17
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 5
  - `RETAIL_DRSTONE4_20260501_193336` × 2
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 1

Samples (first 32 bytes inner data):

```
#1: 1f020155
```
```
#2: 1f3b0155
```
```
#3: 1f010015
```

<!-- /catalog-evidence -->

## Structure

Client-side raw {@code 0x1f} — counterpart to the wrapped
{@code 0x03/0x1f} GamePackets multiplexer (see `udp_c2s_03_1f.md`),
but RAW (unreliable) variant. Verified 2026-05-10 against 20
samples from 5 retail captures.

Fixed 4-byte wire form:

```
[0]      0x1f                   sub-opcode
[1]      target/event_id        varies per session/character
                                (e.g. 0xed in AUGUSTO, 0x08 in CASH,
                                0x02 in CREATION, 0x29/0x2b in DRSTONE4)
[2]      0x01                   CONSTANT
[3]      0x55                   CONSTANT
```

Within a single session byte[1] tends to be stable — the player's
current target/interactive entity. Across sessions/characters it
changes.

## Variants

Single 4-byte form across all 3,424 retail observations. NO size
variation.

## Observed contexts

Client emits when interacting with a target entity (mouseover /
combat target / dialog NPC). The {@code 01 55} trailer is fixed —
likely an event class discriminator.

## Open questions

- Byte[1] semantic: target NPC ID? Object reference?
- The {@code 0x55} ('U' ASCII) trailer is suspicious-constant —
  could be a hardcoded "client UI event" marker or a 1-byte
  enum tag.

## Server-side handler

Not currently emitted. Decoding in `GamePacketReaderUDP.decode()`
maps the raw 0x1f opcode but the body content isn't fully
processed. This packet is fire-and-forget client-side
notification — no server response expected.

If a future use case requires interpreting it, the handler should
read body[1] as the target/event_id and dispatch based on
session state.

