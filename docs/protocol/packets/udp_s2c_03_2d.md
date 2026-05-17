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

**Byte-pinned common NPC-tick form — verified 2026-05-16**
(task #167; 6 retail captures, the 5 highest-count category-0x0003
sub-actions f4/bd/db/ee/63, ≥19 samples each, ALL exactly 55 B):

```
[0]      0x2d                          sub-opcode
[1]      sub-action                    f4 / bd / db / ee / 63 …
[2..3]   03 00                         category LE16 = 0x0003
[4]      00                            flag/pad
[5..10]  71 20 vv vv 9b 45             entity descriptor (6 B;
                                        bytes 7..8 vary per NPC,
                                        71 20 / 9b 45 recur)
[11..14] ff ff ff ff                   sentinel (-1)
[15..18] float                         value/pos #1
[19..22] float                         pos #2 (Y)
[23..26] float                         pos #3
[27..30] float                         pos #4 (orientation)
[31..34] float                         == [19..22] (pos #2 echoed)
[35..46] 43 00 00 80 06 00 00 00 01 00 00 00   INVARIANT block
                                        (byte-identical across ALL
                                        five sub-actions)
[47..54] 81 ca 09 00 + 4 B             sub-action-specific tail
```

**Actionable gap:** retail `0x03/0x2d` NPC ticks are a FIXED
55-byte record. Ceres `NpcDataBroadcast` emits a 9-byte stub
(`2d <mapId LE2> 00 08 00 00 00 00`) — matches no retail
sub-action. This is why the client logs
`LSTPLAYER : Update Message corrupted` and
`@WWORLDMGR : Unable to Spawn WA` (its world-actor parser
expects the 55-byte layout). Fixing NpcDataBroadcast to emit
the verified 55-byte form (entity descriptor + 5 floats +
invariant block + tail) is the concrete next step to make NPCs
render. The 0x2d sub-action 0x11 (the LSTPLAYER virtual_24
case-17 culprit per memory lstplayer_error_misattribution) does
NOT appear in any of the 8 retail strace captures — it is a
rare/player-specific variant; the common-tick fix above is the
higher-leverage target.

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

**Update 2026-05-17 — task #167. The body layout is NOT
per-sub-action.** Full sweep of all 17 retail captures
(69,755 observations; tooling `tools/extract_2d_layouts.py`,
`tools/analyze_2d_cat1.py`, `tools/verify_2d_record71.py`)
disproves the long-standing "440 distinct per-sub-action layouts"
hypothesis. Grouping by `(category[2..3], sub-action[1])` yields
440 groups, but **~all of the long tail is a single capture
(`RETAIL_VEHICLE_DRONE`, 72% of obs) varying one entity-keyed
byte** — not 440 wire formats. The real structure:

- **Length** selects the gross form: 6 / 10 / 55 bytes.
- For the 55-byte form, **`body[5]` (the record-discriminator
  byte) selects the field grid** — `0x71` or `0x75`. These are
  the same bytes `MobState.COMBAT` (0x71) / `MobState.IDLE`
  (0x75) use; the record discriminator *is* the mob state.
- **`body[1]` (sub-action) is an event tag** (which mob event
  fired) and **`body[2..3]` (category LE16) is a routing class**
  (0x0001 lifecycle / 0x0003 high-frequency tick / 0x0002).
  Neither changes the grid past `body[5]`.

Decoder: `server.gameserver.npc.Npc2dRecordDecoder`
(retail-pinned, evidence-cited). Legacy fixed-grid
`MobDataDecoder` retained for back-compat.

### Form 1 — 6-byte PING  `2d [sub] [cat LE16] 00 06`

Minimal event ping, no body. Seen for many distinct sub-actions
under `cat=0x0001` across **9 captures** (e.g. sub 0x01 ×36,
0x02 ×38, 0x03 ×33, 0x04 ×39, 0x05 ×33, 0x07 ×31, 0x22 ×35).
The trailing `0x06` is a constant block-length marker. Proven —
`Npc2dRecordDecoderTest.decodesPingForm_*`.

### Form 2 — 10-byte SHORT  `2d [sub] 03 00 00 0a 00 [tok LE32]`

