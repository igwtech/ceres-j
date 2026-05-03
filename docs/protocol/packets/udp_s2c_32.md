# `UDP S->C 0x32` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x32`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **2335**
- Captures with this packet: **7/17**
- Size (bytes): min **9**, avg **13**, max **19**
- Top markers (within ±2s):
  - IN_SUBWAY_CAR × 10
  - LEAVE_SUBWAY_CAR × 9
  - ENTER_SUBWAY_CAR × 7
  - AT_DEST_SUBWAY_CAR × 6
  - NPC_TALK2 × 3
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 1588
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 222
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 179
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 179
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 72
  - `RETAIL_HANNIBAL_20260426_201501` × 66
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 29

Samples (first 32 bytes inner data):

```
#1: 32fb03000000c84100
```
```
#2: 32fb03000000c84100
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

