# `UDP S->C 0x1f` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x1f`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **14525**
- Captures with this packet: **17/17**
- Size (bytes): min **8**, avg **10**, max **43**
- Top markers (within ±2s):
  - POKE_START × 26
  - FIRE_PVP × 19
  - INVITE_TEAM × 19
  - ACCEPT_INVITE_TEAM × 19
  - POKE_START_AFTER_BUYING_GEL × 18
- Per-capture counts:
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 3882
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 3878
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 2944
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 1630
  - `RETAIL_ODA_20260426_202428` × 428
  - `RETAIL_NORMAN_20260426_200458` × 354
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 343
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 304
  - `RETAIL_DRSTONE_20260501_175315` × 241
  - `RETAIL_HANNIBAL_20260426_201501` × 191
  - `RETAIL_AUGUSTO_20260426_201952` × 119
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 76
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 55
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 34
  - `RETAIL_DRSTONE_20260501_172522` × 24
  - `RETAIL_DRSTONE4_20260501_193336` × 15
  - `RETAIL_DRSTONE3_20260501_181349` × 7

Samples (first 32 bytes inner data):

```
#1: 1f0400308700ae004d0082018201
```
```
#2: 1f0400308700ae004d0082018201
```
```
#3: 1f030030ba02ba02
```

<!-- /catalog-evidence -->

## Structure

Raw {@code 0x1f} S→C — counterpart to the wrapped {@code 0x03/0x1f}
GamePackets multiplexer, but the RAW (unreliable) variant. Verified
2026-05-10 against 30 samples from 5 retail captures.

Common 14-byte form (28/30 samples, tag = 0x30):

```
[0]      0x1f                    sub-opcode
[1..2]   0x0001 LE16             CONSTANT (matches the 0x03/0x1f
                                  wrapped variant's prefix)
[3]      0x00                    CONSTANT
[4]      tag byte                0x30 dominant (28/30); 0x25 in
                                  CREATION; 0x08/0x2b in 0x56-form
[5..6]   LE16 #1                  varies (player stat / NPC count)
[7..8]   LE16 #2                  varies
[9..10]  LE16 #3                  varies
[11..12] LE16 #4                  varies
[13]     trailer byte             usually 0x01
```

Sample bodies for tag 0x30:
- AUGUSTO: `1f 01 00 30  70 00 90 00 40 00 41 01 41 01` (constant
  per-session — 7+ identical samples)
- DRSTONE3: `1f 01 00 30  73 00 94 00 42 00 4a 01 4a 01`
- CREATION: varies (`54 00 64 00 30 00 e8 00 26 01` →
  `58 00 68 00 34 00 f5 00 26 01` over 5 emits — slowly
  incrementing)

The values look like cumulative NPC/zone-state counters (4 small
LE16 values that increment slowly + 1 fixed byte). Possibly a
zone-population update.

### Variants

Tag distribution across 30 samples:

| tag  | count | size | meaning                                |
|-----:|------:|-----:|----------------------------------------|
| 0x30 |    28 | 14 B | zone-state counter (dominant)          |
| 0x25 |     1 | 25 B | extended counter — 4× LE32 + 1× LE32  |
|      |       |      | `92 07 00 00` × 4 + `42 03 00 00`     |
| 0x56 |     2 |  8 B | sub-action 0x08 / 0x2b — `1f [sub] 01 56 00 00 00 00` |

## Observed contexts

Top markers per the catalog: catalog evidence section above lists
captures but no specific 0x1f-related markers — likely fires
continuously during gameplay as a state-broadcast tick.

The tag 0x30 dominance (~93% of samples) suggests a single
periodic state-update payload; tag 0x25 / 0x56 are rarer events.

## Open questions

- Decode the 4× LE16 fields at body[5..12] for tag 0x30. Slowly-
  incrementing values across consecutive samples (CREATION) suggest
  cumulative counters (NPCs spawned / killed / events).
- Tag 0x25 25-byte form with 4× LE32 = 1938 repeated — could be
  per-faction-NPC counts or per-class kill counters.
- Tag 0x56 8-byte form: 1f [sub] 01 56 00 00 00 00 — the literal
  `01 56` after sub looks like an event ID.

## Server-side handler

Ceres-J does not currently emit raw {@code 0x1f}. The wrapped
{@code 0x03/0x1f} GamePackets channel IS handled (cash carrier,
inventory events, combat tags) — see `udp_c2s_03_1f.md` /
`udp_s2c_03_1f.md`. The raw variant is presumably for fire-and-
forget zone-state ticks; the modern client appears to tolerate
its absence (Ceres-J doesn't emit it, no overlay/AI issues).

