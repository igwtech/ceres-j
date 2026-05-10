# `UDP S->C 0x03/0x0d` — Reliable/TimeSync

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x03/0x0d`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **80**
- Captures with this packet: **17/17**
- Size (bytes): min **12**, avg **12**, max **12**
- Top markers (within ±2s):
  - AFTER_ENTER_SEWER × 1
  - IN_WORLD × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 28
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 7
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 7
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
#1: 3f692d00ffc80205d50a5800
```
```
#2: 3c252e0032850305d50a5800
```
```
#3: 9cc32e00f1220405d50a5800
```

<!-- /catalog-evidence -->

## Structure

S→C TimeSync — server response to C→S {@code 0x0c}
GetTimeSync. Fixed 12-byte body (post-0x0d sub-op):

```
[0..3]   server_time LE32        Timer.getIngametime() — 6-day
                                  cycle modulo encoding
[4..7]   client_time echo LE32   echoed from the C→S 0x0c request
[8..11]  trailer LE32            `d5 0a [LE16 var] 00`
                                  bytes 8..9 CONST `d5 0a`,
                                  bytes 10..11 vary per session
                                  (0x0020 in HANNIBAL/NORMAN/AUGUSTO,
                                   0x0051 in DRSTONE3, 0x0058 elsewhere)
```

Sample retail bytes (catalog post-0x0d strip):
```
3f 69 2d 00  ff c8 02 05  d5 0a 58 00     server=0x002d693f,
                                            client=0x0502c8ff
3c 25 2e 00  32 85 03 05  d5 0a 58 00     trailer=0x0058
9c c3 2e 00  f1 22 04 05  d5 0a 58 00
```

## Variants

Single 12-byte form across all 80 retail observations. NO size
variation. Only the trailer byte at body[10] varies between
sessions (0x20 dominant, 0x51 in DRSTONE3, 0x58 in older
captures).

## Observed contexts

Server response to C→S 0x0c GetTimeSync (~8s cadence). Modern
NCE 2.5 client requires this exchange — 5 missed replies → client
aborts with "Synchronisation to WorldServer failed".

The TimeSyncHeartbeatEvent in Ceres-J emits this at ~750 ms
interval to keep the client's state machine satisfied (see task
#155 for fix history).

## Open questions

- Trailer byte[10] semantic (0x20/0x51/0x58 across sessions):
  zone-tag? World-realm subid? The byte is session-stable but
  varies between captures.

## Server-side handler

`server.gameserver.packets.server_udp.TimeSync` (extends
PacketBuilderUDP1303). Verified bytes in
{@code TimeSync.WORLD_ID_TAIL = {0xd5, 0x0a, 0x20, 0x00}} —
matches 3/4 retail captures (HANNIBAL/NORMAN/AUGUSTO).

Tests:
- `TimeSyncByteIdentityTest` (5 tests, fixed 2026-05-09 in
  commit `69ff2d3`) — pins server_time + client_time positions
  + retail-verified trailer bytes.

Fired from:
- {@link
  server.gameserver.packets.client_udp.GetTimeSync#execute}
- {@link
  server.gameserver.internalEvents.TimeSyncHeartbeatEvent}
  (~750 ms periodic)

