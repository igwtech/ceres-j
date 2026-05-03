# `UDP C->S 0x03/0x1f` — Reliable/GamePackets

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x03/0x1f`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **188947**
- Captures with this packet: **17/17**
- Size (bytes): min **3**, avg **8**, max **42**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_TRADING_PLAYER × 72
  - WALK_TO_PEPPERPARK_1 × 48
  - MOB_AGGRO × 45
  - MOB_COMBAT_AND_DESPAWN × 32
  - KILL_MOB2 × 29
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 74790
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 41524
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 15572
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 9784
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 9775
  - `RETAIL_NORMAN_20260426_200458` × 8125
  - `RETAIL_ODA_20260426_202428` × 7733
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 6072
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 4749
  - `RETAIL_HANNIBAL_20260426_201501` × 3216
  - `RETAIL_AUGUSTO_20260426_201952` × 2723
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 2526
  - `RETAIL_DRSTONE4_20260501_193336` × 968
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 760
  - `RETAIL_DRSTONE_20260501_175315` × 314
  - `RETAIL_DRSTONE_20260501_172522` × 204
  - `RETAIL_DRSTONE3_20260501_181349` × 112

Samples (first 32 bytes inner data):

```
#1: 04003e0400000001
```
```
#2: 04002522
```
```
#3: 04002522
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

