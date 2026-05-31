# `0x03/0x1f` GamePackets — Function Reference (act_tags + `0x25` sub-tags)

_Consolidated reference — synthesized from `SUBTAGS.md`, `OPCODE_STRUCTURE.md`,
`CLIENT_LUA_BRIDGE.md`, the per-packet stubs, the flow docs, and the user's
memory notes (the `c2s_1f_*` / `s2c_1f_*` decode iterations of 2026-05-29/30).
NOT re-derived from captures — this synthesizes what is already known and marks
genuine unknowns honestly. Last consolidated 2026-05-31._

`UDP * 0x03/0x1f` is the protocol's **general-purpose state-event channel** — by
far the highest-traffic reliable sub-opcode (C→S 188,947 obs / S→C 36,537 obs
across 17 retail captures). It is a multiplexer: the byte after `0x1f` is the
**act_tag**, and for the transactional family (`0x25`) a further **sub-tag**
routes the state update.

## Envelope

```
[0]      0x03          reliable outer
[1..2]   seq LE16      reliable sequence number
[3]      0x1f          GamePackets sub-op (also an EVENT-CLASS byte — see note)
[4..5]   mapId LE16    sender's current zone id (1=Plaza, 5=Viarosso, …)
[6]      act_tag       ACTION DISCRIMINATOR — table (A)
[7..N]   body          variable per act_tag
```

For the `0x25` transactional family the body continues:

```
[6]      0x25          act_tag = transactional state update
[7]      sub_tag       routes the state update — table (B)
[8..N]   tail          variable per sub_tag
   ... and the txn-wrapped form (sub_tag 0x13):
[7]      0x13          txn wrapper
[8..9]   txn LE16
[10]     inner_tag     (e.g. 0x04 cash) — see table (B) note
[11..N]  data
```

> **mapId correction (2026-05-29 iter 45, `correction_1f_is_event_class.md`).**
> The `01 00` long documented as a "constant prefix" at offsets 4..5 is actually
> the **sender's mapId LE16**, dominantly `0x0001` only because ~99% of retail
> captures were taken in Plaza. Verified live: a Viarosso chat showed `1f 05 00 1b …`
> (mapId=5). Most legacy decode notes write the body offsets as if the prefix were
> fixed `01 00`; treat those two bytes as mapId. Ceres-J emitters likely hardcode
> mapId=1 and must be audited for cross-zone correctness.

---

## (A) `0x1f` act_tags

Direction key: C→S, S→C, or both (direction-overloaded — same tag, different
meaning per direction). Sorted by tag byte. "function" is one line; full byte
maps live in the linked memory note / flow / stub.

