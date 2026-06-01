# Ceres-J Item / Inventory Data Model — Demystified

> Reverse-engineered from source + the client `defs\items.def` (2026-06-01).
> Citations are `file:line` under `src/main/java/server/database/`.

## TL;DR — what the three current tables actually are

| table | rows | verdict |
|---|---|---|
| `items` | 28 | **LIVE** — item instances. But `tokens` is an opaque blob and `slot` is packed. |
| `item_defs` | 0 | **DEAD** — abandoned "schema v2" table, never populated. Item types load from the client file `defs\items.def` instead (`ItemInfoManager.java:18`). |
| `item_containers` | 0 | **DEAD** — containers are implicit, referenced only by `player_characters.{f2,qb,gogu}_inventory_cont_id` + `items.container_id`. |

`items.quality` exists but is **never read/written** by `ItemManager` (its SQL is `id, container_id, type_id, slot, flags, tokens`) — vestigial.

## 1. Item TYPE definitions live in a CLIENT file, not the DB

`defs\items.def` (PAK-packed in the client; decompressed copy under
`tools/tmp/.../Neocron2/defs/items.def`) holds one `setentry` line per type,
parsed by `ItemInfo`/`DefReader`. A quoted name is ONE token; trailing `|`/`;`
are stripped → 24 tokens (0–23). Fields the server actually reads
(`ItemInfo.java:20-33`):

| idx | field | meaning |
|---|---|---|
| 1 | `type_id` | def id — the FK target of `items.type_id` |
| 2 | `name` | display name (quoted) |
| 3 | `tech_level` | TL |
| 4 | `item_class` | weapon/usable/equip category (`ItemInfo.Type`) |
| 8 | `stackable` | `==1` |
| 10 | `weight` | float |
| 21 | `inv_size_x` | footprint width (cells) |
| 22 | `inv_size_y` | footprint height (cells) |

Tokens 5–7, 9, 11–20, 23 exist but the current parser ignores them (likely
model id / price / ammo+damage refs into `pak_weapons.def`/`pak_damage.def` —
**unresolved from this codebase**). Example:
```
setentry 1 "A&W Street Model Gun" 26 1 1 0 0 0 0 0.95 0 1 11 11 0 31 13 0 59 24 2 1 1 |
```
→ type 1, TL 26, class 1, weight 0.95, footprint 2×1.

## 2. The `tokens` bytea = a `short[17]` (the "black box", decoded)

Fixed 17 shorts, little-endian per short (`Item.java:389-411`, constants
`:41-57`). The live default `ff00 ff00 c800 c800 c800 c800 0000 0300 0500 00…`
(identical on all 28 items) decodes to:

| idx | field | default |
|---|---|---|
| 0 | `curr_cond` | 255 |
| 1 | `max_cond` | 255 |
| 2 | `damage` | 200 |
| 3 | `frequency` | 200 |
| 4 | `handling` | 200 |
| 5 | `range` | 200 |
| 6 | `clip_size` | 0 |
| 7 | `ammo_uses` | 3 |
| 8 | `stack_count` | 5 |
| 9 | `mod_slots` (total) | 0 |
| 10 | `mod_slots_used` | 0 |
| 11–15 | `mod1..mod5` (slot contents) | 0 |
| 16 | `constructor_char_id` | 0 |

It is fully decodable — just stored as raw bytea, which is why no SQL/tool can
edit it.

## 3. The `slot` int = packed position (`inventorypos`)

Mixed-radix, NOT bit-shift (`PlayerInventory.java:63,122`; decode
`Item.java:324-339`):
```
slot = slot_x + slot_y*256 + slot_index*65536
slot_index = slot / 65536 ;  slot_y = (slot%65536)/256 ;  slot_x = slot%256
```
- **F2 grid** (`PlayerInventory`, 8 wide): all three parts used.
- **GoGu** (`PlayerGogu`): same packing.
- **Quickbelt** (`PlayerQB`): **flat slot 0–46**, NOT packed.

⚠️ You must know the container KIND to interpret `slot` (no self-describing
flag). Live examples: `131076`→(idx2,x4,y0) F2; `7`→flat QB slot 7.

## 4. `flags` = ITEMFLAG_* bitmask (selects the wire layout)

`Item.java:10-18`: STACK=1, USES=2, WEAPON=4, SPELL=8, SLOTS=16, DOGTAG=32,
UGS=64, SIMPLE=128, CONSTED=256. `createNetworkInfoData` switches on the EXACT
flags value (`Item.java:123…211`), so only known combos emit bytes. Live data:
`4`=WEAPON (type 19 knife), `3`=USES|STACK (type 35), `8`=SPELL (type 100/101).

`PROPERTY_*` (40–48) and `CONTAINERPOS_*` (20–24) are runtime accessor enums
(not persisted). `PACKET_*` (60–63) pick the CharInfo section framing.

## 5. Wire serialization (must stay byte-identical through any refactor)

`createNetworkInfoData()` (`Item.java:118-218`) builds `[type:LE16] + flags-
dependent body`. Bodies by flags:
- **SIMPLE(128):** `02 02 [currcond][maxcond]`
- **USES|STACK(3):** `05 [ammo_uses:1][stack_count:LE32]`
- **SPELL(8)/melee WEAPON(4):** `23 00 06 [currcond][dmg][freq][handling][range][maxcond] 04 00 01 04 00 01`
- **USES|WEAPON(6):** `33 [ammo_uses] 06 [currcond][dmg][freq][handling][range][maxcond] 00 04 00 01 04 00 01`
- **SLOTS(22)/CONSTED(278):** stub — only `[type:LE16]`.

Fields reaching the wire: `type_id, curr_cond, max_cond, damage, frequency,
handling, range, ammo_uses, stack_count`. NOT emitted yet: clip_size, mods,
mod_slots, constructor. Section framing in `getItemInfoPacketData`
(`:220-298`): F2 `[len+3:LE16] 00 [x][y][body]`, QB `[len+2:LE16][slot:1] 00
[body]`, **GOGU returns null (unimplemented)**.

## 6. Target normalized schema (Fase 2)

Replaces `tokens` bytea + packed `slot` + the two dead tables:
- **`item_type`** ← from `defs\items.def` (§1): `type_id PK, name, tech_level,
  item_class, stackable, weight, inv_size_x, inv_size_y, def_raw`.
- **`item_container`** ← from `player_characters.*_cont_id`: `container_id PK,
  owner_char_id FK, kind` (0=F2 1=GoGu 2=QB 3=Box).
- **`item_instance`** ← from `items` + decoded tokens/slot: named columns
  `curr_cond, max_cond, damage, frequency, handling, range, clip_size,
  ammo_uses, stack_count, mod_slots, mod_slots_used, constructor_char_id,
  slot_index, slot_x, slot_y, flags, quality`.
- **`item_mod_slot`** ← tokens[11..15]: `(item_id, slot_no, mod_value)`.

`ItemManager` serializes the §5 wire from the named columns (byte-identical) and
re-packs `slot` on save. The `inventory_view` joins instance+type+container+char
into a human-readable inventory for inspection/editing.

### Open / flagged
- `quality` dead in code — revive or drop.
- GoGu wire + QB/GoGu ADDITEM unimplemented (`Item.java:243,272`).
- SLOTS/CONSTED wire is a stub — byte layout needs a retail pcap.
- def tokens 5–7,9,11–20,23 unmapped — keep `def_raw` so they're not lost.
