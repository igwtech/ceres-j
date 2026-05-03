# `UDP S->C 0x03/0x2d` — Reliable/NPCData

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x03/0x2d`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **69755**
- Captures with this packet: **17/17**
- Size (bytes): min **5**, avg **51**, max **54**
- Top markers (within ±2s):
  - MOB_COMBAT_AND_DESPAWN × 232
  - DISMISS_VEHICLE × 215
  - MOB_DEAGGRO × 205
  - MOB_DEAD × 160
  - MOB_AGGRO2 × 125
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 50459
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 10707
  - `RETAIL_AUGUSTO_20260426_201952` × 1877
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 1119
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 1116
  - `RETAIL_DRSTONE_20260501_175315` × 773
  - `RETAIL_NORMAN_20260426_200458` × 666
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 647
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 522
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 406
  - `RETAIL_HANNIBAL_20260426_201501` × 377
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 265
  - `RETAIL_ODA_20260426_202428` × 215
  - `RETAIL_DRSTONE_20260501_172522` × 182
  - `RETAIL_DRSTONE4_20260501_193336` × 161
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 149
  - `RETAIL_DRSTONE3_20260501_181349` × 114

Samples (first 32 bytes inner data):

```
#1: 00f8090008bc34b542
```
```
#2: 53010000750000000070c3cdccffc20080bb44c047813f00000000b8fa2f012e
```
```
#3: 530100007140426a3a45ffffffff01b5282a000000aac20000aac2a8f92f0197
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

