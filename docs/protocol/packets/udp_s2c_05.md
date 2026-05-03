# `UDP S->C 0x05` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x05`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **3**
- Captures with this packet: **2/17**
- Size (bytes): min **1**, avg **7**, max **10**
- Per-capture counts:
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 2
  - `RETAIL_ODA_20260426_202428` × 1

Samples (first 32 bytes inner data):

```
#1: 05
```
```
#2: 050020030020003a0003
```
```
#3: 05002003002000050020
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

