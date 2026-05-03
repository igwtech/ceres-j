# `UDP S->C 0x03/0x2f` — Reliable/UpdateModel

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x03/0x2f`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **351**
- Captures with this packet: **14/17**
- Size (bytes): min **5**, avg **20**, max **102**
- Top markers (within ±2s):
  - MOB_DEAGGRO × 2
  - KILL_MOB2 × 2
  - GENREP_OPEN_PICK × 1
  - KILL_MOB × 1
  - SKILLS × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 105
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 83
  - `RETAIL_NORMAN_20260426_200458` × 42
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 33
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 33
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 16
  - `RETAIL_ODA_20260426_202428` × 15
  - `RETAIL_HANNIBAL_20260426_201501` × 8
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 4
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 3
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 3
  - `RETAIL_DRSTONE_20260501_172522` × 3
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 2
  - `RETAIL_AUGUSTO_20260426_201952` × 1

Samples (first 32 bytes inner data):

```
#1: 01000200330002014802023fe002031002040602058f02061702071002080502
```
```
#2: 01000200330002014802023fe002031002040602058f02061702071002080502
```
```
#3: 0100020025000201350205d6020621020708020804020a2201020b1300020d05
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