Short event with one trailing token. `cat=0x0003`. Exactly two
tokens observed corpus-wide:

| body                       | token (LE32) | count | captures |
|----------------------------|--------------|------:|---------:|
| `2d fb 03 00 00 0a 00 00 00 00` | `0x00000000` | 147 | many |
| `2d d1 03 00 00 0a 00 60 1f 45` | `0x451f6000` |  91 | many |

(`fa/fc/f5/f7/f8/ca` sub-actions also appear with the same two
tokens.) The `0a 00` after the 5-byte header is a fixed
sub-block marker. Proven — `decodesShortForm_*`.

### Form 3 — 55-byte RECORD

```
[0]      0x2d
[1]      sub-action            event tag (NOT a layout selector)
[2..3]   category LE16         routing class: 0x0001 / 0x0002 / 0x0003
[4]      0x00
[5]      record discriminator  0x71 or 0x75  (== MobState byte)
[6]      route / sub-class     0x20 = position-bearing (0x71)
```

#### 3a. `body[5]=0x71` — ENTITY world-state record

**26,755 retail observations**, multiple captures. Byte-pinned
(`verify_2d_record71.py`, 16,757-sample clean subset =
sentinel+echo):

```
[7..10]  float posX            (route 0x20; e.g. 56 cc c2 45 = 6233.542)
[11..14] 0xFFFFFFFF             "no-target" sentinel — 97.7% of all obs
[15..18] float f1
[19..22] float f2
[23..26] float f3
[27..30] float f4
[31..34] float f5  ==  f2       posY echo — STRUCTURAL PROOF
                                (100% of clean subset, 62.7% overall)
[35]     float-block high byte  0x45 / 0xc5
[36..47] INVARIANT 12 B = 43 00 00 80 06 00 00 00 01 00 00 00
                                byte-identical across every capture
[48..51] tail marker = 81 ca 09 00   fixed offset (16,681 obs)
[52..54] 3-byte per-record tail
```

The doc's earlier byte-pin (offsets [35..46] block / [47..54]
tail) was off by one slot; the figures above are the corrected,
re-verified offsets. Sub-actions `0xee`, `0xa0`, `0x54`, `0xdb`,
`0xe7`, `0xf4`, `0xbd`, `0x63`, … all share this identical grid —
they differ only in the [1] event tag and the float values.
Proven — `decodesEntityRecord_ee/a0/54_fromRetail`.

#### 3b. `body[5]=0x75` — LOCAL / relative-state record

**36,977 retail observations**, multiple captures. Tail signature
`f8 f8 2f 01 89 01 1e 00` @~[44..51] and marker `2e ce 2c 00`
@~[32]. Body [7..54] is a **chain of LE32 entity handles** (each
ends `2f 01` / `…0c`), NOT world floats. The per-slot meanings
are **insufficient evidence** to pin — surfaced verbatim as
`Decoded.localBody` so producers can replay them. Proven (shape +
discriminator) — `decodesLocalRecord_fc/0a_cat1_fromRetail`.

### Category semantics (pinned)

`cat=0x0001` carries the lifecycle family — sub-actions form a
clean contiguous enum `0x01..0x14`, each present in 6–11 captures
(genuine event classes). `cat=0x0003` is the high-frequency
position/AI tick (dominated by `RETAIL_VEHICLE_DRONE`).
`cat=0x0002` is a smaller variant of the 0x75 record. The
0x0001-vs-0x0003 split is **routing class, not combat-vs-idle**:
both categories appear with both `body[5]` discriminators.

### Insufficient evidence (not guessed)

- Per-slot meaning of the 0x75 LOCAL-record handle chain
  (handles identifiable, semantics not).
- `body[6]` route values other than `0x20` for 0x71 records (the
  non-position-bearing 0x71 variants — float fields do not parse
  as world coords; held opaque).
- Sub-action `0x11` client-side 36-byte expectation (task #154 —
  out of #167 scope; the wire form here is the standard 55B
  record).
- Whether `body[1]` event tags map to named events
  (spawn/despawn/HP) — markers correlate loosely but no
  byte-level proof, left undocumented rather than guessed.

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

