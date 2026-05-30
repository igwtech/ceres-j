# Implementation Roadmap — 2026-05-29 Live Retail Session

Generated from 19 memory entries + 382MB Frida-in-Wine retail
captures collected during the iter 44+45 session.

## Status snapshot

- **12 traces** archived in `ceres-j/captures/frida_live/` (382MB)
- **8 tasks closed** via live wire data: #172, #200, #208, #260,
  #269, #311, #313 + dependent confirmations
- **30+ byte-level decodes** of opcodes / sub-tags / field maps
- **2 structural corrections** identified
  ([[correction-1f-is-event-class]] + door_id LE16/LE32 ambiguity)

## P0 server-side (Ceres-J) implementation work

### Item 1: Distinguish zone-cross variants in `Zoning1` (#172, #208)

**Current**: `Zoning1.java` extracts `newLocation` + `szoningId`
from the body but **doesn't read the `cross_subtype` byte at body
offset 6 or the `flags` field at offset 7-8** — so dungeon /
inter-zone / sector-walk crosses are handled identically.

**Spec** ([[dungeon-cross-wire]] + [[interzone-elevator-cross]]):

```
Zoning1 body (17B after [03 seq:2 22 0d]):
  pad     (1B)
  to_sec  (1B)
  char_id (4B)
  cross_subtype (1B)   ← NEW field to extract
    0x00 = sector / inter-zone
    0x03 = dungeon
  flags   (2B LE16)    ← NEW field to extract
    0x0004 = sector internal
    0xFFFF = inter-zone normal
    0x0000 = dungeon entry
  door_id (2B LE16)    ← or 4B LE32 for dungeons (TBD)
  pad     (2B)
  from_sec (4B LE32)
```

**Action**:
1. Add `crossSubtype` + `flags` fields to `Zoning1.java`'s
   `execute()`.
2. Set `Player.pendingCrossType` to one of `SECTOR | INTERZONE |
   DUNGEON` based on flags.
3. Pass `pendingCrossType` to `SZoning1ConfirmEvent` so the
   correct `spawnIdx` is used in the subsequent `Location` emit.

### Item 2: Use correct `spawnIdx` in `Location` for dungeons (#208)

**Current**: `Location(Player)` defaults to `spawnIdx=0`, and the
portal path passes `Portal.exitWorldEntity`. **No dungeon-specific
path uses `spawnIdx=0x100` (=256)** as retail does.

**Spec** ([[dungeon-cross-wire]]):
- Plaza/city default: `spawnIdx=16` (plaza_p1) or similar
- Reaktor dungeon: `spawnIdx=1`
- **Sewer dungeon (NEW): `spawnIdx=0x100 = 256`**

**Action**:
1. Extend the appplaces lookup table in `Zone.java` (or wherever
   spawn-row lives) to include sewer + cellar + other dungeon
   destinations.
2. The cross-handler (Zoning2 → Location emit path) should look
   up `Zone.dungeonSpawnIdx(destBspPath)` if `pendingCrossType ==
   DUNGEON`, else use `Portal.exitWorldEntity` or default 0.

### Item 3: LocalChat mapId hardcoding (likely)

**Current**: nc2-bot's `LocalChatMessage.java` builder probably
always emits mapId=1 (Plaza). [[correction-1f-is-event-class]]
confirmed mapId is variable per zone (1=Plaza, 5=Viarosso, etc.).

**Action**:
1. Audit Ceres-J `LocalChatMessage.java` (server-side broadcast)
   to ensure mapId is derived from `Player.getCurrentZone()` (NOT
   hardcoded).
2. Test: chat from Viarosso should broadcast with mapId=5 to all
   nearby players.

### Item 4: GR-teleport handler (#203)

**Current**: #203 says "Genrep teleport selection does nothing"
— Ceres-J probably has no handler for the C→S map-pick wire.

**Spec** ([[genrep-teleport-full-sequence]]):

```
C→S 0x03/0x1f/<mapId>/0x2f ff×8     ← player opened GR map
C→S 0x03/0x1f/<mapId>/0x4c [station_id:LE16][flag:LE16]
                                     ← player selected station
S→C 0x03/0x23 InfoResponse + ffffffff [entity_id:LE32]
S→C 0x03/0x22/0x03 Zoning1 close-handshake
S→C TCP 0x83/0x0d Zoning2 (7B)
S→C TCP 0x83/0x0c Location → "apps/vr_app_1\0" (default GR target)
C→S 0x03/0x08 ReliableAck (auto)
C→S 0x03/0x2a RequestInitBurst
```

