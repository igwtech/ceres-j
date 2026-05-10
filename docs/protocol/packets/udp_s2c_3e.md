# `UDP S->C 0x3e` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x3e`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **1**
- Captures with this packet: **1/17**
- Size (bytes): min **1**, avg **1**, max **1**
- Top markers (within ±2s):
  - ZONING_AREAMC5_EXIT × 1
- Per-capture counts:
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 1

Samples (first 32 bytes inner data):

```
#1: 3e
```

<!-- /catalog-evidence -->

## Structure

UDP S→C raw 0x3e — single 1-byte sample: bare `3e`. Marker
correlates with `ZONING_AREAMC5_EXIT` — possibly a "zone-exit
acknowledged" signal.

```
[0]   0x3e                   bare sub-opcode signal
```

## Variants

Single 1-byte form.

## Observed contexts

CREATION_LEVELING_LONG only — single emission at zone-exit.

## Open questions

- Is this an actual zone-exit signal or a packet-extraction
  artifact?

## Server-side handler

Not handled. **Low priority** parity gap (1 retail sample).

