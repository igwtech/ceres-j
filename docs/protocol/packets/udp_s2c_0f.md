# `UDP S->C 0x0f` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x0f`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **11**
- Captures with this packet: **5/17**
- Size (bytes): min **10**, avg **10**, max **10**
- Top markers (within ±2s):
  - DOOR_IF × 1
  - ZONING_AREAMC5_COMMANDUNIT × 1
  - ZONING_AREAMC5_EXIT × 1
- Per-capture counts:
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 4
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 2
  - `RETAIL_ODA_20260426_202428` × 2
  - `RETAIL_HANNIBAL_20260426_201501` × 2
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 1

Samples (first 32 bytes inner data):

```
#1: 0f0003fe001f01003804
```
```
#2: 0f0003ea021f01003804
```
```
#3: 0f000375001f01003804
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

