# `UDP S->C 0x20` — Movement

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x20`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **49847**
- Captures with this packet: **16/17**
- Size (bytes): min **5**, avg **16**, max **29**
- Top markers (within ±2s):
  - DRONE_INUSE × 352
  - OUTSIDE_AREAM5_TALKING_GEORDI_MISSION_COMPLETE × 255
  - OUTSIDE_AREAM5_STORE_GOGO × 254
  - DRONE_PICKUP × 244
  - OUTSIDE_AREAM5_TBUYING × 133
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 19251
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 17580
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 4673
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 4673
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 976
  - `RETAIL_NORMAN_20260426_200458` × 766
  - `RETAIL_AUGUSTO_20260426_201952` × 594
  - `RETAIL_ODA_20260426_202428` × 378
  - `RETAIL_DRSTONE_20260501_172522` × 372
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 368
  - `RETAIL_HANNIBAL_20260426_201501` × 76
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 69
  - `RETAIL_DRSTONE_20260501_175315` × 36
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 21
  - `RETAIL_DRSTONE3_20260501_181349` × 8
  - `RETAIL_DRSTONE4_20260501_193336` × 6

Samples (first 32 bytes inner data):

```
#1: 2003007f6ce13dc566e65fc33acd43444116a8c11e54a1430000000000
```
```
#2: 2002007f59ba17c566e64fc3bd46874386ccb4c0bf8d84430000000020
```
```
#3: 2002002020
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

