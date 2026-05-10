# `UDP C->S 0x0b` — CPing

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x0b`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **4868**
- Captures with this packet: **17/17**
- Size (bytes): min **5**, avg **5**, max **70**
- Top markers (within ±2s):
  - BASELINE_HUD × 7
  - KILL_MOB2 × 6
  - KILL_MOB × 5
  - POKE_START × 5
  - AIM_PVP × 4
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 1351
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 990
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 774
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 766
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 299
  - `RETAIL_NORMAN_20260426_200458` × 126
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 106
  - `RETAIL_ODA_20260426_202428` × 103
  - `RETAIL_DRSTONE_20260501_175315` × 69
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 67
  - `RETAIL_AUGUSTO_20260426_201952` × 56
  - `RETAIL_HANNIBAL_20260426_201501` × 53
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 40
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 26
  - `RETAIL_DRSTONE4_20260501_193336` × 19
  - `RETAIL_DRSTONE3_20260501_181349` × 12
  - `RETAIL_DRSTONE_20260501_172522` × 11

Samples (first 32 bytes inner data):

```
#1: 0bd7c80205
```
```
#2: 0b6fd60205
```
```
#3: 0b2ee20205
```

<!-- /catalog-evidence -->

## Structure

Client-side keepalive ping. Fixed 5-byte raw datagram (no
wrapper).

```
[0]      0x0b                   sub-opcode (constant)
[1..4]   client_time LE32        client's local time / counter,
                                 echoed back unchanged in the
                                 server's S→C 0x0b SPing reply
```

Sample retail bytes (catalog):
```
0b d7 c8 02 05    client_time=0x0502c8d7
```

Client emits these at ~1 Hz cadence throughout the session. Each
CPing triggers exactly one S→C 0x0b SPing reply from the server
(see `udp_s2c_0b.md`).

## Variants

Single 5-byte form across 4,868 observations in 17/17 captures.

## Observed contexts

Continuous emission throughout active sessions. Cadence is
client-driven; the server's only job is to reply with SPing
within the client's timeout window (typically a few seconds).

## Open questions

- The {@code client_time} field's exact semantic: wall-clock
  milliseconds since session start? GetTickCount() output?
  Across consecutive CPings the value advances monotonically by
  ~1 s in retail captures, consistent with {@code GetTickCount()}
  scaled. Server doesn't need to interpret — just echo.

## Server-side handler

Decoded via {@link
server.gameserver.packets.client_udp.CPing}:
- {@code skip(1)} past 0x0b
- {@code clienttime = readInt()} (LE32)
- {@code execute()} sends an SPing reply with the echoed
  client_time + the server's current Timer.getIngametime()

Tests:
- `CPingTest` — pins the LE32 client_time decode + sends SPing
  on execute().

