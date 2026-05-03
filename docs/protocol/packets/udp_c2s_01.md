# `UDP C->S 0x01` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x01`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **2009**
- Captures with this packet: **17/17**
- Size (bytes): min **3**, avg **8**, max **176**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_GENREP_OPEN × 80
  - IN_WORLD × 51
  - AFTER_CHAR_SELECT_WORLDENTRY × 19
  - INVENTORY_MANAGE × 2
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 599
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 227
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 155
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 127
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 116
  - `RETAIL_DRSTONE_20260501_175315` × 93
  - `RETAIL_AUGUSTO_20260426_201952` × 72
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 71
  - `RETAIL_HANNIBAL_20260426_201501` × 70
  - `RETAIL_DRSTONE_20260501_172522` × 68
  - `RETAIL_DRSTONE3_20260501_181349` × 67
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 65
  - `RETAIL_ODA_20260426_202428` × 61
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 60
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 57
  - `RETAIL_DRSTONE4_20260501_193336` × 54
  - `RETAIL_NORMAN_20260426_200458` × 47

Samples (first 32 bytes inner data):

```
#1: 01d18421e221e211a000
```
```
#2: 01d18421e221e211a000
```
```
#3: 01d18421e221e211a000
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

