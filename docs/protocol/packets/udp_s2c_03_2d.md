# `UDP S->C 0x03/0x2d` — Reliable/NPCData

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x03/0x2d`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **69755**
- Captures with this packet: **17/17**
- Size (bytes): min **5**, avg **51**, max **54**
- Top markers (within ±2s):
  - MOB_COMBAT_AND_DESPAWN × 232
  - DISMISS_VEHICLE × 215
  - MOB_DEAGGRO × 205
  - MOB_DEAD × 160
  - MOB_AGGRO2 × 125
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 50459
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 10707
  - `RETAIL_AUGUSTO_20260426_201952` × 1877
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 1119
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 1116
  - `RETAIL_DRSTONE_20260501_175315` × 773
  - `RETAIL_NORMAN_20260426_200458` × 666
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 647
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 522
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 406
  - `RETAIL_HANNIBAL_20260426_201501` × 377
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 265
  - `RETAIL_ODA_20260426_202428` × 215
  - `RETAIL_DRSTONE_20260501_172522` × 182
  - `RETAIL_DRSTONE4_20260501_193336` × 161
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 149
  - `RETAIL_DRSTONE3_20260501_181349` × 114

Samples (first 32 bytes inner data):

```
#1: 00f8090008bc34b542
```
```
#2: 53010000750000000070c3cdccffc20080bb44c047813f00000000b8fa2f012e
```
```
#3: 530100007140426a3a45ffffffff01b5282a000000aac20000aac2a8f92f0197
```

<!-- /catalog-evidence -->

## Structure

NPC/entity event multiplexer. Carries 18+ distinct sub-action types
within a single sub-tag. Verified 2026-05-09 against 52 samples
drawn from 6 retail captures (AUGUSTO, CASH_VENDOR, CREATION,
DRSTONE, DRSTONE3, DRSTONE4).

**Common 55-byte form** (40+ of 52 samples, post {@code 0x2d} sub-op):

```
[0]      0x2d                          sub-opcode (constant)
[1]      sub-action                    1 byte — selects the event class
                                        (see Variants section)
[2..3]   action-category LE16          0x0001 (51%) or 0x0003 (48%)
                                        — likely "category 1" vs "category 3"
                                        partition of NPC events
[4..7]   entity_id LE32                target NPC/entity (e.g. 0x00000075,
                                        0x6a424071) — varies
[8..11]  float #1                      e.g. world X coord, HP value, or
                                        velocity component (depends on
                                        sub-action)
[12..15] float #2                      world Y coord
[16..19] float #3                      world Z coord
[20..23] float #4                      orientation / rotation
[24..27] LE32 #5                       entity sub-id or state token
[28..31] LE32 #6                       second entity / event-specific
[32..51] 20 bytes                      sub-action-specific payload
                                        (anim ID, target ref, etc.)
[52..54] 3-byte trailer                sub-action-specific
```

**Short 9-byte form** (sub-action {@code 0x00}, rare):

```
[0]      0x2d
[1]      0x00       sub-action: simple state notification
[2..5]   4-byte data — entity ref + state byte
[6..8]   3-byte tail — float #1 LE32 truncated (e.g. HP delta)
```

### Sample decode (catalog #2, sub-action {@code 0x53}, post-2d body):

```
01 00 00       — action category 1
75 00 00 00    — entity_id = 117
00 70 c3 cd    — (incomplete decode, this layout assumes byte ordering;
cc ff c2 00      a more careful per-sub-action decode is needed)
80 bb 44 c0
47 81 3f 00
00 00 00 b8
fa 2f 01 2e
```

## Variants

**Update 2026-05-10**: re-extracted across 8 captures with no
per-pcap cap → 6,740 total samples. Two distinct sub-action
families emerge based on body[2..3] category:

### Category `0x0003` — high-frequency NPC tick updates

All 5 most-common sub-actions are in this family (~5,500
combined samples):

| sub-action | count | likely meaning                                |
|-----------:|------:|-----------------------------------------------|
| 0xdb       | 1912  | NPC anim / position tick (broadcast)          |
| 0xaa       | 1291  | NPC AI state tick                             |
| 0xe7       | 1210  | NPC despawn / combat-state                    |
| 0xee       | 1208  | NPC misc tick                                 |
| 0xf4       | 1119  | NPC update                                    |

Layout (post {@code 0x2d [sub-action] 0x03 0x00 0x00} = 5B
header):
```
[5..8]   entity_ref LE32         e.g. `75 d0 07 00` = 0x0007d075
[9..12]  4 bytes — varies        position/anim component?
[13..16] 4 bytes
[17..20] 4 bytes — varies (LE32 entity hash often visible:
                            `e0 f7 2f 01` recurs)
