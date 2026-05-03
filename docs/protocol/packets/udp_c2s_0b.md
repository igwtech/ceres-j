# `UDP C->S 0x0b` — CPing

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x0b`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **4868**
- Captures with this packet: **17/17**
- Size (bytes): min **5**, avg **5**, max **70**
- Top markers (within ±2s):
  - BASELINE_HUD × 7
  - KILL_MOB2 × 6
  - KILL_MOB × 5
  - POKE_START × 5
  - AIM_PVP × 4
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 1351
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 990
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 774
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 766
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 299
  - `RETAIL_NORMAN_20260426_200458` × 126
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 106
  - `RETAIL_ODA_20260426_202428` × 103
  - `RETAIL_DRSTONE_20260501_175315` × 69
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 67
  - `RETAIL_AUGUSTO_20260426_201952` × 56
  - `RETAIL_HANNIBAL_20260426_201501` × 53
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 40
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 26
  - `RETAIL_DRSTONE4_20260501_193336` × 19
  - `RETAIL_DRSTONE3_20260501_181349` × 12
  - `RETAIL_DRSTONE_20260501_172522` × 11

Samples (first 32 bytes inner data):

```
#1: 0bd7c80205
```
```
#2: 0b6fd60205
```
```
#3: 0b2ee20205
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

