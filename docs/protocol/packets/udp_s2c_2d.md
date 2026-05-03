# `UDP S->C 0x2d` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x2d`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **8**
- Captures with this packet: **2/17**
- Size (bytes): min **6**, avg **6**, max **6**
- Per-capture counts:
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 4
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 4

Samples (first 32 bytes inner data):

```
#1: 2d000002000a
```
```
#2: 2d007402000a
```
```
#3: 2d005c00000a
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

