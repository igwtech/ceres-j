# `UDP S->C 0x03/0x2e` — Reliable/Weather

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x03/0x2e`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **869**
- Captures with this packet: **17/17**
- Size (bytes): min **13**, avg **13**, max **13**
- Top markers (within ±2s):
  - POKE_START × 4
  - BASELINE_HUD × 3
  - TRADE_OPEN × 2
  - TRADE_CASH_CONFIRM × 2
  - ACCEPT_INVITE_TEAM × 2
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 258
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 146
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 145
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 141
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 61
  - `RETAIL_NORMAN_20260426_200458` × 25
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 21
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 12
  - `RETAIL_DRSTONE_20260501_175315` × 12
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 10
  - `RETAIL_ODA_20260426_202428` × 10
  - `RETAIL_AUGUSTO_20260426_201952` × 10
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 6
  - `RETAIL_HANNIBAL_20260426_201501` × 5
  - `RETAIL_DRSTONE4_20260501_193336` × 3
  - `RETAIL_DRSTONE3_20260501_181349` × 2
  - `RETAIL_DRSTONE_20260501_172522` × 2

Samples (first 32 bytes inner data):

```
#1: 0101000000f3f00100f3f00100
```
```
#2: 0101000000f6f00100f6f00100
```
```
#3: 0100000000a0050000a0050000
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

