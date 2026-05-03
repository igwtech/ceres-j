# `UDP S->C 0x01` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x01`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **13**
- Captures with this packet: **3/17**
- Size (bytes): min **3**, avg **3**, max **3**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_GENREP_OPEN × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 6
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 4
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 3

Samples (first 32 bytes inner data):

```
#1: 01445a
```
```
#2: 01455a
```
```
#3: 01465a
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