[21..end] sub-action-specific tail (~32B)
```

### Category `0x0001` — low-frequency lifecycle events

The 0x0001 family carries lifecycle events (NPC spawn, state
transitions, HP/stat updates, removal) — was originally
documented from a 52-sample subset. Sub-action distribution (top
15 from that subset):

| sub-action | count | likely meaning (inferred from markers)        |
|-----------:|------:|-----------------------------------------------|
| 0x02       |     8 | NPC spawn / character data sync               |
| 0xe7       |     6 | NPC despawn / death                           |
| 0x0f       |     4 | NPC state change                              |
| 0x26       |     4 | NPC removal / despawn-2                       |
| 0x2b       |     4 | NPC interact / dialog state                   |
| 0x63       |     4 | NPC HP/status update                          |
| 0xbd       |     3 | NPC AI state                                  |
| 0xdb       |     3 | NPC anim state                                |
| 0xfc       |     2 | NPC misc tag                                  |
| 0xf1       |     2 | (untyped)                                     |
| 0xf6       |     2 | (untyped)                                     |
| 0x10       |     2 | (untyped)                                     |
| 0x28       |     2 | (untyped)                                     |
| 0x00       |     1 | short 9B state-flag notification              |
| 0x24       |     1 | (untyped)                                     |

**Open: full sub-action enumeration.** The catalog's 69,755 obs
spread across many sub-actions; the 52 sample subset captures
~15-18 distinct values but the long tail probably has 30+ more.

The `0x11` sub-action specifically is investigated separately
(task #154 — client expects 36-byte format, retail emits less).

## Observed contexts

Top markers (within ±2s of emission):
- `MOB_COMBAT_AND_DESPAWN × 232` — NPCs entering/leaving combat,
  death-and-despawn sequences
- `DISMISS_VEHICLE × 215` — vehicle entity despawn
- `MOB_DEAGGRO × 205` — NPC dropping aggro on the player
- `MOB_DEAD × 160` — NPC HP→0 transition
- `MOB_AGGRO2 × 125` — NPC engaging combat

Confirms this packet is the primary NPC/entity-event broadcast.

Per-capture concentration: `RETAIL_VEHICLE_DRONE × 50,459` (72% of
all observations) suggests heavy emission during PvP/combat/vehicle
sessions. Quiet sessions (handshake-only) emit dozens.

## Open questions

- Per-sub-action body layout. Different sub-actions likely have
  different layouts past byte 8 — same total 55B size but different
  field semantics.
- Action-category byte at body[2..3]: `0x0001` vs `0x0003` —
  partition not yet pinned (combat vs idle? friendly vs hostile?).
- The 9B short variant (sub-action 0x00) — decoded structure?
- Sub-action `0x11` (task #154 — client expects 36B not 55B).

## Server-side handler

Ceres-J **emits** `0x03/0x2d` via:
- `server.gameserver.packets.server_udp.NpcDataBroadcast` (combat
  damage tag — sub-action specific)
- `server.gameserver.npc.NpcAggroBroadcast`
- `server.gameserver.npc.NpcDeathBroadcast`

Ceres-J **decodes** C→S `0x03/0x2d` via `SubtagRouter.dispatch(...,
0x03, 0x2d, -1, -1)` (drone-control plus future mob-state). Most
sub-actions are TODO — see `server.gameserver.npc.SubtagRouter`
registry.

