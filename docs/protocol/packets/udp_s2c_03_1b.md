# `UDP S->C 0x03/0x1b` — Reliable/Group1B/PosUpdate

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x03/0x1b`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **1104**
- Captures with this packet: **14/17**
- Size (bytes): min **11**, avg **16**, max **46**
- Top markers (within ±2s):
  - IN_WORLD × 22
  - GOING_OUTSIDE_WASTELANDS × 2
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 332
  - `RETAIL_ODA_20260426_202428` × 101
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 98
  - `RETAIL_NORMAN_20260426_200458` × 90
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 79
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 72
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 71
  - `RETAIL_HANNIBAL_20260426_201501` × 64
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 45
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 44
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 40
  - `RETAIL_AUGUSTO_20260426_201952` × 24
  - `RETAIL_DRSTONE4_20260501_193336` × 22
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 22

Samples (first 32 bytes inner data):

```
#1: c90000002000000000ff14
```
```
#2: c80000002000000000ff14
```
```
#3: 9f0000002000000000ff14
```

<!-- /catalog-evidence -->

## Structure

Two variants verified 2026-05-09 against HANNIBAL + NORMAN retail
samples (`tools/extract-1b-broadcasts.py`):

### Variant A — minimal (12B, marker=0x20)

```
offset bytes  meaning                           constancy
[ 0]   1B    0x1b                               CONSTANT (sub-opcode)
[ 1..2] 2B   entity_id LE16                     varies (target map id)
[ 3..4] 2B   0x00 0x00                          CONSTANT
[ 5]   1B    0x20                               CONSTANT (marker — distinguishes
                                                 from variant B)
[ 6..9] 4B   entity-state LE32                  varies (mostly 0x00000000;
                                                 0x0000c800 in HANNIBAL)
[10]   1B    0xff                               CONSTANT
[11]   1B    action enum (0x14 dominant —        varies (state-dependent)
              0x10 also seen)
```

Catalog samples: `1b8d0000002000000000ff14`, `1b81000000200000c800ff10`.

### Variant B — extended (47B, marker=0x09)

```
offset bytes  meaning
[ 0]   1B    0x1b                              sub-opcode
[ 1..2] 2B   entity_id LE16                    varies
[ 3..4] 2B   0x00 0x80                         CONSTANT (marker B)
[ 5]   1B    0x09                              CONSTANT (variant marker)
[ 6..9] 4B   IEEE754 float (X position?)       varies
[10..13] 4B  IEEE754 float (Y position?)       varies
[14..17] 4B  IEEE754 float (Z position?)       varies
[18..27] 10B 0x00 padding                      CONSTANT
[28..31] 4B  IEEE754 float (X velocity?)       varies
[32..35] 4B  IEEE754 float (Y velocity?)       varies
[36..39] 4B  0x00 0x00 0x00 0x00               CONSTANT
[40..46] 7B  0x16 0x03 0x05 0x87 0x03          CONSTANT trailer (5B
                                                 stable; last 2 vary
                                                 less)
```

Catalog sample (NORMAN):
`1b010800800968b8e9c3b13e55c35af17e45000000000000000000000000dfb2564013842e40000000001603058703`

The 4-byte LE32 floats correspond to common world coordinates
(values near `c3...` indicate `~-200..-300` range and `45...` indicate
`~3600` — consistent with retail Plaza outdoor positions).

## Variants

| Variant | Size | Marker [3..5] | Frequency       | Use            |
|---------|------|---------------|-----------------|----------------|
| A       | 12 B | 00 00 20       | 3/13 (HANNIBAL) | Watchdog/ack   |
| B       | 47 B | 00 80 09       | 10/13 (NORMAN)  | Position/anim  |

Catalog records min/avg/max **11/16/46** — the 11B catalog form is
variant A with the leading 0x1b sub-op stripped (the catalog stores
inner-of-inner bytes for `0x03/...` packets).

## Observed contexts

Top markers: IN_WORLD × 22, GOING_OUTSIDE_WASTELANDS × 2.
Variant A is reliable position-authority echo (gameplay watchdog
input). Variant B is the full position+velocity broadcast for other
players — much rarer.

## Open questions

- Variant B float fields semantic (position vs velocity vs orientation
  matrix?) not yet pinned to ground truth via captured movement.
- Last 2 bytes `87 03` vs `87 03` — same in all NORMAN samples but
  not yet checked across multiple sessions.

## Server-side handler

`server.gameserver.packets.server_udp.PlayerPositionUpdate` emits
the verified 12-byte variant A layout. The 47-byte variant B is not
yet emitted — would require player-orientation state plumbed into
the broadcast path (out of scope for the watchdog-clear task).

