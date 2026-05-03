# `UDP C->S 0x3c` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x3c`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **60**
- Captures with this packet: **9/17**
- Size (bytes): min **12**, avg **12**, max **12**
- Top markers (within ±2s):
  - TALKED_NPC2_CLICKDIALOG4 × 1
  - OUTSIDE_AREAM5_TRADING_PLAYER × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 27
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 17
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 4
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 3
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 3
  - `RETAIL_ODA_20260426_202428` × 2
  - `RETAIL_HANNIBAL_20260426_201501` × 2
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 1
  - `RETAIL_AUGUSTO_20260426_201952` × 1

Samples (first 32 bytes inner data):

```
#1: 3c010001ffffffff00e8b845
```
```
#2: 3c0100073800000000005945
```
```
#3: 3c010001580000000060cf45
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

