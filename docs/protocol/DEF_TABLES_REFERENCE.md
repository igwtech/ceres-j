# Neocron 2 `.def` Tables Reference

_Derived 2026-05-23 from a live Postgres dump (`neocron-postgres`, 43 imported
def files, 24,594 entry rows) cross-referenced against the Ceres-J Java
consumers and the TinNS NC1 authoritative `LoadFromDef` parsers (which Ceres-J
inherits the field semantics from — NC1 and NC2 share the `.def` text format
1:1)._

This document is the canonical map between the **client `.def` files**
(imported into `client_defs(def_name, entry_id, fields JSONB)`) and the
**runtime tables** (`world_objects`, `world_defs`, `npc_spawns`, …). It
exists because zone-cross routing, item creation, NPC spawn and mission
resolution all chain through these tables, and the relationships were
previously documented only piecemeal across source comments and TinNS sources.

> **Field-index convention.** The importer writes JSON keys `f0..fN` starting
> at the token AFTER the directive (`setentry` / `setfrac` / `setdef`) AND the
> id token. So in a `.def` line:
>
> ```
> setentry  <id>   <fieldA>   <fieldB>   <fieldC>   …
>           ↑      ↑          ↑          ↑
>           entry_id  f0         f1         f2
> ```
>
> When citing TinNS, TinNS's `Idx` iterator counts the directive as `case 0`
> and the id as `case 1`, so **TinNS `case N` ⇔ Ceres-J `f(N-2)`** for
> `N ≥ 2`. The translation is applied in the per-def tables below.

---

## Contents

