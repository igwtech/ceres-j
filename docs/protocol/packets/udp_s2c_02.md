# `UDP S->C 0x02` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x02`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **1901**
- Captures with this packet: **17/17**
- Size (bytes): min **1**, avg **16**, max **90**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_GENREP_OPEN × 80
  - IN_WORLD × 51
  - AFTER_CHAR_SELECT_WORLDENTRY × 38
  - INVENTORY_MANAGE × 2
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 603
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 221
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 152
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 124
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 110
  - `RETAIL_DRSTONE_20260501_175315` × 90
  - `RETAIL_AUGUSTO_20260426_201952` × 69
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 68
  - `RETAIL_DRSTONE_20260501_172522` × 65
  - `RETAIL_DRSTONE3_20260501_181349` × 64
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 62
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 52
  - `RETAIL_DRSTONE4_20260501_193336` × 51
  - `RETAIL_HANNIBAL_20260426_201501` × 49
  - `RETAIL_NORMAN_20260426_200458` × 44
  - `RETAIL_ODA_20260426_202428` × 39
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 38

Samples (first 32 bytes inner data):

```
#1: 0201002f04000200330002014802023fe002031002040602058f020617020710
```
```
#2: 0202001f0400252333
```
```
#3: 0203001f0400251f0000c842
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

