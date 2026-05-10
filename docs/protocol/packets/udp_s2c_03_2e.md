# `UDP S->C 0x03/0x2e` — Reliable/Weather

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x03/0x2e`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **869**
- Captures with this packet: **17/17**
- Size (bytes): min **13**, avg **13**, max **13**
- Top markers (within ±2s):
  - POKE_START × 4
  - BASELINE_HUD × 3
  - TRADE_OPEN × 2
  - TRADE_CASH_CONFIRM × 2
  - ACCEPT_INVITE_TEAM × 2
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 258
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 146
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 145
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 141
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 61
  - `RETAIL_NORMAN_20260426_200458` × 25
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 21
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 12
  - `RETAIL_DRSTONE_20260501_175315` × 12
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 10
  - `RETAIL_ODA_20260426_202428` × 10
  - `RETAIL_AUGUSTO_20260426_201952` × 10
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 6
  - `RETAIL_HANNIBAL_20260426_201501` × 5
  - `RETAIL_DRSTONE4_20260501_193336` × 3
  - `RETAIL_DRSTONE3_20260501_181349` × 2
  - `RETAIL_DRSTONE_20260501_172522` × 2

Samples (first 32 bytes inner data):

```
#1: 0101000000f3f00100f3f00100
```
```
#2: 0101000000f6f00100f6f00100
```
```
#3: 0100000000a0050000a0050000
```

<!-- /catalog-evidence -->

## Structure

Vehicle/drone state broadcast. Verified 2026-05-10 against 63
samples from 17/17 retail captures. Fixed 14-byte body:

```
[0]      0x2e                   sub-opcode (constant)
[1]      0x01                   CONSTANT (63/63 samples)
[2]      action_byte             varies (0x00, 0x01, 0x02, 0x03)
[3]      0x00                   CONSTANT
[4..5]   entity_ref LE16         varies — observed values:
                                  0x0044, 0x0164, 0x009d, 0x010d,
                                  0x014e, 0x0d9b, ... mostly small
[6..9]   counter/timestamp LE32  varies — observed range 40K..130K
                                  (0x0000b8af, 0x0001cade, etc.)
[10..13] DUPLICATE of bytes 6..9  exact byte-for-byte copy of [6..9]
```

The DUPLICATED LE32 is the most striking feature — `[6..9]` and
`[10..13]` are identical in 63/63 samples observed. Likely a
"new value + old value" pair (state-change broadcast where both
match means "no change") OR a redundant-validation field
(client validates by comparing).

## Variants

Single 14-byte form across all 869 retail observations. NO size
variation. The action byte at [2] varies (0x00/0x01/0x02/0x03 —
4 distinct states) but body shape is always 14 B.

## Observed contexts

Top per-capture concentrations correlate with vehicle/drone-heavy
captures (RETAIL_VEHICLE_DRONE has the highest counts). Periodic
emission during vehicle operation.

The constant `01 00` prefix at body[2..3] (when action=0x00 byte
[2]=0) suggests this isn't a discriminator but a fixed framing byte.

## Open questions

- Why is the LE32 at [6..9] duplicated at [10..13]? Not yet
  pinned. Possibilities:
  1. New-vs-old state pair (currently unchanged in observed
     samples; would diverge during transitions)
  2. Redundant-validation tag (client checks both halves match)
  3. Vehicle-state packed differently across two slots
- The action_byte enum (0x00/0x01/0x02/0x03): vehicle on / off /
  brake / accelerate? Not observed correlating with any specific
  marker yet.
- Counter/timestamp at [6..9]: monotonic per-vehicle? Reset
  per-session? The values within a single capture/vehicle drift
  by tens/hundreds across emits — suggests a tick counter.

## Server-side handler

Ceres-J does not currently emit {@code 0x03/0x2e}. Vehicle state
broadcasts would require plumbing player-vehicle relationship
data into a periodic broadcast scheduler — out of scope for
current player-only emulation.

The decoder routes C→S 0x03/0x2e through `SubtagRouter` (drone-
control). For S→C, no handler exists; would need a new
`VehicleStateBroadcast` builder if implemented.

