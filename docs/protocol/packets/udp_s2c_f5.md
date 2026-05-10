# `UDP S->C 0xf5` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0xf5`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **1**
- Captures with this packet: **1/17**
- Size (bytes): min **1**, avg **1**, max **1**
- Per-capture counts:
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 1

Samples (first 32 bytes inner data):

```
#1: f5
```

<!-- /catalog-evidence -->

## Structure

UDP S→C raw 0xf5 — single 1-byte sample: bare `f5`.

```
[0]   0xf5                   bare sub-opcode singleton
```

The high-byte 0xf5 is in the same range as 0xf6 (see
`udp_s2c_f6.md`) and 0xfe (TCP framing). Probably an
extraction artifact or a session-end signal.

## Variants

Single 1-byte sample.

## Observed contexts

CREATION_LEVELING_LONG only — single emission. Insufficient
data for semantics.

## Open questions

- Likely an extraction artifact or sentinel byte.

## Server-side handler

Not handled. **Low priority** parity gap (1 retail sample).