| tag | dir | function | byte shape | source |
|---|---|---|---|---|
| `0x00` | C→S | **NoOpMarker** — empty frame/tick boundary, fires unprompted ~5-30s | `…00` (no body) | `c2s_1f_act_tag_wave` |
| `0x01` | C→S | **WeaponFire** — foot-soldier shot: weapon_id + locked target + shot telemetry | `01 [weapon_id:LE16][target_id:LE16][pad:LE16][shot_a:LE32][shot_b:LE32][shot_coord:f32]` (23B) | `c2s_1f_01_weapon_fire`, flows/combat_kill_npc.md |
| `0x02` | C→S | **EventMarker** — sparse empty event boundary (combat enter/exit, vehicle mount, land-impact) | `…02` (no body) | `c2s_1f_02_1f_decoded`, `cash_and_falldamage_subops` (land-impact) |
| `0x06` | C→S | **AdminCommandRequest** — native-client built-in GM cmds (e.g. `/gm_noclip`) | `06 …` varies | stub `udp_c2s_03_1f.md` |
| `0x17` | both | **UseObject / UseItem** (C→S) `[object_id:LE32]`; (S→C) **target/select broadcast** `[entity_id:LE32]` (Ghidra mis-labelled S→C as "sit") | `17 [id:LE32]` (11B) | `c2s_1f_interaction_ops`, `s2c_1f_17_entity_select`, flows/interactions.md |
| `0x18` | S→C | **NamedEntity** — entity + script-name (ASCII null-term, e.g. `BUSYNPC`, `Pathfinders_C08`); drives SCRIPTEDPLAYER spawn | `18 [entity_id:LE32][type_hash:LE32][flag:LE32][name:ASCII\0]` (var) | `s2c_1f_subtag_wave2`, npc_spawn_status |
| `0x19` | C→S | **EntityRequest** — interaction request for a specific entity (mount/talk?) | `19 [entity_id:LE16][pad:LE16]` (11B) | `c2s_1f_act_tag_wave` |
| `0x1a` | both | **Dialog** — C→S advance-dialog (const `01`); S→C next-node `[node_id:LE16][00]`, rare 14B float-param variant. NPC ID implicit from prior 0x17 | C→S `1a 01` (8B); S→C `1a [node_id:LE16] 00` (10B) | `dialog_1f_1a_byte_decode`, `c2s_1f_interaction_ops`, flows/npc_dialogue.md |
| `0x1b` | C→S | **LocalChat** — ASCII chat text (NUL-terminated on retail) | `1b [ASCII…\0]` (var) | `correction_1f_is_event_class`, stub |
| `0x1e` | C→S | **InventoryMove / ItemOp** — F2 drag/drop; op selector 0x02 move / 0x03 use / 0x05 drop | `1e [src_c:1][src_pos:LE16][dst_c:1][dst_pos:LE16][flags:LE16]` (11B; rarer 13B) | stub `udp_c2s_03_1f.md`, `c2s_1f_interaction_ops`, flows/inventory_equip.md, vendor_buy.md |
| `0x1f` | C→S | **EquipHolster** — toolbelt equip/holster, slot 0=holster, 1-3=quickbelt. (Note: stub says bitmask 0x01/02/04/08; `equip_quickbelt_wire` corrects to **direct slot index** — verify with slot ≥4) | `1f [slot:u8]` (8B) | `equip_quickbelt_wire`, `c2s_1f_02_1f_decoded`, stub |
| `0x20` | C→S | **VehicleControl** — vehicle aim/fire telemetry: own vehicle id + aim floats + target | `20 [own_id:LE16][6B flags][f32 x][f32 y][…][target_id:LE32]` (46B) | `c2s_1f_act_tag_wave` |
| `0x21` | S→C | **PostureBroadcast** (sit) / **VehicleEntityBroadcast** — sit-broadcast `[flag][interaction_type:LE32]` (type 0x2c=SIT); also per-tick vehicle-owner ping `[entity_id:LE32][00]` | `21 00 [type:LE32]` (sit, ~9B) / `21 [entity_id:LE32] 00` (vehicle, 12B) | `sit_on_chair_wire`, `s2c_1f_subtag_wave` |
| `0x22` | both | C→S **InputBurst / ExitSeatRequest(Stand)** (empty body, per-tick while held / stand cmd); S→C **opaque 9B** bytes (random — possibly encrypted/anti-cheat token, unconfirmed) | C→S `…22` (no body); S→C `22 [9B opaque]` | `c2s_1f_act_tag_wave`, `sit_on_chair_wire`, `s2c_1f_subtag_wave2` |
| `0x25` | both | **State-update transaction** — routes by sub_tag → table (B). Dominant S→C tag (state-ack channel) | `25 [sub_tag] [tail]` (var) | `s2c_1f_subtag_wave`, `cash_and_falldamage_subops`, table (B) |
| `0x26` | S→C | **Vendor/Loot listing** — variable-size listing payload (up to 821B) | `26 …` (10-821B) | SUBTAGS.md, flows/vendor_buy.md, level_up.md |
| `0x27` | C→S | **CloseDialog** — release vendor/CityCom/dialog lock | `…27` (no body) | `c2s_1f_interaction_ops`, stub |
| `0x2a` | S→C | **MissionGrant** — ASCII mission ID (e.g. `mc5_lorcan`) in body | `2a [ASCII mission_id…]` (58-64B) | CLIENT_LUA_BRIDGE.md, flows/missions.md |
| `0x2b` | C→S | **CityCom DCB RPC** — apartment/terminal RPC; ASCII method names (`DCBSetup`, `VehicleListing`). _Note: in the catalog 0x2b is a sibling `0x03/0x2b` reliable sub-op, not technically under `0x1f` — listed here as the OTHER RPC channel for completeness_ | `2b [sub_tag][24B sig][type:LE16][len:LE16][pad][ASCII method][args]` | `c2s_2b_citycom_dcb`, CLIENT_LUA_BRIDGE.md, flows/apartment_mail.md |
| `0x2c` | S→C | **DamageEvent** — 12B id-only (entity died) / 17B with f32 damage + hit-count + type | `2c 09 [entity_id:LE32]` (12B) / `2c 01 [damage:f32][count:LE32][mod:u8]` (17B) | `s2c_1f_subtag_wave2`, current_pool_damage_heal_path |
| `0x2f` | C→S | **GR map-pick** — Genrep/map-overlay pick sentinel | `2f ff ff ff ff ff ff ff ff` (8B all-FF) | `genrep_teleport_full_sequence`, `iter45_misc_subtags` |
| `0x30` | S→C | **PlayerActionBroadcast** — generic observable-action broadcast (weapon-fire sub=0x0075, equip, posture) | `30 [entity_id:LE32][sub_type:LE16][target_id:LE16][class_id:LE16]` (14B inner) | `s2c_1f_30_action_broadcast` |
| `0x32` | C→S | **unknown** — `32 02 [u32]` (6 obs); payload looks like an entity-target id (`02 55 86 01 00`) but unconfirmed | `32 02 [u32]` (8B) | stub, SUBTAGS.md |
| `0x38` | both | **unknown** — S→C 10B body `01 00 [id/counter:LE32][5B 0][01]`; speculative periodic entity ping (1-3 obs) | `38 [10B]` | `iter45_misc_subtags`, SUBTAGS.md |
| `0x3b` | C→S | **SubtagRouter → cross-channel chat** — whisper/team/clan/buddy | `3b …` varies | stub `udp_c2s_03_1f.md` |
| `0x3d` | C→S | **FramePoll** — DOMINANT client packet (~50/sec, ~70% of C→S 0x1f traffic); per-frame presence/implicit-ack ping. 15B session-init variant with `ff ff ff ff` sentinel | `3d 11 00 00 00 00` (12B) / `3d 32 00 00 00 ff ff ff ff` (15B init) | `c2s_1f_3d_frame_poll`, `cash_and_falldamage_subops` |
| `0x3e` | both | **LivenessFlag / HandshakeStep** — sparse session-liveness; first body byte cycles 1→2→3 across a C→S/S→C handshake | `3e [step:u8] 00 00 00 01` (12B, const) | `c2s_1f_act_tag_wave`, `s2c_1f_subtag_wave2` |
| `0x4a` | S→C | **VehicleExitMarker** — empty vehicle exit/unmount confirm | `…4a` (no body, 7B) | `s2c_1f_subtag_wave2` |
| `0x4c` | both | **direction-overloaded.** C→S = **ChangedChannels** chat-listen bitmask heartbeat (~10s, const body); S→C = **item-name notification** (loot/material name string). _NOT_ a GR-station-select (that was a mis-ID; GR-pick is 0x2f) | C→S `4c [channels:LE16] 03 00` (11B); S→C `4c [LE32=9][name_len:u8][01][ASCII name][00 00]` (var) | `sub_tag_0x4c_dual_use`, `iter45_misc_subtags`, stub |
| `0x4d` | S→C | **unknown** — 7B, `0x02` next byte, 1 obs | `4d 02 …` | SUBTAGS.md |
| `0x4e` | C→S | **unknown** — 7B, `0x02` next byte, 1 obs | `4e 02 …` | SUBTAGS.md |

