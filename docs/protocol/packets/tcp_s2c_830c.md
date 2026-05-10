# `TCP S->C 0x830c` — Location

**Transport:** TCP  
**Direction:** S->C  
**Identifier:** `0x830c`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **75**
- Captures with this packet: **17/17**
- Size (bytes): min **21**, avg **30**, max **38**
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 25
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 7
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 7
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 6
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 5
  - `RETAIL_ODA_20260426_202428` × 4
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 4
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 3
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 3
  - `RETAIL_HANNIBAL_20260426_201501` × 3
  - `RETAIL_NORMAN_20260426_200458` × 2
  - `RETAIL_AUGUSTO_20260426_201952` × 1
  - `RETAIL_DRSTONE4_20260501_193336` × 1
  - `RETAIL_DRSTONE3_20260501_181349` × 1
  - `RETAIL_DRSTONE_20260501_172522` × 1
  - `RETAIL_DRSTONE_20260501_175315` × 1
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 1

Samples (first 32 bytes inner data):

```
#1: 830c010000000000000010000000706c617a612f706c617a615f703100
```
```
#2: 830c650000000000000000000000706c617a612f706c617a615f703300
```
```
#3: 830c0500000000000000000000007065707065722f7065707065725f703100
```

<!-- /catalog-evidence -->

## Structure

TCP Location packet — server tells the client which zone (BSP
file) to load. Verified 2026-05-09 against retail pepper_p3
capture + 75 cross-pcap observations.

```
[0..1]   83 0c                  TCP opcode
[2..5]   location LE32           zone ID (e.g. 0x01 = plaza_p1,
                                  0x65 = plaza_p3, 0x05 = pepper_p1,
                                  9999 = apartment instance)
[6..9]   apartment_flag LE32     1 if location == 9999 (apartment),
                                  else 0
[10..13] 4-byte zero field       padding — must be present or
                                  client truncates zone name by 4
                                  chars and fails BSP load
[14..]   zone path C-string      ASCII world path + null terminator
                                  (e.g. "plaza/plaza_p1\0")
```

Catalog samples:
```
83 0c 01 00 00 00 00 00 00 00 00 00 10 00 00 00 plaza/plaza_p1\0
83 0c 65 00 00 00 00 00 00 00 00 00 00 00 00 00 plaza/plaza_p3\0
83 0c 05 00 00 00 00 00 00 00 00 00 00 00 00 00 pepper/pepper_p1\0
```

## Variants

Single layout across 75 retail observations. The zone path
varies: `plaza/plaza_p1`, `plaza/plaza_p3`, `pepper/pepper_p1`,
etc. Apartment paths use the `apps/clean/` prefix for instanced
apartment zones.

## Observed contexts

Triggered by:
- World-entry (initial zone load after handshake)
- Zone transition (player crosses zone boundary)
- Apartment door interactions (loads instance)

The client uses this to load the corresponding `.bsp` from
`worlds/<zone path>.bsp`.

## Open questions

- Bytes [10..13] purpose — empirically must be present (without
  them client truncates zone name 4 bytes), but their semantic
  isn't pinned. Could be a zone-version hash or instance ID.
- The location byte at [2..5] mapping to specific zones —
  catalog has 75 observations across many zones; full mapping
  would be in the client's zone table.

## Server-side handler

`server.gameserver.packets.server_tcp.Location` — emits the
verified format. Handles apartment instancing via the special
location 9999.

Fired from:
- {@link
  server.gameserver.packets.client_udp.Movement} — zone-boundary
  crossing detection
- {@link
  server.gameserver.packets.client_udp.Zoning2} — confirmed
  zone transition
- {@link
  server.gameserver.internalEvents.WorldEntryEvent} — initial
  world-entry burst

