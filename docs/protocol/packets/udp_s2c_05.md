# `UDP S->C 0x05` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x05`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **3**
- Captures with this packet: **2/17**
- Size (bytes): min **1**, avg **7**, max **10**
- Per-capture counts:
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 2
  - `RETAIL_ODA_20260426_202428` × 1

Samples (first 32 bytes inner data):

```
#1: 05
```
```
#2: 050020030020003a0003
```
```
#3: 05002003002000050020
```

<!-- /catalog-evidence -->

## Structure

UDP S→C raw 0x05 — TWO distinct forms across 3 retail samples:

```
1-byte form (1/3): bare `05`
   — sub-opcode singleton, possibly a status marker.

10-byte form (2/3):
   05 00 [LE16][LE16][LE16][LE16]
   e.g. 05 00 20 03  00 20  00 3a  00 03    CREATION
        05 00 20 03  00 20  00 05  00 20    CREATION
   — looks like 4× LE16 fields after the 0x05 00 prefix.
```

The 10-byte form's second LE16 (0x0320 = 800 in both samples)
is byte-stable; subsequent fields vary — possibly a
`[zone][slot][?][?]` quadruple.

## Variants

- **1-byte** (1 sample, ODA): bare opcode signal.
- **10-byte** (2 samples, CREATION_LEVELING): 4× LE16 payload.

## Observed contexts

Only 3 retail samples across 2 captures. Insufficient data for
firm semantics. The 10-byte forms are emitted ~within seconds
of each other in CREATION_LEVELING — possibly successive
state updates of the same kind.

## Open questions

- The 4× LE16 payload's exact fields. Without more samples,
  not pinnable.
- The 1-byte form — different message entirely or same
  packet stripped to its opcode?

## Server-side handler

Not currently emitted by Ceres-J — no `case 0x05:` raw S→C
emit path. **Low priority** parity gap (3 retail samples).

