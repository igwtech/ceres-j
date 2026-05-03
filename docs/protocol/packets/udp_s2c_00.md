# `UDP S->C 0x00` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x00`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **147**
- Captures with this packet: **12/17**
- Size (bytes): min **1**, avg **10**, max **58**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_GENREP_OPEN × 6
  - ZONING_AREAMC5_COMMANDUNIT × 3
  - ZONING_AREAMC5_EXIT × 3
  - BEFORE_ENTER_SEWER × 1
  - KILL_MOB4 × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 44
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 39
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 13
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 13
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 11
  - `RETAIL_ODA_20260426_202428` × 9
  - `RETAIL_HANNIBAL_20260426_201501` × 6
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 5
  - `RETAIL_DRSTONE_20260501_175315` × 3
  - `RETAIL_AUGUSTO_20260426_201952` × 2
  - `RETAIL_DRSTONE4_20260501_193336` × 1
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 1

Samples (first 32 bytes inner data):

```
#1: 003c010009e8060000f81a00
```
```
#2: 002d00640100
```
```
#3: 003c01000671290000d52800
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

