# `TCP S->C 0x830c` — Location

**Transport:** TCP  
**Direction:** S->C  
**Identifier:** `0x830c`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **75**
- Captures with this packet: **17/17**
- Size (bytes): min **21**, avg **30**, max **38**
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 25
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 7
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 7
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 6
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 5
  - `RETAIL_ODA_20260426_202428` × 4
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 4
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 3
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 3
  - `RETAIL_HANNIBAL_20260426_201501` × 3
  - `RETAIL_NORMAN_20260426_200458` × 2
  - `RETAIL_AUGUSTO_20260426_201952` × 1
  - `RETAIL_DRSTONE4_20260501_193336` × 1
  - `RETAIL_DRSTONE3_20260501_181349` × 1
  - `RETAIL_DRSTONE_20260501_172522` × 1
  - `RETAIL_DRSTONE_20260501_175315` × 1
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 1

Samples (first 32 bytes inner data):

```
#1: 830c010000000000000010000000706c617a612f706c617a615f703100
```
```
#2: 830c650000000000000000000000706c617a612f706c617a615f703300
```
```
#3: 830c0500000000000000000000007065707065722f7065707065725f703100
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

