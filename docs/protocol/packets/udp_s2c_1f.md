# `UDP S->C 0x1f` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x1f`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **14525**
- Captures with this packet: **17/17**
- Size (bytes): min **8**, avg **10**, max **43**
- Top markers (within ±2s):
  - POKE_START × 26
  - FIRE_PVP × 19
  - INVITE_TEAM × 19
  - ACCEPT_INVITE_TEAM × 19
  - POKE_START_AFTER_BUYING_GEL × 18
- Per-capture counts:
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 3882
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 3878
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 2944
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 1630
  - `RETAIL_ODA_20260426_202428` × 428
  - `RETAIL_NORMAN_20260426_200458` × 354
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 343
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 304
  - `RETAIL_DRSTONE_20260501_175315` × 241
  - `RETAIL_HANNIBAL_20260426_201501` × 191
  - `RETAIL_AUGUSTO_20260426_201952` × 119
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 76
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 55
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 34
  - `RETAIL_DRSTONE_20260501_172522` × 24
  - `RETAIL_DRSTONE4_20260501_193336` × 15
  - `RETAIL_DRSTONE3_20260501_181349` × 7

Samples (first 32 bytes inner data):

```
#1: 1f0400308700ae004d0082018201
```
```
#2: 1f0400308700ae004d0082018201
```
```
#3: 1f030030ba02ba02
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

