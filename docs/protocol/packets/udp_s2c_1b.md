# `UDP S->C 0x1b` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x1b`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **143484**
- Captures with this packet: **17/17**
- Size (bytes): min **19**, avg **19**, max **49**
- Top markers (within ±2s):
  - KILL_MOB2 × 201
  - BASELINE_HUD × 198
  - KILL_MOB × 195
  - MOB_COMBAT_AND_DESPAWN × 148
  - DISMISS_VEHICLE × 146
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 58099
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 22834
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 16754
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 16539
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 7120
  - `RETAIL_AUGUSTO_20260426_201952` × 5556
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 3169
  - `RETAIL_NORMAN_20260426_200458` × 2648
  - `RETAIL_ODA_20260426_202428` × 2037
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 1960
  - `RETAIL_DRSTONE_20260501_175315` × 1867
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 1580
  - `RETAIL_DRSTONE4_20260501_193336` × 897
  - `RETAIL_HANNIBAL_20260426_201501` × 824
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 824
  - `RETAIL_DRSTONE_20260501_172522` × 390
  - `RETAIL_DRSTONE3_20260501_181349` × 386

Samples (first 32 bytes inner data):

```
#1: 1b020000000359ba17c566e64fc3bd46874386ccb4c0bf8d8443000000002000
```
```
#2: 1b490100001f4b7750807d81400f0100004011
```
```
#3: 1b3e0100001f5f77307fbe8040d4050000ea11
```

<!-- /catalog-evidence -->

## Structure

19-byte fixed-length raw position broadcast. Verified 2026-05-09
against 30 samples drawn from HANNIBAL, NORMAN, AUGUSTO retail pcaps
(see `tools/extract-1b-broadcasts.py`).

```
offset bytes  meaning                              constancy
[ 0]   1B    0x1b                                  CONSTANT (sub-opcode)
[ 1]   1B    object_id LE16 lo                     varies
[ 2]   1B    object_id LE16 hi (0x01..0x03 typ.)   varies
[ 3]   1B    0x00                                  CONSTANT
[ 4]   1B    0x00                                  CONSTANT
[ 5]   1B    0x1f                                  CONSTANT (inner opcode)
[ 6..7] 2B   Y_raw LE16 (= world_y + 32000)         varies
[ 8..9] 2B   Z_raw LE16 (= world_z + 32000)         varies
[10..11] 2B  X_raw LE16 (= world_x + 32000)         varies
[12]   1B    orientation byte (0x40 dominant —      varies
              also 0x02, 0x20, 0x22, 0x42)
[13..14] 2B  entity_class_id LE16                   varies (per
                                                     NPC class)
[15]   1B    0x00                                  CONSTANT
[16]   1B    0x00                                  CONSTANT
[17]   1B    state hash lo                          varies
[18]   1B    state hash hi (mostly 0x11/0x0f, also  varies
              0x12/0x20/0x21/0x22)
```

Cross-pcap evidence: NORMAN's 0x010e broadcasts repeatedly emit
`b9 88 00 00` for [13..16] → `entity_class_id = 0x88b9`. AUGUSTO has
multiple NPCs sharing `entity_class_id = 0x7176` (NPCs 0x0123 + 0x0109
both emit it). Bytes [15..16] are always 0x00 across all 30 samples.

## Variants

Single 19B variant observed in 30/30 samples across 3 retail captures.
The catalog records min/avg/max **19/19/49** — the 49-byte outlier
appears once in HANNIBAL (`1b020000000359ba17c566e64fc3bd46874386ccb4c0bf8d8443000000002000`)
and starts with a different `[3..5]` shape (`00 00 03 59 ba`). Likely
a different sub-variant; not yet decoded.

## Observed contexts

Highest-frequency S→C packet in retail captures (143,484 obs across
17/17 captures). Top markers: KILL_MOB2, BASELINE_HUD, KILL_MOB,
MOB_COMBAT_AND_DESPAWN, DISMISS_VEHICLE — fires throughout active
play whenever NPCs or other players move within the local zone.

## Open questions

- Bytes [17..18] semantic: state/animation enum? Action ID? Sequence
  per-entity counter? Unknown. Mostly stable per entity per session
  but varies between sessions.
- 49-byte variant decoding (HANNIBAL outlier) — different leading
  shape, possibly a chained multi-broadcast packet.

## Task #178 — entity_class_id resolved (2026-05-17)

Decoded AUGUSTO / NORMAN / DRSTONE4 retail pcaps
(`ceres-j/strace/*.pcap`, present in the tree — the earlier claim
they were missing was wrong) with `tools/pcap-decode.py`, pairing
every entity's `0x1b` against its `0x03/0x28`.

Findings (27 NPCs in AUGUSTO alone):

- `[13..14]` is **100% stable per entity** for the whole session
  (ent 0x0124 = 1739 ×103; the NCPD-guard group 0x0125–0x012f all
  = 29002; ent 0x0102 = 366 ×84) and **never `0x0000`** for a
  mobile NPC.
- It is a **different id space** from `0x28[11..12]` class
  (ent 0x0124: `0x28` cls = 335 but `0x1b` = 1739).
- The retail values (29002, 29046, 1739, 366, 308, 429, …) appear
  in **no client def file** — every decompressed `defs/pak_*.def`
  was grepped; `npc.def` maxes at id 20008 and 29002/29046 are
  absent everywhere. They are **server-assigned runtime
  spawn/world-actor handles**, the same category as the
  `0x28[7..10]` instance handle — there is no static
  type→entity_class_id table to look up and a fresh capture would
  not produce one (the value is a heap handle, not class metadata).
- **Ordering proves it is load-bearing:** the raw `0x1b` for an
  entity arrives **before** its reliable `0x03/0x28` WorldInfo
  (AUGUSTO 0x0124: `0x1b` @ 277.953 s, `0x28` @ 278.433 s). The
  client creates the world actor from this id on first sight, so
  emitting `0x0000` (the pre-fix behaviour) is exactly why NPCs
  never rendered.

Other per-entity observations: `[12]` orientation and `[18]` are
also per-entity stable (e.g. 0x0124: orient `0x20`, `[18]=0x1a`);
`[17]` varies per packet (runtime anim/state) with `0xff` common.

## Server-side handler

`server.gameserver.packets.server_udp.ObjectPositionBroadcast`
(and the `0x1b` datagram in `ZoneStateCompoundPacket`) now emit
`[13..14]` = the NPC's real **npc.def type id** via
`NPC.getEntityClassId()` — genuine client data, stable, non-zero,
in the npc.def id space. Bulk `world_npcs` rows whose
`npc_type_id` is 0 (17 of 3,571) fall back to a deterministic
(zone, mapID) hash that is still unique and stable per entity (no
two NPCs alias). `[17..18]` remain `0x11 0x11` (`[18]=0x11` is the
retail-dominant value; `[17]` is per-packet runtime state we do
not fabricate). Constant bytes [0,3,4,5,15,16] match retail
exactly.

