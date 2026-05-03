# `UDP C->S 0x07` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x07`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **5**
- Captures with this packet: **5/17**
- Size (bytes): min **1012**, avg **1012**, max **1012**
- Per-capture counts:
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 1
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 1
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 1
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 1
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 1

Samples (first 32 bytes inner data):

```
#1: 070000090000000107200000461a7f01000001736372697074735c6c75615c73
```
```
#2: 07000009000000010720000046b0a200000001736372697074735c6c75615c73
```
```
#3: 07000009000000060720000046b0a200000001736372697074735c6c75615c73
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