### Act_tags still genuinely unknown after consolidation

`0x16` (1 obs, S→C, `0xe6` next), `0x31` (2 obs, S→C, `0x01`×2), `0x35` (1 obs,
S→C), `0x36` (2 obs, mixed), `0x39` (1 obs vehicle pcap, uncatalogued), plus the
partially-guessed `0x22`-S→C / `0x32` / `0x38` rows above. These have too few
observations (1-2) and no decode — listed for completeness, function unknown.

> **C→S `0x04` (cash/skill-spend):** referenced in older work
> (`cash_and_falldamage_subops`, level_up.md) as a top-level act_tag, but the
> verified cash carrier path is the `0x25/0x13` txn wrapper with inner tag
> `0x04` (table B), **not** a standalone `0x1f/0x04`. Treat `0x04` as a `0x25`
> inner tag, not a primary act_tag.

---

## (B) `0x25` sub-tags (`01 00 25 [sub_tag] …`)

`0x25` is the transactional state-update family — the dominant S→C tag. Two
forms: direct (`25 [sub_tag] [tail]`) and txn-wrapped (`25 13 [txn LE2][inner_tag][data]`).
Sorted by sub_tag byte.

| sub_tag | dir | function | byte shape | source |
|---|---|---|---|---|
| `0x04` | both | **Cash update** (S→C, wallet LE32 drives HUD CASH) / **skill-point spend** (C→S, 8B). Carried directly or inside the `0x13` txn wrapper | `25 13 [txn:LE2] 04 [cash:LE32]` (11B inner) / C→S `25 04 …` (8B) | `cash_and_falldamage_subops`, `charinfo_field_positions`, flows/level_up.md, vendor_buy.md, combat_kill_npc.md |
| `0x06` | S→C | **NpcAiState** — NPC action/animation-state broadcast (entity_id + float + chain id) | `25 06 01 05 [count:LE16][entity_id:LE16] 01 [type:u8][f32]…` (31B) | `s2c_1f_subtag_wave` |
| `0x07` | S→C | **BuffApplication** — entity gained effect: strength f32, duration f32, effect_id | `25 07 [buff_type:LE16] 01 [strength:f32][entity_id:LE16] 02 [duration:f32][effect_id:LE16]` (24B) | `s2c_1f_25_inner_extension` |
| `0x0b` | S→C | **Counter** — `[04 00][value:LE16][4B pad]`; sparse, meaning unconfirmed. (Older cash note also saw `0x0b` as a 2-byte event marker inside the txn wrapper) | `25 0b 04 00 [value:LE16][4B 0]` (15B) | `s2c_1f_25_inner_extension`, `cash_and_falldamage_subops` |
| `0x13` | S→C | **Txn wrapper** — transactional envelope carrying one or more `[inner_tag][data]` events (cash 0x04, markers 0x0b/0x0e/…); also the **Zoning1 commit** (`txn_id` LE32, 0x020e high-16 invariant) | `25 13 [txn:LE2][inner_tag][data]+` | `cash_and_falldamage_subops`, `zoning1_body_pinned`, `ceresj_zoning1_missing_startack` |
| `0x14` | both | **InsideF2InvMove / buy-ack / counter** — C→S F2 inner inventory move and vendor buy-ack; also a counter pattern `[counter:LE16][2B]` | C→S `25 14 [item-id-echo]` / `25 14 [counter:LE16][2B]` | stub `udp_c2s_03_1f.md`, `iter45_misc_subtags`, flows/vendor_buy.md |
| `0x15` | S→C | **HpTick** — HP-pool regen tick, current HP as LE16 | `25 15 06 [hp:LE16][4B pad]` (14B) | `s2c_1f_25_inner_extension` |
| `0x16` | S→C | **VehicleTransition** — empty marker (mount/dismount/gear change). _Older note also saw a `0x16` "rare empty event" at zone changes_ | `25 16` (no tail, 8B) | `s2c_1f_subtag_wave`, `cash_and_falldamage_subops` |
| `0x17` | C→S | **InventoryCombineItems** — F2 item-combine inner action | `25 17 …` | stub `udp_c2s_03_1f.md` |
| `0x1a` | S→C | **PoolPartialRatio** — intermediate pool % during heal/damage animation (f32, e.g. 75.8) | `25 1a [ratio:f32][4B pad]` (16B) | `s2c_1f_25_inner_extension` |
| `0x1e` | S→C | **InventoryMove echo** — server echo of the C→S `0x1e` move (container ref + slot) | `25 1e [container_ref][slot…]` (10B) | stub `udp_s2c_03_1f.md` |
| `0x1f` | S→C | **PoolFull marker** — terminal "pool at 100%" indicator after heal complete / vehicle exit. (Older note tentatively called the f32=100.0 "max soullight" — superseded: it's the pool-full marker) | `25 1f 00 00 c8 42` (f32=100.0, 12B) | `s2c_1f_subtag_wave`, `s2c_1f_25_inner_extension` |
| `0x22` | S→C | **HudPoolMaxes** — 7×LE16 pool MAX values (HP/PSI/STA/… ) broadcast; refreshes HUD after subskill/level/equip change. The missing piece for `.setsub` HUD refresh. _(In the smaller S→C 48-sample subset, `0x22` also appears as a 19B item-grant/inventory-snapshot — same sub_tag, different payload size; treat HudPoolMaxes (14B body) and item-grant (19B) as size-distinguished variants)_ | `25 22 [v1..v7:LE16]` (22B / 14B body) | `s2c_1f_subtag_wave`, stub, hud_pool_path_confirmed |
| `0x23` | S→C | **NoOpStateAck** — generic state-update ack, ~90% of S→C 0x25 traffic; trailing byte is session-stable. Also the **Zoning1 start-ack** (`0x25/0x23`) retail emits ~190ms before the commit (missing on Ceres-J) | `25 23 [trailer:u8]` (5B) | stub, `ceresj_zoning1_missing_startack`, `s2c_1f_subtag_wave` |
| `0x32` | S→C | **Heartbeat variant** — recurring tick variant (seen with trailing 1B flag; exact semantics open) | `25 32 …` | SUBTAGS.md, genrep_teleport_full_sequence (open) |

### `0x25/0x13` inner-tag map (txn-wrapped events)

Inside `25 13 [txn:LE2] [inner_tag] [data]`, the documented inner tags
(`cash_and_falldamage_subops`):

| inner_tag | tail | meaning |
|---|---|---|
| `0x04` | LE32 | wallet update (cash) — drives HUD CASH |
| `0x0b` | 2B | event marker (unconfirmed) |
| `0x0e` | 1B | event marker (unconfirmed) |
| `0x02`, `0x03`, `0x05` | var | compound multi-event payloads (unconfirmed) |

### `0x25` sub-tags still genuinely unknown

`0x0b` (counter — value semantics unconfirmed), `0x32` (heartbeat variant — exact
trigger/semantics open), and the `0x25/0x13` inner markers `0x0b`/`0x0e`/`0x02`/
`0x03`/`0x05` (seen but not byte-decoded). Predicted-but-unobserved sub-tags
(`0x05`-`0x12`, `0x20`, `0x21`, `0x24`+) per OPCODE_STRUCTURE.md §5 — function
unknown, no observations.

---

## Cross-references

- Envelope spec / act_tag canonical: `memory/c2s_03_1f_envelope_canonical.md`, stub `packets/udp_c2s_03_1f.md`
- mapId correction: `memory/correction_1f_is_event_class.md`
- Opcode-space conventions: `OPCODE_STRUCTURE.md` §5
- Lua→wire RPC mapping: `CLIENT_LUA_BRIDGE.md`
- Tag distribution table (raw counts): `SUBTAGS.md`
- Pool/HUD state machine (0x25 sub-tags): `memory/s2c_1f_25_inner_extension.md`, `memory/hud_pool_path_confirmed.md`
- Combat round-trip: `memory/c2s_1f_01_weapon_fire.md` → `memory/s2c_1f_subtag_wave2.md` (0x2c)
- Zone-cross 2-phase (0x25/0x23 + 0x25/0x13): `memory/ceresj_zoning1_missing_startack.md`, `memory/zoning1_body_pinned.md`
