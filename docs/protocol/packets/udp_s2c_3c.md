# `UDP S->C 0x3c` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x3c`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **345**
- Captures with this packet: **16/17**
- Size (bytes): min **1**, avg **11**, max **12**
- Top markers (within ±2s):
  - BEFORE_KILL_MOB23 × 1
  - BEFORE_EXIT_SEWER × 1
  - KILL_MOB5 × 1
  - TRADE_CASH_CONFIRM × 1
  - FIRE_PVP_2 × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 96
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 62
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 62
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 62
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 16
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 10
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 10
  - `RETAIL_ODA_20260426_202428` × 7
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 6
  - `RETAIL_HANNIBAL_20260426_201501` × 4
  - `RETAIL_DRSTONE_20260501_175315` × 3
  - `RETAIL_AUGUSTO_20260426_201952` × 2
  - `RETAIL_NORMAN_20260426_200458` × 2
  - `RETAIL_DRSTONE3_20260501_181349` × 1
  - `RETAIL_DRSTONE_20260501_172522` × 1
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 1

Samples (first 32 bytes inner data):

```
#1: 3c040006532d0000ca150000
```
```
#2: 3c010005ef150000886e0000
```
```
#3: 3c0100098025000044520000
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

