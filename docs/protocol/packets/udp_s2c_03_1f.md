# `UDP S->C 0x03/0x1f` — Reliable/GamePackets

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x03/0x1f`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **36537**
- Captures with this packet: **17/17**
- Size (bytes): min **4**, avg **5**, max **821**
- Top markers (within ±2s):
  - BASELINE_HUD × 36
  - KILL_MOB × 36
  - KILL_MOB2 × 36
  - POKE_START × 36
  - IN_SUBWAY_CAR × 29
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 10070
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 7308
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 5925
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 5867
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 2156
  - `RETAIL_NORMAN_20260426_200458` × 946
  - `RETAIL_ODA_20260426_202428` × 813
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 759
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 519
  - `RETAIL_DRSTONE_20260501_175315` × 479
  - `RETAIL_HANNIBAL_20260426_201501` × 466
  - `RETAIL_AUGUSTO_20260426_201952` × 443
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 310
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 178
  - `RETAIL_DRSTONE4_20260501_193336` × 135
  - `RETAIL_DRSTONE3_20260501_181349` × 82
  - `RETAIL_DRSTONE_20260501_172522` × 81

Samples (first 32 bytes inner data):

```
#1: 0400252333
```
```
#2: 0400252333
```
```
#3: 0400252333
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

