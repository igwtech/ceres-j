# `UDP S->C 0x0d` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x0d`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **4**
- Captures with this packet: **2/17**
- Size (bytes): min **10**, avg **10**, max **10**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_GENREP_OPEN × 2
- Per-capture counts:
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 2
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 2

Samples (first 32 bytes inner data):

```
#1: 0d000374031f01002d05
```
```
#2: 0d00035a001f01002d00
```
```
#3: 0d00039a391f01002d57
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

