# `UDP C->S 0x1f` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x1f`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **3424**
- Captures with this packet: **16/17**
- Size (bytes): min **3**, avg **4**, max **30**
- Top markers (within ±2s):
  - BEFORE_INTERACT_VENDOR_NPC × 6
  - AFTER_INTERACT_VENDOR_NPC × 6
  - MOB_AGGRO × 6
  - DRONE_EQUIPED_SLOT5 × 6
  - NPC_VENDOR_CLOSE × 6
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 1561
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 782
  - `RETAIL_DRSTONE_20260501_175315` × 200
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 186
  - `RETAIL_ODA_20260426_202428` × 160
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 89
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 89
  - `RETAIL_NORMAN_20260426_200458` × 89
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 76
  - `RETAIL_AUGUSTO_20260426_201952` × 74
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 51
  - `RETAIL_HANNIBAL_20260426_201501` × 42
  - `RETAIL_DRSTONE_20260501_172522` × 17
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 5
  - `RETAIL_DRSTONE4_20260501_193336` × 2
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 1

Samples (first 32 bytes inner data):

```
#1: 1f020155
```
```
#2: 1f3b0155
```
```
#3: 1f010015
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

