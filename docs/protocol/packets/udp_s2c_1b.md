# `UDP S->C 0x1b` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x1b`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **143484**
- Captures with this packet: **17/17**
- Size (bytes): min **19**, avg **19**, max **49**
- Top markers (within ±2s):
  - KILL_MOB2 × 201
  - BASELINE_HUD × 198
  - KILL_MOB × 195
  - MOB_COMBAT_AND_DESPAWN × 148
  - DISMISS_VEHICLE × 146
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 58099
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 22834
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 16754
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 16539
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 7120
  - `RETAIL_AUGUSTO_20260426_201952` × 5556
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 3169
  - `RETAIL_NORMAN_20260426_200458` × 2648
  - `RETAIL_ODA_20260426_202428` × 2037
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 1960
  - `RETAIL_DRSTONE_20260501_175315` × 1867
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 1580
  - `RETAIL_DRSTONE4_20260501_193336` × 897
  - `RETAIL_HANNIBAL_20260426_201501` × 824
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 824
  - `RETAIL_DRSTONE_20260501_172522` × 390
  - `RETAIL_DRSTONE3_20260501_181349` × 386

Samples (first 32 bytes inner data):

```
#1: 1b020000000359ba17c566e64fc3bd46874386ccb4c0bf8d8443000000002000
```
```
#2: 1b490100001f4b7750807d81400f0100004011
```
```
#3: 1b3e0100001f5f77307fbe8040d4050000ea11
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

