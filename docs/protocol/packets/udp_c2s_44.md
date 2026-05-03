# `UDP C->S 0x44` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x44`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **3**
- Captures with this packet: **3/17**
- Size (bytes): min **11**, avg **16**, max **27**
- Per-capture counts:
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 1
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 1
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 1

Samples (first 32 bytes inner data):

```
#1: 44400c000336061f01003d
```
```
#2: 44400c000336061f01003d
```
```
#3: 44400c00034b4d1f01003d11000000001100200100272c70ec4576
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

