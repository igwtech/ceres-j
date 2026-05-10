# `UDP C->S 0x0c` — TimeSync

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x0c`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **140**
- Captures with this packet: **17/17**
- Size (bytes): min **5**, avg **29**, max **103**
- Top markers (within ±2s):
  - AFTER_ENTER_SEWER × 1
  - IN_WORLD × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 49
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 30
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 12
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 11
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 8
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 6
  - `RETAIL_HANNIBAL_20260426_201501` × 5
  - `RETAIL_ODA_20260426_202428` × 4
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 3
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 3
  - `RETAIL_AUGUSTO_20260426_201952` × 2
  - `RETAIL_NORMAN_20260426_200458` × 2
  - `RETAIL_DRSTONE4_20260501_193336` × 1
  - `RETAIL_DRSTONE3_20260501_181349` × 1
  - `RETAIL_DRSTONE_20260501_172522` × 1
  - `RETAIL_DRSTONE_20260501_175315` × 1
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 1

Samples (first 32 bytes inner data):

```
#1: 0cffc80205
```
```
#2: 0c32850305
```
```
#3: 0cf1220405
```

<!-- /catalog-evidence -->

## Structure

GetTimeSync — client time-sync request. Fixed 5-byte body
(same wire shape as CPing 0x0b).

```
[0]      0x0c                   sub-opcode (constant)
[1..4]   client_time LE32        client's local time / counter,
                                 echoed back in the server's
                                 S→C 0x03/0x0d TimeSync reply
```

Sample retail bytes (catalog):
```
0c ff c8 02 05    client_time=0x0502c8ff
0c 32 85 03 05    client_time=0x05038532
0c f1 22 04 05    client_time=0x050422f1
```

## Variants

Single 5-byte form across all 140 retail observations. NO size
variation. Same opcode-and-payload shape as the C→S 0x0b CPing
keepalive (just different sub-opcode and corresponding S→C
response: 0x0d TimeSync vs 0x0b SPing).

## Observed contexts

Client emits at ~8 s cadence from session state 3/6 onward. The
modern NCE 2.5 client requires this exchange — after 5 missed
TimeSync replies, the client aborts with "Synchronisation to
WorldServer failed" and disconnects.

## Open questions

- Why emit BOTH CPing (0x0b, ~1 Hz) AND GetTimeSync (0x0c, ~8 s)
  for time sync? CPing seems to do the same job. Possibly
  different state-machine triggers (CPing for keepalive,
  GetTimeSync for state-3→state-4 transition).

## Server-side handler

Decoded via {@link
server.gameserver.packets.client_udp.GetTimeSync}:
- {@code skip(1)} past 0x0c
- {@code clienttime = readInt()} LE32
- {@code execute()} sends a {@link
  server.gameserver.packets.server_udp.TimeSync} reply with the
  echoed client_time + Timer.getIngametime()

Tests:
- `GetTimeSyncTest` (1 test) — pins TimeSync emit on execute().
- `TimeSyncByteIdentityTest` (5 tests) — pins the 16-byte 0x03/0x0d
  reply with the verified `d5 0a 20 00` trailer constant.

