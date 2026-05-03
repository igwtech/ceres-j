# `UDP S->C 0x03/0x1b` — Reliable/Group1B/PosUpdate

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x03/0x1b`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **1104**
- Captures with this packet: **14/17**
- Size (bytes): min **11**, avg **16**, max **46**
- Top markers (within ±2s):
  - IN_WORLD × 22
  - GOING_OUTSIDE_WASTELANDS × 2
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 332
  - `RETAIL_ODA_20260426_202428` × 101
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 98
  - `RETAIL_NORMAN_20260426_200458` × 90
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 79
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 72
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 71
  - `RETAIL_HANNIBAL_20260426_201501` × 64
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 45
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 44
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 40
  - `RETAIL_AUGUSTO_20260426_201952` × 24
  - `RETAIL_DRSTONE4_20260501_193336` × 22
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 22

Samples (first 32 bytes inner data):

```
#1: c90000002000000000ff14
```
```
#2: c80000002000000000ff14
```
```
#3: 9f0000002000000000ff14
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

