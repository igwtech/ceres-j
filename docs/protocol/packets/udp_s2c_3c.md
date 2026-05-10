# `UDP S->C 0x3c` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x3c`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **345**
- Captures with this packet: **16/17**
- Size (bytes): min **1**, avg **11**, max **12**
- Top markers (within ±2s):
  - BEFORE_KILL_MOB23 × 1
  - BEFORE_EXIT_SEWER × 1
  - KILL_MOB5 × 1
  - TRADE_CASH_CONFIRM × 1
  - FIRE_PVP_2 × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 96
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 62
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 62
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 62
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 16
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 10
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 10
  - `RETAIL_ODA_20260426_202428` × 7
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 6
  - `RETAIL_HANNIBAL_20260426_201501` × 4
  - `RETAIL_DRSTONE_20260501_175315` × 3
  - `RETAIL_AUGUSTO_20260426_201952` × 2
  - `RETAIL_NORMAN_20260426_200458` × 2
  - `RETAIL_DRSTONE3_20260501_181349` × 1
  - `RETAIL_DRSTONE_20260501_172522` × 1
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 1

Samples (first 32 bytes inner data):

```
#1: 3c040006532d0000ca150000
```
```
#2: 3c010005ef150000886e0000
```
```
#3: 3c0100098025000044520000
```

<!-- /catalog-evidence -->

## Structure

Verified 2026-05-10 against 43 cross-pcap samples from 17/17
retail captures. Fixed 12-byte body:

```
[0]      0x3c                   sub-opcode (constant)
[1]      0x01                   CONSTANT (42/43); 0x02 in DRSTONE,
                                 0x04 in PLAZA — rare variants
[2]      0x00                   CONSTANT
[3]      sub-action              varies (0x00..0x09 — 10 distinct
                                 values across samples)
[4..7]   LE32 #1                 random-looking, values cluster in
                                 range 0x0000..0x8000 (low 16 bits)
[8..11]  LE32 #2                 same range as #1 — independent value
```

The low-16-bit clustering of both LE32 fields suggests they may
be 2× LE16 pairs (X/Y coords, or two entity refs) rather than
true LE32s. High bytes `00 00` in 43/43 samples.

Sample retail bytes:
```
3c 01 00 03 6d 41 00 00 8b 3f 00 00    sub-action=3
3c 01 00 07 ee 3c 00 00 8f 55 00 00    sub-action=7
3c 01 00 02 c0 4a 00 00 e1 28 00 00    sub-action=2
```

## Variants

Single 12-byte form across all 345 retail observations. NO size
variation. The byte[1] alternative values (0x02, 0x04) are rare
(2/43 samples) — possibly different broadcast classes.

## Observed contexts

Catalog markers don't pinpoint a specific event class for 0x3c.
Per-capture distribution is fairly uniform across active
sessions — possibly a per-tick state-broadcast for a foreign
entity (NPC AI tick? other-player misc state?).

## Open questions

- Sub-action enum (0x00..0x09): which gameplay events map to
  each? Without correlated markers, unverified.
- LE32 field semantics: hashes? Quantized coords? Entity IDs?
  The high-byte clustering at zero suggests LE16 pairs but no
  decode confirms.
- Why 2/43 samples have byte[1] != 0x01: an alternate broadcast
  class or session-state flag?

## Server-side handler

Ceres-J does not currently emit raw {@code 0x3c}. The harness
treats it as unreplicable entity-state and skips retail emissions
(see `PcapReplayTest.isUnreplicableEntityState` — `0x3c` listed
explicitly).

