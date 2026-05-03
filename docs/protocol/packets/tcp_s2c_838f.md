# `TCP S->C 0x838f` — ?

**Transport:** TCP  
**Direction:** S->C  
**Identifier:** `0x838f`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **1392**
- Captures with this packet: **17/17**
- Size (bytes): min **7**, avg **7**, max **7**
- Top markers (within ±2s):
  - LEAVE_SUBWAY_CAR × 2
  - POKE_START × 2
  - AIM_PVP × 2
  - FIRE_PVP × 2
  - TRADE_OPEN × 2
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 378
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 272
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 230
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 227
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 84
  - `RETAIL_NORMAN_20260426_200458` × 36
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 30
  - `RETAIL_ODA_20260426_202428` × 30
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 20
  - `RETAIL_DRSTONE_20260501_175315` × 19
  - `RETAIL_AUGUSTO_20260426_201952` × 16
  - `RETAIL_HANNIBAL_20260426_201501` × 16
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 13
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 8
  - `RETAIL_DRSTONE4_20260501_193336` × 6
  - `RETAIL_DRSTONE3_20260501_181349` × 4
  - `RETAIL_DRSTONE_20260501_172522` × 3

Samples (first 32 bytes inner data):

```
#1: 838f0000000000
```
```
#2: 838f0000000000
```
```
#3: 838f0000000000
```

<!-- /catalog-evidence -->

## Structure

_TODO: byte-level layout. Use evidence above + matching pcaps to derive. Cite specific captures and offsets._

## Variants

_TODO: enumerate observed variants (e.g. different sub-tags, optional trailers)._

## Observed contexts

_TODO: when does this packet fire? Which scenarios trigger it? See top markers above for hints._

## Open questions

_TODO: list what we don't yet understand._

## Server-side handler

_TODO: pointer to the Ceres-J implementation, or 'not yet implemented' if missing._

