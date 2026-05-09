# `UDP S->C 0x20` — Movement

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x20`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **49847**
- Captures with this packet: **16/17**
- Size (bytes): min **5**, avg **16**, max **29**
- Top markers (within ±2s):
  - DRONE_INUSE × 352
  - OUTSIDE_AREAM5_TALKING_GEORDI_MISSION_COMPLETE × 255
  - OUTSIDE_AREAM5_STORE_GOGO × 254
  - DRONE_PICKUP × 244
  - OUTSIDE_AREAM5_TBUYING × 133
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 19251
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 17580
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 4673
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 4673
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 976
  - `RETAIL_NORMAN_20260426_200458` × 766
  - `RETAIL_AUGUSTO_20260426_201952` × 594
  - `RETAIL_ODA_20260426_202428` × 378
  - `RETAIL_DRSTONE_20260501_172522` × 372
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 368
  - `RETAIL_HANNIBAL_20260426_201501` × 76
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 69
  - `RETAIL_DRSTONE_20260501_175315` × 36
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 21
  - `RETAIL_DRSTONE3_20260501_181349` × 8
  - `RETAIL_DRSTONE4_20260501_193336` × 6

Samples (first 32 bytes inner data):

```
#1: 2003007f6ce13dc566e65fc33acd43444116a8c11e54a1430000000000
```
```
#2: 2002007f59ba17c566e64fc3bd46874386ccb4c0bf8d84430000000020
```
```
#3: 2002002020
```

<!-- /catalog-evidence -->

## Structure

Raw entity-position broadcast. Three size variants observed across
27 retail samples (AUGUSTO/CREATION/DRSTONE/DRSTONE3/DRSTONE4 +
catalog).

### 13-byte short variant (74% of observations)

Compact delta-position update for an existing entity:

```
[0]      0x20
[1..2]   entity_id LE16              292/308/957/etc. — per-NPC
[3]      action flag                 varies (0x03/0x24/0xbd/0xee/...)
[4]      0x80 / 0xd1 / etc.          orientation hint
[5..6]   Y_raw LE16                  signed delta or quantized coord
[7..8]   Z_raw LE16
[9..10]  X_raw LE16
[11..12] state/trailer 2B            usually 0x0000
```

### 17-byte LE32-float variant (22% of observations)

Full-precision position update — same shape as one of the
{@code 0x1b} multi-broadcast forms:

```
[0]      0x20
[1..2]   entity_id LE16              0x0001 (player's own ID dominant)
[3]      action flag                 0x27
[4..7]   X_coord float LE32          e.g. 8360.3 (verified clean)
[8..11]  Y_coord float LE32          e.g. 942.9
[12..15] Z_coord float LE32          e.g. 528.9
[16]     trailer                     0x60 (state/anim)
```

### 29-byte extended variant (rare; catalog #1, #2)

Adds a second LE32-float velocity/orientation field plus 4-byte
padding. Catalog sample:
```
20 03 00 7f 6c e1 3d c5 66 e6 5f c3 3a cd 43 44
41 16 a8 c1 1e 54 a1 43 00 00 00 00 00
```
- `[0]` 0x20
- `[1..2]` ent_id LE16 = 3
- `[3]` 0x7f (state)
- `[4..7]` X float = -3037.0
- `[8..11]` Y float = -223.9
- `[12..15]` Z float = +783.2
- `[16..19]` velocity X float = -21.0
- `[20..23]` velocity Y float = +322.7
- `[24..28]` 5-byte trailer (zero pad)

## Variants

| Size | Frequency | Use                                            |
|-----:|----------:|------------------------------------------------|
| 13 B |       20  | Compact delta NPC position (LE16 quantized)   |
| 17 B |        6  | Player full-precision position (LE32 floats)  |
| 29 B |        1  | Full position+velocity (catalog #1, #2)       |

The size discriminates: server picks LE16 vs LE32 based on whether
the entity is the local player (LE32 absolute) vs. a remote NPC
(LE16 delta from prior position).

## Observed contexts

Top markers (within ±2s of emission):
- `DRONE_INUSE × 352` — drone control / position broadcast
- `OUTSIDE_AREAM5_TALKING_GEORDI_MISSION_COMPLETE × 255` — NPC
  conversation in Aream5
- `OUTSIDE_AREAM5_STORE_GOGO × 254` — NPC vendor
- `DRONE_PICKUP × 244` — drone retrieval
- `OUTSIDE_AREAM5_TBUYING × 133` — buying interaction

Concentration in vehicle/drone-heavy captures (RETAIL_VEHICLE_DRONE
× 19,251) suggests this is the main NPC/drone position-update
channel during active play. 16/17 captures (only one missing) so
near-universal.

Per-capture entity_id stability: same entity_ids repeat dozens of
times in a single capture (e.g. CREATION's `0x0103` × 5 in 5
consecutive samples) — confirming each value is a specific
zone-resident entity.

## Open questions

- 13-byte variant byte[3] flag space — observed 0x03, 0x24, 0xbd,
  0xee, 0xf1, 0xf4, 0xdb, 0x34 (8+ values across samples). Per-flag
  semantics not yet pinned (anim ID? action class?).
- 13-byte byte[4] "orientation hint" — heavy bias toward 0x80 / 0x7e
  values (likely encoded angle).
- LE16 coord encoding: signed delta vs quantized absolute? The
  `+ 32000` offset convention used by 0x1b doesn't cleanly fit all
  samples; some entities have raw values >40000 which would map to
  +8000 absolute (plausible) but others to >65000 (overflow).
- 29-byte variant frequency: only 1 catalog sample seen — is it
  rare or just under-sampled?

## Server-side handler

Ceres-J does not currently emit raw {@code 0x20} broadcasts. The
production code path emits {@code 0x1b} (19 B unreliable position
broadcast — see {@link
server.gameserver.packets.server_udp.ObjectPositionBroadcast}) and
reliable {@code 0x03/0x1b} {@link
server.gameserver.packets.server_udp.PlayerPositionUpdate}.

The C→S direction has handler {@link
server.gameserver.packets.client_udp.Movement} (the client's own
position update). The S→C raw {@code 0x20} appears to be the
server's broadcast variant for FAST per-tick entity updates,
distinct from the slower {@code 0x1b} watchdog input. Implementing
S→C 0x20 for NPCs would require a per-NPC broadcast scheduler with
LE16 coord quantization.

