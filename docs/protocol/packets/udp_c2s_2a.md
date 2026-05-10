# `UDP C->S 0x2a` — RequestPos

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x2a`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **87**
- Captures with this packet: **17/17**
- Size (bytes): min **3**, avg **14**, max **16**
- Top markers (within ±2s):
  - AFTER_ENTER_SEWER × 1
  - IN_WORLD × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 34
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 8
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 7
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 6
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 5
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 5
  - `RETAIL_ODA_20260426_202428` × 4
  - `RETAIL_HANNIBAL_20260426_201501` × 4
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 3
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 3
  - `RETAIL_NORMAN_20260426_200458` × 2
  - `RETAIL_AUGUSTO_20260426_201952` × 1
  - `RETAIL_DRSTONE4_20260501_193336` × 1
  - `RETAIL_DRSTONE3_20260501_181349` × 1
  - `RETAIL_DRSTONE_20260501_172522` × 1
  - `RETAIL_DRSTONE_20260501_175315` × 1
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 1

Samples (first 32 bytes inner data):

```
#1: 2ab0a200007f763b69f12063ed2c0900
```
```
#2: 2ab0a200001eef3bcebab302f6120a00
```
```
#3: 2ab0a200005cd536e6e0da776f8d0b00
```

<!-- /catalog-evidence -->

## Structure

RequestPositionUpdate — client requests its own state refresh.
Fixed 16-byte body. Verified 2026-05-09 against 9 cross-pcap
samples (DRSTONE3, AUGUSTO, NORMAN, CASH, FULL, CREATION,
DRSTONE, DRSTONE4).

```
[0]      0x2a                   sub-opcode
[1..4]   character_uid LE32     player's character UID
                                 (e.g. 0x00017ebd in AUGUSTO/CASH/
                                  FULL/NORMAN; 0x00017f1a in
                                  CREATION/DRSTONE3/DRSTONE4)
[5..15]  request data (12 B)    varies per request — request token /
                                 client-side state hash
```

Sample retail bytes (catalog):
```
2a b0 a2 00 00 7f 76 3b 69 f1 20 63 ed 2c 09 00
2a b0 a2 00 00 1e ef 3b ce ba b3 02 f6 12 0a 00
```

The character_uid at [1..4] is session-stable (same value across
all C→S 0x2a emits within a session — confirms it identifies the
character making the request).

## Variants

Single 16-byte form across all 87 retail observations. NO size
variation.

## Observed contexts

Server response varies per session (per task #169):
- DRSTONE3: full refresh (PositionUpdate + CharInfo multi-megabyte
  multipart)
- AUGUSTO/NORMAN/CASH: init-burst refresh only (zoneInfo + TimeSync
  + ChatList)

The 12-byte request data at body[5..15] presumably encodes the
discriminator. Specific bytes that differentiate "full refresh"
vs "init-burst refresh" not yet pinned (see task #169).

## Open questions

- Body[5..15] content semantic: client-side state hash? Request
  type token? Server uses it to decide which response variant to
  emit (full refresh vs init-burst refresh).
- Frequency: ~1 emit per session in most captures, multiple in
  long-session captures — matches "client requests state refresh
  on resync triggers" hypothesis.

## Server-side handler

Decoded via {@link
server.gameserver.packets.client_udp.RequestPositionUpdate}:
- {@code skip(1)} past 0x2a
- Body content currently unparsed ("content is still unknown")
- {@code execute()} emits {@link
  server.gameserver.packets.server_udp.PositionUpdate} +
  {@link server.gameserver.packets.server_udp.CharInfo} +
  {@link server.gameserver.packets.server_udp.InfoResponse#zoneInfo}

The handler currently emits the FULL refresh unconditionally —
matches DRSTONE3 retail behavior but mismatches
AUGUSTO/NORMAN/CASH (which want init-burst-only). See task #169
for variant-discriminator decode follow-up.

