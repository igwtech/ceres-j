# `UDP C->S 0x03/0x2b` — Reliable/CityCom

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x03/0x2b`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **123**
- Captures with this packet: **3/17**
- Size (bytes): min **22**, avg **53**, max **828**
- Top markers (within ±2s):
  - OPEN_HOMETERM × 6
  - OPEN_HOMETERM_DELETEMAIL × 5
  - OPEN_HOMETERM_READMAIL × 3
- Per-capture counts:
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 70
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 30
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 23

Samples (first 32 bytes inner data):

```
#1: 1ff400e6c1b2c68a4678b1ef0b0e16ff557e75092f08
```
```
#2: 1fd700e9976c27ad5188c6af0b0e16ff557e75092f08
```
```
#3: 17d700e9976c27ad5188c6af0b0e16ff557e75092f080f00dc00100056656869
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

