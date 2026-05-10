# `UDP S->C 0x1d` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x1d`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **1**
- Captures with this packet: **1/17**
- Size (bytes): min **10**, avg **10**, max **10**
- Per-capture counts:
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 1

Samples (first 32 bytes inner data):

```
#1: 1d002002007fbe46cd45
```

<!-- /catalog-evidence -->

## Structure

UDP S→C raw 0x1d — sole 10-byte retail sample:
`1d 00 20 02 00 7f be 46 cd 45`.

```
[0]      0x1d                  sub-opcode
[1]      0x00                  CONSTANT
[2..3]   20 02                 LE16 = 0x0220 (entity/zone id?)
[4]      0x00                  reserved
[5..8]   7f be 46 cd            LE32 float = ~0xcd46be7f
                                ≈ -2.08e8 (or two LE16 floats)
[9]      0x45                  trailer
```

The `7f be 46 cd 45` tail looks like float-encoded coordinates
or a packed timestamp.

## Variants

Single 10-byte retail sample.

## Observed contexts

CREATION_LEVELING_LONG only — single emission. Insufficient
data for trigger semantics.

## Open questions

- Without more samples, structure is ambiguous.

## Server-side handler

Not handled. **Low priority** parity gap (1 retail sample).