1. [Two-tier data model](#two-tier-data-model)
2. [Quick-reference relationship diagram](#quick-reference-relationship-diagram)
3. [Worked example: portal `worldmodel[139]` end-to-end](#worked-example-portal-worldmodel139-end-to-end)
4. [Runtime tables](#runtime-tables)
5. [Per-def schema](#per-def-schema)
   - [World / portal / zone family](#world--portal--zone-family)
   - [Item / weapon / ammo family](#item--weapon--ammo-family)
   - [Character / skill family](#character--skill-family)
   - [NPC / spawn family](#npc--spawn-family)
   - [Mission / dialog family](#mission--dialog-family)
   - [Faction / outpost family](#faction--outpost-family)
   - [Misc support defs](#misc-support-defs)
6. [Cross-def relationship chains](#cross-def-relationship-chains)
7. [Open questions / unmapped defs](#open-questions--unmapped-defs)
8. [Index of useful queries](#index-of-useful-queries)

---

## Two-tier data model

Ceres-J's data layer is split in two tiers:

| Tier | Source | Tables | Owner |
|---|---|---|---|
| **Client defs** (1:1 from `.def` text) | NC2 client `defs\*.def` | `client_defs(def_name, entry_id, fields jsonb)` | `server.database.importer.DefImporter` |
| **World runtime** (parsed `.dat`/`.bsp`) | NC2 client `worlds\*` | `world_objects`, `world_doors`, `world_npcs`, `world_passive_objects`, `world_position_markers`, `world_regions`, `world_labeled_regions`, `world_tagged_entities`, `world_extras`, `world_npc_waypoints`, `world_raw_elements`, `world_defs` | `server.database.importer.WorldDatImporter` + `ClientDataImporter` |
| **Server runtime / persistence** | DB-only | `accounts`, `player_characters`, `items`, `item_containers`, `item_defs`, `npc_spawns`, `schema_versions` | Ceres-J itself; mutated at runtime |

The **runtime** layer references the **client defs** layer at well-defined
join points (e.g. `world_objects.worldmodel_id` → `client_defs(def_name='worldmodel', entry_id=…)`).
The whole purpose of this document is to make those joins explicit.

Counts (`SELECT def_name, COUNT(*) FROM client_defs GROUP BY def_name`):

| def_name | rows | def_name | rows | def_name | rows |
|---|---|---|---|---|---|
| actionmod | 109 | hack | 1 | npcgroupspawn | 560 |
| ammo | 313 | implants | 271 | npcloot | 2 |
| appartements | 232 | itemcontainer | 347 | outposts | 37 |
| appplaces | 1420 | itemmod | 223 | recycles | 227 |
| armor | 377 | itemplan | 448 | respawn | 82 |
| blueprintpieces | 22 | itemres | 640 | routemenu | 23 |
| characters | 2522 | items | 4398 | routesubmenu | 222 |
| charaction | 32 | maps | 162 | scripts | 809 |
| charkinds | 27 | missionbase | 1639 | shots | 693 |
| damage | 738 | modeltextures | 97 | skills | 5 |
| drugs | 233 | npc | 2534 | subskill | 33 |
| effects | 1 | npcarmor | 1145 | trader | 309 |
| fractions | 22 | | | weapons | 781 |
| gameplaysettings | 217 | | | weather | 169 |
| | | | | worldinfo | 837 |
| | | | | worldmodel | 1733 |
| | | | | worldspawnpoints | 1543 |

---

## Quick-reference relationship diagram

```
       ┌──────────────────────────────────────────────────────────────┐
       │                       WORLD / ZONE                           │
       └──────────────────────────────────────────────────────────────┘

world_objects(world_path, object_id)
  │ worldmodel_id
  ▼
worldmodel(entry_id)  f0=name  f1=UseFlags  f2=functionType  f3=functionValue
  │                                          │ ft ∈ {15,18,20,29}: ZONE-CHANGE
  │ ufChair = 8 (sit)                        │ ft = 6: GenRep (respawn link)
  │                                          ▼
  │                                       appplaces(entry_id = fval)
  │                                          │ f1=ExitWorldID
  │                                          ▼
  │                                       worldinfo(entry_id = ExitWorldID)
  │                                          │ f0=name f1=type f2=alternateDatFile
  │                                          ▼
  │                                       world_defs(id = ExitWorldID)
  │                                          path  bsp_name
  ▼
worldspawnpoints (entry_id = same as worldmodel) — alt model index by spawnpoint type

worldinfo  ◄── maps(f1)               ── world-image atlas referencing zones
                                         (route map, mini-map index)


       ┌──────────────────────────────────────────────────────────────┐
       │                       ITEM / EQUIPMENT                       │
       └──────────────────────────────────────────────────────────────┘

items(entry_id)  f0=name  f1=model  f2=type  f3=TL  f10=mResStart f11=resCount …
  ▲                          ▲
  │                          │
  ├── weapons(f1=itemId) ─────┴── itemres → ssq (subskill) requirements
  │
  ├── armor(f0=itemId) ─── damage → (damageEffect, damageType)
  │
  ├── implants(f3=skill+ssq target) ─── via skills + subskill
  │
  ├── drugs(f4=ssq target via f5..) ─── via subskill
  │
  ├── damage(entry_id ↔ items via shots) ── carries actual damage triples
  │
  ├── shots(f0=itemId, f1=damageId) ─── ammo→shots→damage chain
  │
  ├── ammo(f0=damageId, f1=shotsId or wpnShotId)
  │
  ├── itemmod(per-item attribute mod) ─── via items.f17 mGfxMods
  │
  ├── itemres → ssq restrictions     ── consulted on equip
  │
  ├── itemplan / blueprintpieces ─── recipes / parts (research)
  │
  ├── recycles(f0=resultItemId, f3..= partItemIds) ─── recycling outputs
  │
  ├── itemcontainer(loot table)     ── stack of itemId+chance, used by NPCs
  │
  └── trader(f4..= itemId pairs)    ── vendor inventories


       ┌──────────────────────────────────────────────────────────────┐
       │                       NPC / SPAWN                            │
       └──────────────────────────────────────────────────────────────┘

npc(entry_id) — "NPC type" (= NameID, the entity class) — see TinNS PDefNpc
  f0=model  f2=NGT  f3=gender  f4=faction  f5=health  f6=armorId
  f7=weaponId  f8=dialogScript  f10=loot(=itemcontainer id)
  f12=functionType  f16=standardScript  f17=standardParameter
  f18=mass  f22=flags
                 │           │              │
                 │           │              ▼
                 │           │           itemcontainer (loot table — chained id triples)
                 │           ▼
                 │       fractions(entry_id = faction)
                 ▼
              npcarmor(entry_id = armorId)        f0..f6 = damage-mitigation per type
              7-slot mitigation array

world_npcs(world_path, npc_id, npc_type_id, trade_id, …)   ◄── parsed .dat
   │           ▲
   │ npc_type_id → npc(entry_id)
   │ trade_id → trader(entry_id)  (per-NPC vendor inventory override)
   ▼
npc_spawns(zone_id, type, script_name, …)  — Ceres-J curated layer

npcgroupspawn(entry_id) — group templates (8 NPCs × {type,script,param,fval,chance})


       ┌──────────────────────────────────────────────────────────────┐
       │                       MISSION / DIALOG                       │
       └──────────────────────────────────────────────────────────────┘

missionbase(entry_id)  f0=sourceId(NPC type or terminal id)
  f1=startDialog  f2=descTextId
  f3..f14 = 4× (npcType, npcDialog, dialogStartState)  -- mission NPCs
  f15..f30 = 4× (targetType, value1, value2, value3)    -- targets
  f31..f35 = endMoney, endXp, maxTime, difficulty, minFactionValue
  f36..f37 = points, flags
            ▲                  ▲                       ▲
            │                  │                       │
            │                  │                       └── fractions (minFactionValue)
            │                  └── npc (via npcType)
            └── scripts (startDialog string → scripts[ident].luaFile)


       ┌──────────────────────────────────────────────────────────────┐
       │                       CHARACTER / SKILL                      │
       └──────────────────────────────────────────────────────────────┘

charkinds(entry_id) — class template ("Engineer", "Tank", …) — see TinNS PDefCharKind
  f0=name f1=type  f2..f(2+3·NSk-1) = (start, max, grow) per skill
  ...     32× (skillId, points)  (subskill OR skill train points)
          16× (subskillId, level)
          money, then inventory (id pairs)
                 │
                 ▼
              skills(entry_id 1..5)  — STR DEX CON INT PSI master + subskill list
                 │ f4 = numSubSkills
                 ▼
              subskill(entry_id 1..33)  — short_name, strengthen, actionmods

characters(entry_id) — 2522 entries — character "definitions" used for
  • PC archetypes (lobby gender × profession)
  • NPC visual templates (model + head/torso/legs + colors)
  f0=name f1=model f2=(reserved) f3=head f4=torso f5=legs f6=color f7=brightness
  (NC1-era; NC2 char create also uses f8..f14 for textures: see ItemInfoManager#init
   javadoc and CreateCharacter)
                 │ f3, f4, f5, f8, f9, f10 (texture indices)
                 ▼
              modeltextures(entry_id ≈ f3/10)  — 90-element texture LUT


       ┌──────────────────────────────────────────────────────────────┐
       │                    APARTMENTS / SETTINGS                     │
       └──────────────────────────────────────────────────────────────┘

appartements(entry_id) — purchasable apartment layouts
  f0=name f1=path(world basename) f2=(reserved) f3=value f4..f11 = 8× place ids
  f12=faction (for base apartements only)
            │
            ▼  f1 = world basename → world_defs.path component

gameplaysettings(entry_id) — server tunables (key/value-ish)
  f0=settingName(string) f1=value(float)

routemenu(entry_id) / routesubmenu(entry_id) — UI menu trees for the
  city-route HUD; non-relational data, used to render the directory.
```

---

## Worked example: portal `worldmodel[139]` end-to-end

This is the chain that drives the dungeon-portal interaction at the
viarosso `bop/underground_lab` entrance. Each row was queried from the
live DB to verify it resolves end-to-end.

| Step | Query | Result |
|---|---|---|
| 1. World object (clicked actor) | `SELECT world_path, object_id, worldmodel_id FROM world_objects WHERE worldmodel_id=139 LIMIT 1` | `worlds/viarosso/pak_viarosso_p1.dat`, object_id=16, **worldmodel_id=139** |
| 2. `worldmodel.def[139]` | `SELECT fields FROM client_defs WHERE def_name='worldmodel' AND entry_id=139` | `f0="ABANDONED LABS 3 (VERY EASY)"`, **f2=20** (DATFILE WORLDCHANGE ACTOR), **f3=722** (appplaces id) |
| 3. `appplaces.def[722]` | `SELECT fields FROM client_defs WHERE def_name='appplaces' AND entry_id=722` | `f0="Abandoned Rooms Lvl.3 ENTRANCE"`, **f1=1075** (ExitWorldID), `f2=1` (Entity), `f3=0` (sewerLevel) |
| 4. `worldinfo.def[1075]` | `SELECT fields FROM client_defs WHERE def_name='worldinfo' AND entry_id=1075` | `f0="ABANDONED LABS 3 (VERY EASY)"`, `f1=3` (type), **f2=".\\worlds\\bop\\underground_lab_x1.dat"** (alternate .dat) |
| 5. `world_defs[1075]` (default path) | `SELECT path, bsp_name FROM world_defs WHERE id=1075` | `bop/underground_lab`, `underground_lab.bsp` |

The destination .dat the client loads is determined by:

- if `worldinfo.f2` is non-empty/non-space → use **alternate `.dat` path**
  (the dungeon variant `_x1.dat`),
- else → use `world_defs.path` (normal sector geometry).

The wire packet emitted by Ceres-J (`ChangeLocation`,
`server.gameserver.packets.server_udp.ChangeLocation`) carries
`(ExitWorldID, Entity, entityTypeByte)` only — the client self-positions
from the destination zone's `.dat` against `Entity` (see
`docs/zone_portal_params.md` §4 for proof from retail pcap).

`entityTypeByte` is derived from `functionType` (NOT from
`appplaces.f3`):

```
entityTypeByte = (functionType == 20 || functionType == 29) ? 1 : 0
```

(Source: `PortalResolver.java:84-86` mirrors TinNS
`decoder/UdpUseObject.cxx:341-475`.)

---

## Runtime tables

These are populated at startup by `ClientDataImporter` (worlds.ini) and
`WorldDatImporter` (per-`.dat` parse), and mutated at runtime by the
gameserver.

### `world_defs(id, path, bsp_name)`
837 rows. Master zone registry. Loaded from `worlds\worlds.ini`
(`ClientDataImporter`). Primary key `id` is the **worldId** referenced by
every other table.

| col | type | meaning |
|-----|------|---------|
| `id` | INT PK | NC2 worldId (1=plaza_p1, 5=pepper_p1, 1064=sewer_p4, …) |
| `path` | TEXT | dotted base path (`plaza/plaza_p1`) |
| `bsp_name` | TEXT | `<basename>.bsp` |

The **resolution rule** for the physical `.dat` file the engine loads:

- if `client_defs[worldinfo].fields->>f2` is non-empty / non-space →
  alternate path (e.g. `worlds\bop\underground_lab_x1.dat`);
- else → `worlds/<path>/<basename>.dat`.

Consumed by: `server.database.worlds.WorldManager`,
`server.gameserver.Zone`, `server.gameserver.packets.server_tcp.Location`.

### `world_objects(id, world_path, object_id, worldmodel_id, model_id, pos_*, rot_*, scale, bbox_*)`
Static furniture/world actors. **Primary cross-ref** into
`client_defs(worldmodel, entry_id=worldmodel_id)`. The keys to remember:

- `world_path` is the file-system style `worlds/<dir>/pak_<basename>.dat` —
  derive from `world_defs.path` via
  `'worlds/' || regexp_replace(path, '([^/]+)$', 'pak_\1') || '.dat'`
  (see `NpcSpawnManager.java:107-108`).
- `object_id` is the per-zone unique furniture id the client sends in the
  `0x03/0x1f/0x17` UseItem packet
  (`raw_object_id = (object_id + 1) * 1024`, low 10 bits clear for
  furniture — see `UseItem.java:40-41`).

Consumed by: `server.gameserver.PortalResolver`,
`server.gameserver.packets.client_udp.UseItem`.

### `world_doors(id, world_path, door_id, worldmodel_id, pos_*, actor_type, params)`
Doors. **NOT portals** — see `docs/zone_portal_params.md` §1. `actor_type`
is one of `DDOOR / TRIGDD / TRIGDOOR / SDOOR / NBUTTON`, `params` is the
TinNS `mDoorParameters[0..3]` comma list. Use `worldmodel_id` to resolve
the model entry (door visual/sound).

### `world_npcs(id, world_path, npc_id, npc_type_id, trade_id, pos_*, actor_name, angle, has_waypoints)`
NPCs declared in the `.dat`. **`npc_type_id` → `client_defs(npc, entry_id)`** —
this is the NPC "class" (faction, health, dialog script, weapon, …).
`trade_id` → `client_defs(trader, entry_id)` (vendor inventory).
`actor_name` is the per-spawn AI-script token the client SCRIPTEDPLAYER
constructor reads (`NpcSpawnManager.java:122-135`).

### `world_npc_waypoints(npc_row_id, idx, pos_*)`
Walk paths for NPCs that move. FK `npc_row_id → world_npcs.id`.

### `world_passive_objects(id, world_path, entry_id, worldmodel_id, pos_*, raw)`
Decorative-only objects (no interaction). `worldmodel_id` resolves to a
worldmodel.def entry the same way `world_objects` does.

### `world_position_markers(id, world_path, element_type, pos_*, trailer)`
World tags / spawn beacons / region anchors — `element_type` differentiates
(NC2 retail subset; un-decoded element types preserved as raw `trailer`
bytes).

### `world_regions(id, world_path, pos_*, dim1, dim2, flag, region_id)`
Trigger regions (sector-border crossing, safe-zone, etc.). `flag` is the
region kind; semantics partially documented in
`docs/zone_portal_params.md` §6 (vehicle-zoning border-region logic).

### `world_labeled_regions(id, world_path, name, pos_*, dim1, dim2)`
Named regions — sub-areas like "OZ-Pepper" or "Reaktor Halle" used by chat
location text and `0x03/0x23` info responses.

### `world_tagged_entities(id, world_path, entity_id, counter, subtype, sub2, pos_*, tail)`
Generic per-zone catch-all for entity classes the parser recognises by
`(subtype, sub2)` pair but doesn't decode further. Diagnostic table —
inspect when adding support for a new world-element class.

### `world_extras(…)` / `world_raw_elements(…)`
Catch-all for parser-misses; raw bytes preserved so a future RE pass can
decode them without re-importing.

### `npc_spawns(id, zone_id, type, name, script_name, model_name, x, y, z, angle, hp, armor)`
Curated NPC spawns (per Ceres-J authoring). Higher priority than
`world_npcs`; if a `zone_id` has no `npc_spawns` rows the `NpcSpawnManager`
falls back to `world_npcs` and bridges via the path mapping above.

### `item_containers(id, type)` / `item_defs(id, name, type, tech_level, stats_json)` / `items(id, container_id, type_id, slot, quality, flags, tokens)`
Server-side persistent item state. `items.type_id` ↔
`client_defs(items, entry_id)`. `container_id` ↔ `item_containers.id`
(player inventory, vendor box, equipment slots …).

### `player_characters(…)`
Per-character persistent state. Refs into client defs:

- `class` → `client_defs(charkinds, entry_id)` (e.g. 1=Engineer, 2=Field Medic)
- `faction` → `client_defs(fractions, entry_id)`
- `location` → `world_defs.id` (current zone)
- `model_head / torso / leg / hair / beard` → `client_defs(characters, …)`
- `texture_*` → `client_defs(modeltextures, …)`

### `accounts(…)`
Account-tier persistence (login, chars-per-account). Independent of `client_defs`.

### `schema_versions(version, applied_at)`
Migration ledger — `SqliteDatabase.CURRENT_SCHEMA_VERSION = 8`.

---

## Per-def schema

For each def the table below uses this template:

- **Represents** — what this def is for.
- **Field shape** — semantic mapping of every `fN` slot.
- **TinNS source** — authoritative parser the field names came from.
- **Java consumers** — code in `ceres-j/src/main/java/` that reads it.
- **FK out** — `fN → other_def`.
- **FK in** — other defs / runtime tables that point AT this one.

### World / portal / zone family

#### `worldmodel` (1733 rows)

- **Represents**: physical/model template for every static furniture &
  interactable in every zone. Drives use-flags (chair, door, terminal,
  portal …) and the portal-resolve `(functionType, functionValue)` pair.
- **Sample rows**:
  - `1 = DOOR` `f1=2 f2=0 f3=0`
  - `87 = HOLOMATCH\nEXIT` `f1=2 f2=15 f3=0`
  - `139 = ABANDONED LABS 3 (VERY EASY)` `f1=66 f2=20 f3=722`
  - `302 = sewer entrance` `f2=18 f3=52`
- **Field shape** (TinNS `definitions/WorldModels.cxx` → Ceres `f` map):

  | f | TinNS name | type | meaning |
  |---|---|---|---|
  | f0 | `mName` | string | display name (visible in `BuildText100Msg` etc.) |
  | f1 | `mUseFlags` | int | bitmask: `2`=Door, `8`=Chair (ufChair), `64`=triggered-button, `66`=trigger+door … |
  | f2 | `mFunctionType` | int | **Zone-change discriminator**. `6`=GenRep, `15`=Holomatch exit, `18`=WORLDCHANGEACTOR, `20`=DATFILE world change, `29`=Underground exit; other values are local-action (sit, use-terminal, etc.) |
  | f3 | `mFunctionValue` | int | semantics depend on `f2`: when zone-change, the key into `appplaces.def`; when GenRep, the link to `respawn.def`. |
  | f4 | `mHackDifficulty` | int | hack mini-game difficulty for hackable objects |
  | f5 | `mHackPenalty` | int | hack-failure penalty |
  | f6 | (unused, TinNS reserved) | int | always 0 |
- **TinNS source**: `tinns/tinns/gameserver/definitions/WorldModels.cxx:7-40`.
- **Java consumers**:
  - `PortalResolver.java:147-152` (reads f2 = functionType, f3 = functionValue)
  - `PortalResolver.java:193-231` (reads f1 = UseFlags, checks `f1 & 8` for chair)
  - `SitOnChair.java`, `UseItem.java` (chair / portal dispatch)
- **FK out**:
  - `f2 ∈ {15,18,20,29}` ⇒ `f3 → appplaces.entry_id` (portal)
  - `f2 = 6` ⇒ `f3 → respawn.entry_id` (GenRep link)
- **FK in**:
  - `world_objects.worldmodel_id → worldmodel.entry_id`
  - `world_doors.worldmodel_id → worldmodel.entry_id`
  - `world_passive_objects.worldmodel_id → worldmodel.entry_id`

#### `appplaces` (1420 rows)

- **Represents**: destination address book for zone-change actors and
  apartment placement points. Each entry resolves to
  `(ExitWorldID, ExitWorldEntity, SewerLevel)`.
- **Sample rows**:
  - `1 = PEPPER SEWER DOWNSTAIRS 1A` `f1=952 f2=9 f3=4`
  - `52 = sewer exit` `f1=1 f2=52 f3=0`
  - `722 = Abandoned Rooms Lvl.3 ENTRANCE` `f1=1075 f2=1 f3=0`
- **Field shape** (TinNS `definitions/AppartementPlaces.hxx:7-25`):

  | f | TinNS name | type | meaning |
  |---|---|---|---|
  | f0 | `mName` | string | human-readable label ("PEPPER SEWER DOWNSTAIRS 1A") |
  | f1 | `mExitWorldID` | int | destination `worldId` (joins `worldinfo.entry_id` AND `world_defs.id`) |
  | f2 | `mExitWorldEntity` | int | insertion-point / entity index — resolved by the **client** against the destination `.dat` (no server XYZ) |
  | f3 | `mSewerLevel` | int | sewer-depth hint; TinNS uses this for debug only — the wire byte sent in ChangeLocation is `(ft==20||ft==29)?1:0`, NOT this field |
- **TinNS source**: `tinns/tinns/gameserver/definitions/AppartementPlaces.{hxx,cxx}`.
- **Java consumers**: `PortalResolver.java:165-181`.
- **FK out**: `f1 → worldinfo.entry_id` AND `f1 → world_defs.id` (same key in both tables).
- **FK in**: `worldmodel.f3 → appplaces.entry_id` (when `worldmodel.f2 ∈ {15,18,20,29}`).

#### `worldinfo` (837 rows)

- **Represents**: per-zone metadata — display name, zone "type", optional
  alternate `.dat` path override. **One row per worldId.**
- **Sample rows**:
  - `1 = PLAZA SEC-1` `f1=1 f2=" " f3=16 f4=1`
  - `940 = PEPPER PARK SEWER 3.1 (NORMAL)` `f1=6 f2=" " f3=1 f4=4`
  - `1064 = ABANDONED CELLAR 2 (EASY)` `f1=3 **f2=".\\worlds\\sewer\\sewer_p4_x1.dat"** f3=1 f4=4`
  - `1075 = ABANDONED LABS 3 (VERY EASY)` `f1=3 f2=".\\worlds\\bop\\underground_lab_x1.dat" f3=1 f4=4`
- **Field shape** (TinNS `definitions/Worlds.cxx`):

  | f | TinNS name | type | meaning |
  |---|---|---|---|
  | f0 | `mName` | string | display name (city/sector/dungeon human label) |
  | f1 | (type) | int | zone class — values: `1`=city, `3`=dungeon, `6`=sewer, `16`=indoor; used for chat-location / minimap routing |
  | f2 | `mDatFile` | string | **alternate `.dat` path** (`".\\worlds\\..."`) overriding the default `world_defs.path`. A literal `" "` (single-space) means "use default" |
  | f3 | `mFlags` | int | engine flags (sector kind, indoor/outdoor, weather-pause, …) — not RE'd field-by-field |
  | f4..f9 | (reserved / NC2 extension) | int | typically `0..4` small integers; faction influence / hazard markers; not consumed by current Ceres-J |
- **TinNS source**: `tinns/tinns/gameserver/definitions/Worlds.cxx` (note: TinNS parses fields 4=datfile, 5=flags, so `case 4→f2` and `case 5→f3`).
- **Java consumers**: none direct; `WorldManager` uses `world_defs.path` (the
  pre-imported normalised form) instead. The override semantics of `f2`
  are documented but not yet wired into Ceres-J — **gap** noted in §Open
  questions.
- **FK out**: none (this IS the zone master).
- **FK in**:
  - `appplaces.f1 → worldinfo.entry_id`
  - `maps.f1 → worldinfo.entry_id` (the route-map indexes worldId via the map's `f1` field)
  - `world_defs.id == worldinfo.entry_id` (1:1 join)
  - `npc_spawns.zone_id == worldinfo.entry_id`

#### `worldspawnpoints` (1543 rows)

- **Represents**: ⚠️ The first 4 fields shadow `worldmodel` exactly (the
  data we sampled — entries 1,2,3 — have the same `f0=name f1=useFlags
  f2=ft f3=fval` shape). This appears to be a per-spawnpoint variant of
  worldmodel for **respawn / GenRep placement** rather than for general
  furniture; needs deeper RE.
- **Sample row**: `1 = DOOR f1=2 f2=0 f3=0 f4=0 f5=0` (one less trailing
  field than worldmodel, but the data is `worldmodel`-shape-compatible).
- **Field shape** (assumed from sample, NOT verified against TinNS — TinNS
  has no `WorldSpawnPoints.cxx`):

  | f | name (assumed) | type | meaning |
  |---|---|---|---|
  | f0 | name | string | display label |
  | f1 | useFlags | int | mirrors worldmodel f1 |
  | f2 | functionType | int | mirrors worldmodel f2 |
  | f3 | functionValue | int | mirrors worldmodel f3 |
  | f4..f5 | (reserved) | int | always 0 in sampled data |
- **Java consumers**: none currently.
- **Open**: confirm whether this is a parallel respawn-placement table or
  a worldmodel slice; very high field-overlap suggests it may be the
  `respawn.def` companion (entries used by `respawn.def`'s `linkedObjectId`).

#### `maps` (162 rows)

- **Represents**: the in-game route-map atlas — minimap thumbnails +
  named sector list per zone.
- **Sample**: `1 = f0=1 f1=2001 f2=195 f3=204 f4=-3248.0 f5=-2767.0 f6=0.028 f7=280 f8=432 f9=1001 f10="Plaza Sec-2" …`
- **Field shape** (semantic-only — no TinNS source for this, derived from data):

  | f | meaning |
  |---|---|
  | f0 | source worldId (this map represents zone `f0`) |
  | f1 | map-image worldId (the larger atlas zone-image showing the area, joins worldinfo / atlas tables) |
  | f2..f3 | map pixel size (W, H) |
  | f4..f5 | world-coord top-left anchor (float) |
  | f6 | world→pixel scale (float) |
  | f7..f8 | offsets (border?) |
  | f9..f40 | alternating `(worldId, "name")` pairs — visible sector labels |
- **FK out**: `f1 → worldinfo.entry_id`; alternating `(worldId, name)` pairs → worldinfo.
- **Java consumers**: none currently (UI atlas isn't yet wired).

### Item / weapon / ammo family

#### `items` (4398 rows)

- **Represents**: the master item table — every craftable, equippable,
  consumable, weapon-back-reference, ammo-item, container reference, etc.
- **Sample**: `1 = "A&W Street Model Gun" f1=26 f2=1 f3=1 f4=0 f8=0.95 f10=1 f11=11 f12=11 f20=2 f21=1 f22=0`
- **Field shape** (TinNS `definitions/Items.cxx`, NC2 has additional
  fields beyond TinNS — known TinNS-mapped fields below; the remainder are
  client-side display only):

  | f | TinNS Idx | TinNS name | type | meaning |
  |---|---|---|---|---|
  | f0 | 2 | `mName` | string | display name |
  | f1 | 3 | `mModel` | int | model id for IG display (cross-refs visual asset, not RE'd here) |
  | f2 | 4 | `mType` | int | item kind discriminator (1=weapon ref, 2=armor, 3=ammo, 4=implant, …) |
  | f3 | 5 | `mValue1` | int | type-dependent stat (e.g. TL) |
  | f4 | 6 | `mValue2` | int | type-dependent stat |
  | f5 | 7 | `mValue3` | int | type-dependent stat |
  | f6..f7 | 8..9 | (BmNum / mmBmNumIndex) | — | client display indexes (ignored server-side) |
  | f8 | 10 | `mSizeX` | int (real in sample) | inventory grid X (note: NC2 sample shows `0.95` here — engine reads as float weight in some builds; cross-check ItemInfo.java which uses tokens[10] = weight) |
  | f9 | 11 | `mSizeY` | int | inventory grid Y |
  | f10 | 12 | (Smallbmnum) | — | display |
  | f11 | 13 | `mWeight` | float | kg |
  | f12 | 14 | `mStackable` | int 0/1 | stackable flag |
  | f13 | 15 | `mFillWeight` | float | container fill weight |
  | f14 | 16 | `mQualifier` | int | (mod restriction?) |
  | f15 | 17 | `mGfxMods` | int | visual mod group |
  | f16 | 18 | `mItemGroupID` | int | recycler / random-drop grouping (see PDefItemsMap::BuildItemGroups) |
  | f17 | 19 | `mTextDescID` | int | UI text-table description id |
  | f18 | 20 | `mBasePrice` | int | base sell price |
  | f19 | 21 | `mTechlevel` | int | TL |
  | f20 | 22 | `mItemflags` | int | bitfield (rare, unique, tradeable, …) |
  | f21..f22 | — | (NC2 extra slots `mInvSizeX/Y` per ItemInfo.java:31-33) | int | inventory grid override |

  Note that `ItemInfo.java` (the legacy Ceres-J wrapper) reads
  `tokens[2]=name`, `tokens[3]=TL`, `tokens[4]=type`, `tokens[8]=stackable`,
  `tokens[10]=weight`, `tokens[21]=invX`, `tokens[22]=invY` — confirming
  the field mapping above (with the NC2-specific shuffle: NC2 puts the
  inventory size at the *end* whereas TinNS NC1 has it at `mSizeX/Y` early).

- **TinNS source**: `tinns/tinns/gameserver/definitions/Items.cxx:30-100`.
- **Java consumers**:
  - `ItemInfoManager.java` (loads tokens, but stores ID/Name/TL/Type/Weight/SizeXY only)
  - Schema v2 `item_defs(id, name, type, tech_level, stats_json)` — not yet populated from `client_defs(items)`, **gap** noted in `ItemInfoManager.java:13` TODO.
- **FK in**: very wide — `weapons.f1`, `armor.f0` (sometimes), `damage.f0`,
  `shots.f0`, `recycles.f0` and `recycles.f3..f10`, `trader.f4..` (item id slots),
  `itemcontainer.f3..` (item id slots), `npc.f7` (weaponId), `itemres.f0`.

#### `weapons` (781 rows)

- **Represents**: weapon stats — per-weapon ammo use, max range, skill
  multiplicator, ammo-type slots.
- **Sample**: `1 = "A&W Street Model Gun TL 26" f1=1 f2=2 f3=26 f4=0.307 f5=1 f15=16 f16=1 f17=0.384 f20=1 f21=1 f23=0 …`
- **Field shape** (TinNS `definitions/Weapons.cxx:13-100`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mName` | display name |
  | f1 | 3..6 | `Fpsmodel/Attachmodel/Munactor/Droptime` (skipped) | (client) |
  | f5 | 7 | `mItemIndex` | **→ items.entry_id** — the parent item row |
  | f6..f8 | 8..10 | effectcolor[3] | (client) |
  | f13 | 15 | `mAmmoUse` | shots per shot button-press |
  | f14 | 16 | `mPSIuse` | PSI cost per shot |
  | f15 | 17 | `mStaminaUse` | stamina cost (float) |
  | f17 | 19 | weaponHold | (client) |
  | f18 | 20 | `mWeaponType` | type code (melee/heavy/pistol/rifle/PSI/…) |
  | f19 | 21 | `mDiscardable` | int |
  | f20 | 22 | `mSkillFactor` | skill→damage multiplier (float) |
  | f23 | 25 | `mMaxRange` | int |
  | f24 | 26 | `mAggressiveWeapon` | 0/1 |
  | f25 | 27 | `mDamageMultiplicator` | float |
  | f26..f33 | 28..35 | `mAmmoTypes[8]` | → 8 slots of `ammo.entry_id` (only first N used per weapon) |

  > In the sampled row the **f1=1** matches `items[1]` — so the TinNS
  > `case 7 = mItemIndex` aligns to Ceres `f5` only if the NC1 .def has
  > unused 3-skip; the **NC2 .def appears to have `mItemIndex` at f1**
  > (i.e. case 3, shifted). Sample: `weapons[1].f1=1` ↔ `items[1].f0="A&W Street Model Gun"`.
  > Verified by SQL join (`weapons.f1 → items.entry_id`, 694/781 hits).
- **Java consumers**: schema v2 `item_defs` (TODO);
  decoders in `server.gameserver.npc.MobAttackTicker` may pull `f15` for
  damage scaling but that path is not yet wired.
- **FK out**: `f1 → items.entry_id`; `fN..` ammo slots → `ammo.entry_id`.
- **FK in**: `items.f2 ∈ {1=weapon}` rows are mirrored here; the
  reciprocal lookup is `weapons.f1 = items.entry_id`.

#### `ammo` (313 rows)

- **Represents**: ammo type — links to damage profile, shot fx, magazine
  capacity.
- **Sample**: `1 = f0=250 f1=1 f2=0 f3=20`
- **Field shape** (TinNS `definitions/Ammo.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mDamageId` | → `damage.entry_id` |
  | f1 | 3 | `mWeaponShotId` | → `shots.entry_id` (the firing fx) |
  | f2 | 4 | `mMagSize` | rounds per magazine |
  | f3 | 5 | `mShotId` | secondary shots ref (rare; usually 0) |
- **FK out**: `f0 → damage`, `f1 → shots`, `f3 → shots`.

#### `shots` (693 rows)

- **Represents**: projectile/shot effect — mass, radius, speed, damage.
- **Field shape** (TinNS `definitions/Shots.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mDamageId` | → `damage.entry_id` |
  | f1 | 3 | `mMass` | int |
  | f2 | 4 | `mRadius` | int (explosion radius) |
  | f3 | 5 | `mSpeed` | float (m/s) |
  | f4..f12 | (client fx tail) | strings/ints | hit-sound, customFrameFx, customHitFx, custom-type — display-only |
- **FK out**: `f0 → damage.entry_id`.

#### `damage` (738 rows)

- **Represents**: variable-arity damage profile (1..4 damage triples per
  entry). Variable layout — the count is in `f1`.
- **Sample**: `1 = f0=340 f1=1 f2=1 f3=1 f4=100.0 f5=0 …`
- **Field shape** (TinNS `definitions/Damage.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | (skipped) | — (TinNS does nothing) |
  | f1 | 3 | `mDamageNum` | number of damage tuples N (1..4) |
  | f2 + 3·k | 4..15 | `mDamageValue[k]` | int |
  | f3 + 3·k | 5..16 | `mDamageEffect[k]` | int |
  | f4 + 3·k | 6..17 | `mDamageType[k]` | int (PIE/FIR/ENR/POR/XRR/PSI — see fractions damage codes) |
- **FK in**: `shots.f0`, `ammo.f0`, `armor` (mitigation matches damage type).

#### `armor` (377 rows)

- **Represents**: armor stats per item — per-damage-type mitigation values.
- **Sample**: `1 = f0=1 f1=1.0 f2=4 f3=2201 f4=20.0 f5=2202 f6=20.0 …`
- **Field shape** (no clean TinNS parser — derived from sample shape):

  | f | meaning |
  |---|---|
  | f0 | → `items.entry_id` (parent item) |
  | f1 | weight / factor (float) |
  | f2 | numEntries N (count of mitigation triples) |
  | f3 + 2·k | itemmod / damage-type id |
  | f4 + 2·k | mitigation value (float) |

  Variable arity capped at 8 entries (f3..f18).
- **FK out**: `f0 → items.entry_id`.

#### `implants` (271 rows)

- **Represents**: implant — base stats + skill/subskill modifiers.
- **Field shape** (TinNS `definitions/Implants.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mType` | implant slot type |
  | f1 | 3 | `mDuration` | int (duration/durability) |
  | f2 | 4 | `mChangeNum` | N modifier triples (≤ 8) |
  | f3 + 3·k | 5..28 | `mChangeType[k]` | subskill/skill target id |
  | f4 + 3·k | 6..29 | `mChangeScale[k]` | float magnitude |
  | f5 + 3·k | 7..30 | `mChangeTarget[k]` | int target qualifier |
- **FK out**: `mChangeType` slots → `subskill.entry_id` (or `skills.entry_id`).

#### `drugs` (233 rows)

- **Field shape** (TinNS `definitions/Drugs.cxx`):

  Same triple-array structure as `implants`, with a leading `mIndex` and
  `mType` (Ceres f0=mType, f2=duration, f3=mChangeNum, then triples).
- **FK out**: `mChangeType` slots → `subskill.entry_id`.

#### `actionmod` (109 rows)

- **Represents**: per-action subskill multipliers used by combat/skill
  resolution (action modifier curves).
- **Field shape** (TinNS `definitions/ActionMod.cxx`):

  | f | meaning |
  |---|---|
  | f0 | `mStartValue` (float baseline) |
  | f1 | `mNumOfSsq` count (≤ 8) |
  | f2 + 2·k | `mSsqId[k]` → `subskill.entry_id` |
  | f3 + 2·k | `mModFactor[k]` float |

#### `charaction` (32 rows)

- **Represents**: character-action effects (sprint, jump, sit, …) and
  their per-subskill modifiers.
- **Field shape** (TinNS `definitions/CharacterActions.cxx`):

  | f | meaning |
  |---|---|
  | f0 | `mNumOfSsq` (≤ 8) |
  | f1 + 2·k | `mSsqId[k]` → `subskill.entry_id` |
  | f2 + 2·k | `mModFactor[k]` float |

#### `itemmod` (223 rows)

- **Represents**: item modifiers (sharpened, weakened, masterwork, …)
  applied to weapons.
- **Field shape** (TinNS `definitions/ItemMod.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mType` | itemmod type |
  | f1 | 3 | (skipped) | — |
  | f2 | 4 | `mChangeNum` (≤ 4) | |
  | f3 + 3·k | 5..16 | `mChangeTarget[k]` | int |
  | f4 + 3·k | 6..17 | `mChangeValue[k]` | float |
  | f5 + 3·k | 7..18 | `mChangeScale[k]` | float |
  | f15 | 17 | `mName` | string ("weakened", "sharpened", …) |

#### `itemres` (640 rows)

- **Represents**: item restrictions — minimum subskill values required to
  equip / use this item.
- **Field shape** (TinNS `definitions/ItemRes.cxx`):

  | f | meaning |
  |---|---|
  | f0 | `mNumRestrictions` count (≤ 6) |
  | f1 + 2·k | `mSsqId[k]` → `subskill.entry_id` |
  | f2 + 2·k | `mMinValue[k]` int |
- **FK in**: items can reference itemres rows via NC2-specific field index
  (~`items.f10` — empirically 292/4398 items match by `items.f10 →
  itemres.entry_id`).

#### `itemcontainer` (347 rows)

- **Represents**: NPC loot / vendor container — 6-slot list of items with
  drop chances.
- **Sample**: most rows are identical defaults — entries 0..N=2 are
  zeros/single-slot.
- **Field shape** (TinNS `definitions/ItemContainer.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mNumItemsAtOnce` | how many to draw per spawn |
  | f1 | 3 | `mRespawnTime` | seconds |
  | f2 | 4 | `mNumItems` | slot count (≤ 6) |
  | f3 + 3·k | 5..22 | `mItemId[k]` | `>0` → `items.entry_id`; `<0` → `items.mItemGroupID` (group) |
  | f4 + 3·k | 6..23 | `mQuality[k]` | float ≤ 1 (% quality) |
  | f5 + 3·k | 7..24 | `mChance[k]` | int (weight, NOT %) |
- **FK out**: `f3+3k → items.entry_id` (or items group id when negative).
- **FK in**: `npc.f10` (loot).

#### `trader` (309 rows)

- **Represents**: per-NPC vendor inventory — pairs of `(itemId, priceScale)`.
- **Field shape** (TinNS `definitions/Trader.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mType` | trader category |
  | f1 | 3 | `mMaxWealth` | float (cash pool) |
  | f2 | 4 | `mWealthRespawn` | float (regen rate) |
  | f3 | 5 | `mQuality` | int (default item quality on sale) |
  | f4 + 2·k | 6..(6+2·N) | `mItemId[k]` | → `items.entry_id` |
  | f5 + 2·k | 7..(7+2·N) | `mItemPriceScale[k]` | float (negative ids = item-group ref) |
- **FK in**: `world_npcs.trade_id → trader.entry_id` (per-spawn vendor
  override).

#### `recycles` (227 rows)

- **Represents**: recycling recipes — which part-items decompose into a
  result item.
- **Field shape** (TinNS `definitions/Recycles.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mResultItemId` | → `items.entry_id` (the OUTPUT, also keys the row) |
  | f1 | 3 | `mBuildTime` | seconds |
  | f2 | 4 | `mNumParts` | N (≤ 8) |
  | f3 + k | 5..12 | `mPartId[k]` | → `items.entry_id` (the INPUT parts) |
- **FK out**: `f0 → items.entry_id`, `f3..f10 → items.entry_id`.

#### `blueprintpieces` (22 rows)

- **Represents**: blueprint puzzle pieces (research minigame).
- **Field shape** (TinNS `definitions/BluePrintPieces.cxx`):

  | f | meaning |
  |---|---|
  | f0 | `mMaxPieceNum` |
  | f1 | `mPieceNum` (count, ≤ 20) |
  | f2..f21 | `mPieceId[]` array — likely → `items.entry_id` (research-required parts) |

#### `itemplan` (448 rows)

- **Represents**: item construction plan — drives the construction
  mini-game / blueprint result composition. TinNS does not parse this
  def; semantics inferred from sample shape.
- **Sample**: `95 = f0=95 f1=0 f2=0 … f6=7 f7=218 f8=2 f9=222 …`
- **Field shape** (inferred):

  | f | meaning |
  |---|---|
  | f0 | result item id (mirrors entry_id) |
  | f1..f5 | five float params (build cost / multipliers) |
  | f6 | numParts |
  | f(2k+5), f(2k+6) | (partItemId, qty) pairs (k = 0..numParts-1) |
- **Open**: confirm against TinNS — no `ItemPlan.cxx` exists; this may be
  NC2-only.

### Character / skill family

#### `charkinds` (27 rows)

- **Represents**: player class template — skill caps, starting subskill
  levels, starting inventory. Drives character creation.
- **Sample**: `1 = "Engineer" f1=3 f2=1 f3=20 f4=100 f5=2 f6=35 f7=64 f8=1 …` (large row, 131+ fields per entry)
- **Field shape** (TinNS `definitions/CharacterKinds.{hxx,cxx}`):

  The layout is variable-arity, expressed by computed offsets based on
  `NumSkills`:

  ```
  f0           = mName (string)
  f1           = mType
  f2..f(3·NSk+1) = NSk × (start, max, grow) per skill
  f(...)..f(...) = 32 × (skillId|subskillId, points)   -- train points
  f(...)..f(...) = 16 × (subskillId, level)            -- start subskill levels
  f(...)         = starting money
  f(...)         = inventory item ids (variable length)
  ```

  With `NSk = 5` (STR/DEX/CON/INT/PSI):

  - f0=name, f1=type
  - f2..f16 = 15 entries (5 skills × 3) — (start, max, grow) triples
  - f17..f80 = 64 entries (32 × 2) — (skillId/subskillId, points)
  - f81..f112 = 32 entries (16 × 2) — (subskillId, level)
  - f113 = money
  - f114..f131 = inventory (id, count) pairs
- **TinNS source**: `tinns/tinns/gameserver/definitions/CharacterKinds.{hxx,cxx}`.
- **FK out**: skill / subskill ids → `skills.entry_id` / `subskill.entry_id`; inventory ids → `items.entry_id`.
- **FK in**: `player_characters.class → charkinds.entry_id`.

#### `characters` (2522 rows)

- **Represents**: character visual templates — both PC archetypes and NPC
  appearance prefabs.
- **Sample**: `0 = "private male" f1=-1 f2=0 f3=1000 f4=1010 f5=1020 f6=0 f7=0 f8=700 f9=800 f10=900 …`
- **Field shape** (TinNS `definitions/Characters.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mName` | visual archetype name ("private male", "tank male", "SSCORPA", …) |
  | f1 | 3 | `mModel` | model id (-1 = use head/torso/legs separately) |
  | f2 | 4 | (skipped) | — |
  | f3 | 5 | `mHead` | head model id |
  | f4 | 6 | `mTorso` | torso model id |
  | f5 | 7 | `mLegs` | legs model id |
  | f6 | 8 | `mColor` | color id |
  | f7 | 9 | `mBrightness` | brightness id |
  | f8..f14 | (NC2 extension) | textures | texture indices used by NC2 CreateCharacter (head/torso/legs/hair/beard) — see `player_characters.texture_*` columns |
- **FK out**: head/torso/legs/texture ids → `modeltextures.entry_id` (via
  div-10 / floor mapping for some assets).
- **FK in**:
  - `player_characters.model_head/torso/leg/hair/beard → characters.entry_id`
  - `npc.f0 → characters.entry_id` (NPC types reference visual prefabs)

#### `skills` (5 rows)

- **Represents**: master skill list. Exactly 5 rows — STR/DEX/CON/INT/PSI.
- **Sample**: `1 = "STRENGTH" "STR" f2=5 f3=1 f4=2 f5=3 f6=22 f7=6` (5 subskill ids attached).
- **Field shape** (TinNS `definitions/Skills.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mName` | full name |
  | f1 | 3 | `mShortName` | 3-letter abbrev |
  | f2 | 4 | `mNumSubSkills` | N |
  | f3..f(2+N) | 5..(4+N) | `mSubSkills[]` | → `subskill.entry_id` |
- **FK out**: `f3..` → `subskill.entry_id`.

#### `subskill` (33 rows)

- **Represents**: subskill master list. 33 entries (NC2 retail).
- **Sample**: `1 = "melee combat" "M-C" f2=4.0 f3=1 f4=1 f5=0 …`
- **Field shape** (TinNS `definitions/SubSkills.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mName` | full name |
  | f1 | 3 | `mShortName` | 3-letter abbrev |
  | f2 | 4 | `mStrengthenFactor` | float — leveling-curve multiplier |
  | f3 | 5 | `mNumActionModifiers` N | |
  | f4..f(3+N) | 6..(5+N) | `mActionModifiers[]` | → `actionmod.entry_id` |
- **FK in**: dozens — `skills.fN`, `implants.fN`, `drugs.fN`, `actionmod.fN`,
  `itemres.fN`, `charaction.fN`, etc.
- **Application**: `player_characters` has 33 named columns (`mc`, `hc`,
  `tra`, …, `wpw`) — one per subskill. The HUD pool maxes
  (HP/PSI/STA) are derived locally on the client from HLT/ATL/END/PSU
  subskills (per `hud_pool_path_confirmed.md` memory).

#### `modeltextures` (97 rows)

- **Represents**: 90-element texture LUT — per-archetype texture mapping
  (each entry is a 90-field table of texture ids).
- **Sample**: `0 = f0=1000 f1=1001 f2=1002 … f89=-1`
- **Field shape**: 90 sequential texture ids; `-1` means "unused slot".
- **FK in**: `characters.fN` and `player_characters.texture_*` reference
  these indirectly.

### NPC / spawn family

#### `npc` (2534 rows)

- **Represents**: NPC type / class — every NPC in the world inherits its
  stats / dialog / loot / weapon / faction from one of these rows.
- **Sample**: `1 = f0=1 f1=0 f2=3 f3=120 f4=15168 f5=256.0 f6=1651 f7=3.0 f8=15.0 f9=1 f10=0 f11=0 f12=" " f13=-103 f14=0.0 …f20="NCPD" f21="WCOP" f22=" " f23=6145 f24=0`
- **Field shape** (TinNS `gameserver/NPC.cxx` + `definitions/Npc.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mModel` | model id → `characters.entry_id` (visual prefab) |
  | f1 | 3 | (gap) | TinNS skips |
  | f2 | 4 | `mNGT` | NPC-group-type (combat class) |
  | f3 | 5 | `mGender` | int |
  | f4 | 6 | `mFaction` | → `fractions.entry_id` |
  | f5 | 7 | `mHealth` | int |
  | f6 | 8 | `mArmorId` | → `npcarmor.entry_id` |
  | f7 | 9 | `mWeaponId` | → `items.entry_id` (the weapon item) |
  | f8 | 10 | `mDialogScript` | string (lua script base — e.g. "WCOP_DIALOG") |
  | f9 | 11 | `mCombat` | int (combat behaviour mode) |
  | f10 | 12 | `mLoot` | → `itemcontainer.entry_id` |
  | f11 | 13 | `mMovementEnd` | int |
  | f12 | 14 | `mFunctionType` | int (NPC function class — guard, vendor, mission, …) |
  | f13 | 15 | `mModelScaling` | float |
  | f14 | 16 | `mMoneyLoose` | int (cash drop) |
  | f15 | 17 | `mSkillScale` | float |
  | f16 | 18 | `mStandardScript` | string (default lua script identifier — joins `scripts.f0`) |
  | f17 | 19 | `mStandardParameter` | string |
  | f18 | 20 | `mMass` | int |
  | f19..f21 | (NC2 extra) | display strings | short-name (`"NCPD"`, `"WCOP"`), token tag, … |
  | f22 | 24 | `mFlags` | bitfield |
  | f23..f24 | (NC2 extra) | unknown | — |
- **TinNS source**: `tinns/tinns/gameserver/NPC.cxx:DEF_Load`, `definitions/Npc.cxx`.
- **Java consumers**: `NpcSpawnManager.java`, `NPC.java`, `WorldNPCInfo.java`.
- **FK out**: `f0 → characters`, `f4 → fractions`, `f6 → npcarmor`,
  `f7 → items`, `f10 → itemcontainer`, `f16 → scripts.f0` (string match).
- **FK in**: `world_npcs.npc_type_id → npc.entry_id`,
  `npcgroupspawn.fN slots → npc.entry_id`.

#### `npcarmor` (1145 rows)

- **Represents**: 7-slot damage mitigation array per "armor profile".
- **Sample**: `1 = f0=0.0 f1=750.72 f2=750.72 f3=-1.0 f4=794.88 f5=794.88 f6=-1.0 f7=0.0 f8=0.0 f9=0.0`
- **Field shape** (TinNS `definitions/NpcArmor.cxx`):

  | f | meaning |
  |---|---|
  | f0..f6 | 7 mitigation values (per damage type, by index — same order as `damage.mDamageType` axes) |
  | f7..f9 | reserved (always 0.0) |

  `-1.0` means "no mitigation for this damage type".
- **FK in**: `npc.f6`.

#### `npcgroupspawn` (560 rows)

- **Represents**: group spawn template — defines an 8-slot list of NPC
  types each with its own AI script and spawn chance. Used by zone
  spawners to roll a random group composition.
- **Sample**: `1 = f0=1 f1=8 f2=410 f3="SSCORPA" f4=0 f5=0 f6=10 f7=411 f8="SSCORPA" …` (8 × 5-tuple)
- **Field shape** (TinNS `definitions/NpcGroupSpawn.{hxx,cxx}`):

  | f | meaning |
  |---|---|
  | f0 | `mIgnoreNearPC` (0/1) |
  | f1 | `mNumNpc` count N (≤ 8) |
  | f2+5k | `mNpcType[k]` → `npc.entry_id` |
  | f3+5k | `mScript[k]` string |
  | f4+5k | `mScriptParameter[k]` string |
  | f5+5k | `mFunctionValue[k]` int |
  | f6+5k | `mSpawnChance[k]` int weight |
- **FK out**: per-slot type → `npc.entry_id`; script string → `scripts.f0`.

#### `npcloot` (2 rows)

- **Represents**: loot meta — variable per-NPC item-drop chains. NC1
  fragment; NC2 retail uses `itemcontainer` via `npc.f10` instead.
- **Field shape**: 18 fields (`f0..f17`), variable layout, see TinNS
  `definitions/NpcLoot` (no .cxx in tree). Only 2 entries — likely
  legacy / unused in NC2.
- **Status**: low priority; if you need NPC loot use `npc.f10` →
  `itemcontainer`.

### Mission / dialog family

#### `missionbase` (1639 rows)

- **Represents**: mission template — source (giver), target list,
  reward, faction restriction.
- **Sample**: `335 = f0=-1 f1=1 f2=11656 f3=0 f4=1 … f31=5000 f32=0 f33=5000 f34=24 f35=0 f36=8 f37=1 f38=0`
- **Field shape** (TinNS `definitions/Mission.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mSourceId` | > 0 = NPC type id (`npc.entry_id`); < 0 = terminal id |
  | f1 | 3 | `mStartDialog` | string → `scripts.f0` (the lua dialog identifier) |
  | f2 | 4 | `mDescTextId` | int (text-table id for description) |
  | f3..f14 | 5..16 | 4 × (npcType, npcDialog, dialogStartState) | NPCs involved in the mission |
  | f15..f30 | 17..32 | 4 × (targetType, value1, value2, value3) | mission targets |
  | f31 | 33 | `mEndMoney` | reward NC |
  | f32 | 34 | `mEndXp` | reward XP |
  | f33 | 35 | `mMaxTime` | seconds |
  | f34 | 36 | `mDifficulty` | int |
  | f35 | 37 | `mMinFactionValue` | int (→ implicit `fractions.entry_id` qualifier) |
  | f36 | 38 | `mPoints` | int |
  | f37 | 39 | `mFlags` | bitfield — bit 0..3 = "NPC0..3 no-search" |

  **Target type taxonomy** (from TinNS comments in `Mission.cxx` epilogue):
  - `1` kill NPC | `2` kill NPC type | `3` dialog trigger | `4` kill NPC
    type range | `5` dialog trigger + counter | `6` conquer outpost |
    `7` conquer outpost + counter | `8` conquer + hold outpost |
    `9` kill player | `10` loot NPC | `11` loot NPC type
- **FK out**: `f0 → npc.entry_id` (when > 0); per-target `npcType` slots
  → `npc.entry_id`; `f1` string → `scripts.f0`.

#### `scripts` (809 rows)

- **Represents**: lua script registry — every dialog / mission / NPC
  behaviour script the client AND server can resolve by identifier.
- **Sample**: `0 = "DIALOGHEADER" "scripts/lua/dialogheader.lua"`,
  `2 = "S_TEST" "scripts/lua/s_test.lua" f2="DIALOGHEADER"`.
- **Field shape** (TinNS `definitions/Scripts.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mIdentifier` | ASCII token (uppercase; what other defs reference) |
  | f1 | 3 | `mLuaFile` | path relative to client root |
  | f2 | 4 | `mScriptHeader` | the "parent" header identifier (re-entrant include chain) |
- **FK in**: `npc.f8` (dialog), `npc.f16` (standard script);
  `npcgroupspawn.fN` (per-slot script string); `missionbase.f1`
  (start-dialog identifier).

### Faction / outpost family

#### `fractions` (22 rows)

- **Represents**: faction master list (TinNS keeps the NC2 misspelling).
  22 entries: 0 = "Soul Light" (special sentinel), 1..21 = real factions.
- **Sample**: `1 = "City Administration" f1=1 f2=1 f3=845 f4=1024 f5=0 …` (26 fields per entry — `f0..f25`).
- **Field shape** (TinNS `definitions/Factions.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mName` | display name |
  | f1 | 3 | `mStartValue` | int (faction relation default) |
  | f2 | 4 | `mAffected` | bool (does Soullight track this faction?) |
  | f3 | 5 | `mSL` | int (Soullight value) |
  | f4..f25 | 6..(6+NUMFACTIONS) | `mRelations[]` | one int per other faction (faction-to-faction relation matrix) |
- **FK in**: `npc.f4` (per-NPC faction); `player_characters.faction`;
  `outposts.f1` (default owning faction); `appartements.f12` (base apt faction).

#### `outposts` (37 rows)

- **Represents**: outpost — capturable PvP installation, ties into faction warfare.
- **Sample**: `2006 = "Simmons Factory" f1=1 f2=5 f3=1.2 f4=1.2 f5=10 f6=6 f7=7 f8=106 f9=107 …`
- **Field shape** (TinNS `definitions/Outposts.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mName` | display name |
  | f1 | 3 | `mType` | int (outpost class — research lab, mine, factory, …) |
  | f2 | 4 | `mStandardFaction` | → `fractions.entry_id` (default owner) |
  | f3 | 5 | `mRevenue` | float (per-hour income) |
  | f4 | 6 | `mConquestReward` | float |
  | f5 | 7 | `mMaxSecurity` | int |
  | f6..f13 | 8..15 | `mInfluenceZone[8]` | → 8 zone ids the outpost influences (joins `worldinfo.entry_id`) |

### Misc support defs

#### `appartements` (232 rows)

- **Represents**: purchasable apartment layouts — name, worldname, value,
  placepoints, owning faction (for base apartments).
- **Sample**: `1 = "small appartement" "apps/plaza_app_1" f2=0 f3=8 f4=208 f5=209 …`
- **Field shape** (TinNS `definitions/Appartements.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mName` | display name |
  | f1 | 3 | `mWorldName` | base-path (`apps/<basename>`) — joins `world_defs.path` via `path LIKE f1 || '%'` |
  | f2 | 4 | `mValue` | NC cost |
  | f3 | 5 | `mPlaceCount` | placement-points count |
  | f4..f11 | 6..13 | `mPlace[8]` | 8 placement-point ids (→ `appplaces.entry_id`) |
  | f12 | 14 | `mFaction` | → `fractions.entry_id` (for base apartments only) |

#### `weather` (169 rows)

- **Field shape** (TinNS `definitions/Weather.cxx`):

  | f | meaning |
  |---|---|
  | f0 | `mSectionId` |
  | f1 | `mNumWeathers` (≤ 8) |
  | f2+2k | `mWeatherId[k]` |
  | f3+2k | `mDuration[k]` seconds |

#### `gameplaysettings` (217 rows)

- **Represents**: server tunables (key/value table).
- **Sample**: `1 = "Lawenforcer Enabled" 1.0`, `2 = "Skill Gain Factor" 0.5`, `3 = "Skill Cap Factor" 1.0`.
- **Field shape**: `f0=name(string), f1=value(float)`.

#### `routemenu` (23 rows) / `routesubmenu` (222 rows)

- **Represents**: in-game route-finder UI menu tree.
- **routemenu fields**: `f0=label, f1=type, f2=startSubmenuId, f3=endSubmenuId, f4..f5=floats`.
- **routesubmenu fields**: `f0=label, f1=int, f2=int (parent ref)`.
- **No TinNS parser** — pure UI atlas data.

#### `respawn` (82 rows)

- **Represents**: GenRep (genetic replicator) respawn locations.
- **Field shape** (TinNS `definitions/Respawn.cxx`):

  | f | TinNS Idx | name | meaning |
  |---|---|---|---|
  | f0 | 2 | `mWorldID` | → `world_defs.id` |
  | f1 | 3 | `mEntityID` | insertion-point index in destination zone |
  | f2 | 4 | `mHazardLevel` | int |
  | f3 | 5 | `mName` | display name (e.g. "PLAZA: TYPHERRA MEMORIAL") |
  | f4 | 6 | `mFlag` | string flag/category |
- **FK out**: `f0 → world_defs.id`.
- **FK in**: `worldmodel(f2=6).f3 → respawn.entry_id` (the GenRep
  worldmodel functionValue keys the respawn point).

#### `effects` (1 row)

- **Represents**: one-off particle/effect script registry. NC2 retail has
  only the single demo entry `"BLITZBUMMBEB"` carrying a procedural
  particle script. Not used by the server.

#### `hack` (1 row)

- **Represents**: hacking-skill table. Single stub entry — TinNS
  `Hack.cxx` only reads `mIndex` (it parses but never reads back).
  Effectively unused.

---

## Cross-def relationship chains

These are the canonical multi-table walks. Each is named, declared in the
order the engine queries it, and marked with the Java consumer that drives
it.

### Chain A — Portal / zone-change (driven by `UseItem.java`, `PortalResolver.java`)

```
world_objects(world_path, object_id)
  └─► worldmodel(entry_id = worldmodel_id)
        ├ f1 (UseFlags) & 8 ≠ 0   →  CHAIR  (sit, no zone change)
        ├ f1 (UseFlags) & 8 == 0
        │     └ f2 (functionType) ∈ {15,18,20,29}
        │       └─► appplaces(entry_id = worldmodel.f3)
        │             ├ f1 = ExitWorldID
        │             ├ f2 = ExitWorldEntity (client insertion key)
        │             └ f3 = SewerLevel field (TinNS debug only)
        │     └ f2 == 6  →  GenRep
        │       └─► respawn(entry_id = worldmodel.f3)
        │             ├ f0 → world_defs.id
        │             └ f1 = entity (client insertion key)

then for actual world load:
worldinfo(entry_id = ExitWorldID)
  └ f2 = alternateDatFile  → if non-empty: use this .dat
                           → else: use world_defs(id = ExitWorldID).path

Wire packet emitted: 0x03/0x1f/<localId>/0x38 [04][entityType][Location u32][Entity u16]
entityType = (functionType == 20 || functionType == 29) ? 1 : 0
```

End-to-end example: see [§Worked example](#worked-example-portal-worldmodel139-end-to-end).

### Chain B — Item creation / usage

```
items(entry_id = TypeId)            -- canonical item row
  ├ f1 = items.mModel                (visual)
  ├ f2 = items.mType ∈ {1=weapon, 2=armor, 3=ammo, 4=implant, 5=drug, 6=container, ...}
  │
  ├ if f2 == 1 (weapon):
  │     └─► weapons(f1 == items.entry_id) — weapon stats
  │           └─► ammo(entry_id ∈ weapons.f26..f33)
  │                 ├─► shots(entry_id = ammo.f1)
  │                 │     └─► damage(entry_id = shots.f0)
  │                 └─► damage(entry_id = ammo.f0)
  │
  ├ if f2 == 2 (armor):
  │     └─► armor(f0 == items.entry_id)
  │           └─► (per-damage-type mitigation by slot, joins damage by type code)
  │
  ├ if f2 == 4 (implant):
  │     └─► implants(entry_id == items.entry_id)  -- typical co-key convention
  │           └─► subskill(entry_id ∈ implants.fN) — modifier targets
  │
  └ equip-check:
        └─► itemres(entry_id) — items.f10/f17 reference the restriction row
              └─► subskill(entry_id ∈ itemres.fN) — required min subskill values
```

Recycling:

```
recycles(entry_id = output_item)  -- key IS the result item id
  ├ f0 = mResultItemId → items.entry_id
  └ f3..f10 → items.entry_id  (part items)
```

### Chain C — NPC spawn / behaviour

```
world_npcs(world_path, npc_id, npc_type_id, trade_id, actor_name, …)
  ├ npc_type_id → npc(entry_id)
  │     ├ f0 (model)   → characters(entry_id)
  │     ├ f4 (faction) → fractions(entry_id)
  │     ├ f6 (armorId) → npcarmor(entry_id)
  │     ├ f7 (weaponId) → items(entry_id)
  │     ├ f8 (dialogScript) → scripts(f0 == this string)
  │     │                          └ scripts.f1 = lua file path
  │     ├ f10 (loot) → itemcontainer(entry_id)
  │     │                  └ itemcontainer.f3+3k → items(entry_id)
  │     └ f16 (standardScript) → scripts(f0 == this string)
  │
  ├ trade_id → trader(entry_id)            -- per-spawn vendor override
  │              └ trader.f4+2k → items(entry_id)
  │
  └ actor_name → SCRIPTEDPLAYER ctor script token (no DB join — read by client)

Group spawn flavour (procedural):
npcgroupspawn(entry_id)
  └ f2+5k slots → npc(entry_id) + script string → scripts(f0)
```

### Chain D — Mission

```
missionbase(entry_id)
  ├ f0 (sourceId)         → npc(entry_id) (if > 0) OR terminal id (if < 0)
  ├ f1 (startDialog)      → scripts(f0 == this string)
  │                            └ scripts.f1 = lua file
  ├ f3,f6,f9,f12 (npcType[0..3]) → npc(entry_id)
  ├ f15+4k (targetType)    enum: 1=killNPC, 2=killNPCType, 3=dialogTrigger, ...
  └ f35 (minFactionValue) → implicit fractions qualifier
```

### Chain E — Character creation

```
player_characters(class)
  └─► charkinds(entry_id)
        ├ skill-info triples       → skills(entry_id)
        ├ subskill train pts       → subskill(entry_id)
        └ starting inventory ids   → items(entry_id)

player_characters(model_head, model_torso, model_leg, model_hair, model_beard)
  └─► characters(entry_id) (visual prefab)
        └─► modeltextures(entry_id ≈ f3/10) — texture LUT

player_characters(faction)
  └─► fractions(entry_id)

player_characters(location)
  └─► world_defs(id)        -- and via 1:1 join → worldinfo(entry_id)
```

### Chain F — Outpost / apartment

```
outposts(entry_id)
  ├ f2 (standardFaction) → fractions(entry_id)
  └ f6..f13 (influenceZone[8]) → worldinfo(entry_id)

appartements(entry_id)
  ├ f1 (worldName)  ≈ world_defs.path component
  ├ f4..f11 (place[8]) → appplaces(entry_id)
  └ f12 (faction)   → fractions(entry_id)
```

### Chain G — Subskill & action lookup (combat resolution)

```
actionmod(entry_id) — referenced by:
  ├ subskill.fN (charaction modifiers)
  ├ charaction.fN (per-action skill modifiers)
  └ (combat code computes effective skill via these modifiers)
```

---

## Open questions / unmapped defs

Defs / fields that need more RE. Marked with confidence level.

### High priority

1. **`worldinfo.f3` and `f4`** — sample shows enums (`16, 0, 1, 3, …`).
   `f1` is the zone class (1=city, 3=dungeon, 6=sewer, 16=indoor). `f3`
   and `f4` semantics are NC2-only (TinNS only reads `f0=mName, f1=type,
   f2=datFile, f3=flags`). The non-zero `f3` values cluster around 1 and
   16 — likely a hazard / outdoor flag.
2. **`worldinfo.f2` override is not wired into Ceres-J.** The alternate
   `.dat` path is documented (and used by the live client) but
   `WorldManager.java` only reads `world_defs.path`. Result: dungeon
   variants resolve to the wrong physical .dat unless added by hand to
   `world_defs`. Track via `worldinfo` → `world_defs` cross-check.
3. **`items.f8..f10` ambiguity.** The sampled row `items[1] f8=0.95` is
   a float, but TinNS treats this index as `mSizeX` (int). NC2 has
   shuffled fields — `ItemInfo.java` reads `tokens[10] = weight` and
   `tokens[21..22] = invSizeX/Y`, suggesting the NC2 .def shifts the
   layout. Needs a clean field-by-field audit against a single sample row.
4. **`worldspawnpoints` schema** — 1543 rows, no TinNS parser. The first
   4 fields shadow `worldmodel`. Likely a respawn-placement companion
   table — confirm via cross-reference to `respawn.def`.

### Medium priority

5. **`damage.f0` semantics** — currently flagged as an items.entry_id ref
   (191/738 hits) but TinNS marks `case 2` as skipped. May be a sound
   index reused as item-id by coincidence.
6. **`npcloot`** — only 2 rows, NC1-legacy. Confirm whether NC2 uses any
   of these rows at all; if not, treat the def as deprecated.
7. **`itemplan` exact schema** — no TinNS parser, semantics inferred from
   sample shape. Verify via construction-minigame trace.
8. **`maps` field layout beyond f9..f40** — the alternating `(worldId,
   name)` pairs run to `f39`; needs confirmation that exactly 16 pairs
   exist per row (32 fields → f9..f40 = 32 slots).

### Low priority

9. **`gameplaysettings`** has 217 entries — only ~10 are referenced by
   any current Ceres-J code. The full list of tunables (and their effect
   on combat / skill gain / faction shifts) is undocumented.
10. **`effects`** — 1 row, NC1-legacy, never read at runtime. Safe to
    ignore.
11. **`hack`** — 1 row, parser is a stub. Hack mini-game ties via
    `worldmodel.f4/f5` (hack difficulty/penalty) but the table itself is
    inert.
12. **`routemenu` / `routesubmenu`** — UI atlas, no functional impact.
    Documented for completeness.
13. **`weather`** linking to specific zones — `f0=mSectionId` joins what
    table? Likely `world_defs.id` slice or a sector-band index. Audit
    against `WorldWeather` packet (`docs/protocol/packets/udp_s2c_03_2f.md`).

---

## Index of useful queries

Reusable SQL snippets — paste into
`docker exec neocron-postgres psql -U ceres -d ceres -c "<sql>"`.

### Portal-chain walk

Resolve every zone-change actor in a given zone:

```sql
SELECT
  wo.world_path, wo.object_id,
  wo.worldmodel_id,
  (SELECT fields->>'f0' FROM client_defs WHERE def_name='worldmodel'
     AND entry_id=wo.worldmodel_id) AS wm_name,
  (SELECT (fields->>'f2')::int FROM client_defs WHERE def_name='worldmodel'
     AND entry_id=wo.worldmodel_id) AS function_type,
  (SELECT (fields->>'f3')::int FROM client_defs WHERE def_name='worldmodel'
     AND entry_id=wo.worldmodel_id) AS function_value,
  (SELECT (a.fields->>'f1')::int
     FROM client_defs a
     WHERE a.def_name='appplaces'
       AND a.entry_id = (SELECT (fields->>'f3')::int
                           FROM client_defs
                           WHERE def_name='worldmodel'
                             AND entry_id=wo.worldmodel_id)) AS exit_world_id
FROM world_objects wo
WHERE wo.world_path = 'worlds/plaza/pak_plaza_p1.dat'
  AND wo.worldmodel_id IN (
        SELECT entry_id FROM client_defs
        WHERE def_name='worldmodel'
          AND (fields->>'f2')::int IN (15, 18, 20, 29)
      )
ORDER BY wo.object_id;
```

### Find chairs in a zone

```sql
SELECT wo.object_id, cd.entry_id AS worldmodel_id, cd.fields->>'f0' AS model_name,
       (cd.fields->>'f1')::int AS use_flags
FROM world_objects wo
JOIN client_defs cd
  ON cd.def_name='worldmodel' AND cd.entry_id = wo.worldmodel_id
WHERE wo.world_path = 'worlds/plaza/pak_plaza_p1.dat'
  AND (cd.fields->>'f1')::int & 8 = 8;
```

### Resolve an NPC's full chain (npc_type → faction, armor, weapon, loot)

```sql
SELECT
  n.entry_id              AS npc_type,
  n.fields->>'f8'         AS dialog_script,
  (SELECT fields->>'f0' FROM client_defs
    WHERE def_name='fractions' AND entry_id=(n.fields->>'f4')::int)  AS faction_name,
  (SELECT fields->>'f0' FROM client_defs
    WHERE def_name='items'      AND entry_id=(n.fields->>'f7')::int)  AS weapon_name,
  (n.fields->>'f6')::int                                              AS armor_id,
  (n.fields->>'f10')::int                                             AS loot_container
FROM client_defs n
WHERE n.def_name='npc'
  AND n.entry_id = 100;
```

### Recycling: what does this part recycle into?

```sql
SELECT r.entry_id AS result_item, r.fields->>'f0' AS result_id, r.fields->>'f1' AS build_time
FROM client_defs r
WHERE r.def_name='recycles'
  AND (r.fields->>'f3')::int = 1000;   -- replace 1000 with the part item id
```

### Find all worlds with an alternate .dat path

```sql
SELECT entry_id, fields->>'f0' AS name, fields->>'f2' AS alt_dat
FROM client_defs
WHERE def_name='worldinfo'
  AND length(trim(fields->>'f2')) > 0;
```

### Probe a candidate FK across two defs

(Generic — drop in your `def_a`, `fX`, `def_b` triple)

```sql
SELECT
  COUNT(*)                                         AS hit_rows,
  COUNT(DISTINCT (a.fields->>'fX')::int)           AS distinct_keys
FROM client_defs a
JOIN client_defs b
  ON b.def_name = '<def_b>'
 AND b.entry_id = (a.fields->>'fX')::int
WHERE a.def_name = '<def_a>';
```

---

## Source / authority footnotes

Every claim about field semantics in this document cites at least one of:

- **TinNS** (`/home/javier/Documents/Projects/Neocron/tinns/tinns/gameserver/definitions/*.{hxx,cxx}`)
  — NC1 reference implementation that parses the same .def text format.
- **Ceres-J Java consumer** — file:line references in `src/main/java/`.
- **Live Postgres sample** — every per-def "Sample" line was produced by
  `SELECT entry_id, fields::text FROM client_defs WHERE def_name=… LIMIT N`.
- **`docs/zone_portal_params.md`** — authoritative for the portal chain.
- **`docs/CLIENT_DATA_IMPORT.md`** — for the import pipeline (worlds.ini, .dat).

If a field is marked "unknown" or "open" it is **not** in any of those
sources and requires a new RE pass (pcap analysis, Ghidra of
`neocronclient.exe`, or TinNS extension).