**Action**:
1. Add `GenrepSelect.java` C→S parser for `0x1f/<mapId>/0x4c`.
2. Wire it to trigger the full Phase 2-6 sequence above.
3. For dead players: route to default genrep destination (city
   med-bay / faction apartment / VR by default).

## P1 server-side

### Item 5: Sit-broadcast wire (#205)

**Current**: Probably emits broadcast but with wrong sub-tag.

**Spec** ([[sit-on-chair-wire]]):
- C→S `0x20/0x01/0x00/0x80 [target:LE16][type:LE32=0x2c]` →
  sit-initiate
- S→C `0x03/0x1f/<mapId>/0x21 00 [type:LE32=0x2c]` → posture
  broadcast (NEW sub-tag 0x21)
- C→S `0x03/0x1f/<mapId>/0x22` empty → stand command

**Action**:
1. Update `SitOnChair.java` or equivalent to emit
   `0x03/0x1f/<mapId>/0x21` broadcast.
2. Add handler for `0x03/0x1f/<mapId>/0x22` stand command.

### Item 6: Equip-holster broadcast (#229, #195)

**Spec** ([[equip-quickbelt-wire]]):
- C→S `0x03/0x1f/<mapId>/0x1f [slot:u8]` (NOT bitmask)
- S→C `0x03/0x1f/<mapId>/0x30 [entity_id:LE32][cat][sub=0x0075 if
  fire, otherwise other][target][class=0x024b]` 14B
  PlayerActionBroadcast

**Action**: Verify Ceres-J emits this 14B broadcast on equip/fire.

### Item 7: PlayerActionBroadcast (#281, #288)

**Spec** ([[s2c-1f-30-action-broadcast]]):

```
0x03/0x1f/<mapId>/0x30
  [entity_id:LE32]
  [category:LE16]
  [sub_type:LE16]    ← 0x0075 for WPN_FIRE
  [target_id:LE16]
  [class_id:LE16]    ← 0x024b for default char class
```

## P0 client-side (nc2-bot)

### Item 8: LocalChat — already correct (verify only)

`nc2-bot/nc2bot/packets/localchat.py` already builds
`[1f][mapId LE2][1b][message]`. Just verify the bot fills the
correct mapId for its current zone.

### Item 9: Add posture / equip-holster builders

Add to nc2-bot:
- `PostureBroadcast.encode(map_id, posture_type)` →
  `[1f][mapId LE2][21][00][type:LE32]`
- `StandCommand.encode(map_id)` → `[1f][mapId LE2][22]`
- `EquipHolster.encode(map_id, slot)` → `[1f][mapId LE2][1f][slot:u8]`
- `WeaponFire.encode(map_id, target, weapon_id)` →
  `[1f][mapId LE2][01]<...byte map TBD>`

### Item 10: Zoning1 / Zoning2 builder with cross_subtype

Add to nc2-bot zone-cross simulator support for all 3 variants:
sector, inter-zone, dungeon.

## Open research leads (not blocking impl)

- **#210 446B death-overlay cipher-desync** —
  [[death-cipher-desync]] — these packets fail LFSR decrypt.
  Could be Synaptic Impairment overlay. Needs cipher-state
  investigation via Ghidra.
- **#295 0x07 multipart Lua script paths** — large multipart
  packets carry ASCII script paths. Need parser + handler.
- **#175 intermittent 2nd-cross crash** — second GR-teleport
  may still hang. Test once #208 lands.

## Implementation priority order

1. **Item 1+2** (#208 dungeon + #172 inter-zone discrimination) —
   high impact, byte-pinned, blocks user-facing zone hang
2. **Item 4** (#203 GR teleport) — closes user-visible bug
3. **Item 5** (#205 sit-broadcast) — visible to other players
4. **Item 3** (LocalChat mapId verification) — quick correctness
   audit
5. **Item 6+7** (equip + action broadcast) — combat-related,
   needs more captures to enumerate sub-codes
6. **Items 8-10** (nc2-bot parallel impl) — for protocol
   regression testing

## How to use this doc

For each item, refer to the linked memory entry for byte-level
spec. The memory entries cite specific traces in
`ceres-j/captures/frida_live/` for ground truth.

Related: All 19 memory entries from 2026-05-29 iter 44+45.
