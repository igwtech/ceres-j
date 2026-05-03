# `UDP C->S 0x32` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x32`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **2156**
- Captures with this packet: **7/17**
- Size (bytes): min **9**, avg **17**, max **21**
- Top markers (within ±2s):
  - AT_DEST_SUBWAY_CAR × 10
  - IN_SUBWAY_CAR × 6
  - LEAVE_SUBWAY_CAR × 4
  - ENTER_SUBWAY_CAR × 3
  - NPC_TALK × 3
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 1832
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 98
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 79
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 79
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 36
  - `RETAIL_HANNIBAL_20260426_201501` × 18
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 14

Samples (first 32 bytes inner data):

```
#1: 32fb03000000c84100
```
```
#2: 32fc03000000000000
```
```
#3: 32fb03000000c84100
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

