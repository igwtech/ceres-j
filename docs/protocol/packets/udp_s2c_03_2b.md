# `UDP S->C 0x03/0x2b` — Reliable/CityCom

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x03/0x2b`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **94**
- Captures with this packet: **3/17**
- Size (bytes): min **7**, avg **66**, max **919**
- Top markers (within ±2s):
  - OPEN_HOMETERM × 3
  - OPEN_HOMETERM_DELETEMAIL × 3
- Per-capture counts:
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 52
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 22
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 20

Samples (first 32 bytes inner data):

```
#1: 1a0f0001000056656869636c654c697374696e6700
```
```
#2: 170f000100040056656869636c654c697374696e670006003238303835000200
```
```
#3: 1a0f0001000056656869636c65436f6e74726f6c00
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

