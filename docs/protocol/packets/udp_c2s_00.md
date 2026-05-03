# `UDP C->S 0x00` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x00`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **6338**
- Captures with this packet: **17/17**
- Size (bytes): min **1**, avg **7**, max **231**
- Top markers (within ±2s):
  - POKE_START × 23
  - TRADE_CASH_CONFIRM × 17
  - FIRE_PVP_2 × 17
  - WHISPER × 16
  - AIM_PVP × 16
- Per-capture counts:
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 2987
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 2987
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 165
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 108
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 19
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 16
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 11
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 10
  - `RETAIL_HANNIBAL_20260426_201501` × 10
  - `RETAIL_ODA_20260426_202428` × 9
  - `RETAIL_DRSTONE_20260501_175315` × 6
  - `RETAIL_AUGUSTO_20260426_201952` × 3
  - `RETAIL_NORMAN_20260426_200458` × 2
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 2
  - `RETAIL_DRSTONE4_20260501_193336` × 1
  - `RETAIL_DRSTONE3_20260501_181349` × 1
  - `RETAIL_DRSTONE_20260501_172522` × 1

Samples (first 32 bytes inner data):

```
#1: 002d03000000
```
```
#2: 002d03000000
```
```
#3: 003c040006870000000012f3
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

