# `UDP S->C 0x25` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x25`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **1**
- Captures with this packet: **1/17**
- Size (bytes): min **1**, avg **1**, max **1**
- Per-capture counts:
  - `RETAIL_HANNIBAL_20260426_201501` × 1

Samples (first 32 bytes inner data):

```
#1: 25
```

<!-- /catalog-evidence -->

## Structure

UDP S→C raw 0x25 — single 1-byte sample: bare `25`.

```
[0]   0x25                   bare sub-opcode singleton
```

Likely the LongPlayerInfo (0x03/0x25) sub-tag emitted as a
bare keepalive or the LOW byte of a longer packet that got
truncated during extraction.

## Variants

Single 1-byte retail sample.

## Observed contexts

HANNIBAL capture only — single emission. Insufficient data
for semantics.

## Open questions

- Is this an actual 1-byte signal or an extraction artifact?

## Server-side handler

Not handled. **Low priority** parity gap (1 retail sample).

