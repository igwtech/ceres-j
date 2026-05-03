# `UDP C->S 0x2d` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x2d`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **339**
- Captures with this packet: **3/17**
- Size (bytes): min **6**, avg **6**, max **6**
- Top markers (within ±2s):
  - TAKE_DRUG_PARTY_B × 3
  - HEAL_PVP × 2
  - OUTSIDE_AREAM5_TRADING_PLAYER × 2
  - POKE_START × 1
  - TRADE_CONFIRM × 1
- Per-capture counts:
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 133
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 133
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 73

Samples (first 32 bytes inner data):

```
#1: 2d010000003f
```
```
#2: 2d010000003f
```
```
#3: 2d010000003f
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

