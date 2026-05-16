# Zone-Transition Portals — `params` Decode & Mechanism

Status: **decoded** (2026-05-16). Authoritative source: TinNS NC1 emulator, which
parses the *identical* `.dat`/world data format and already implements the full
zone-jump path. All file:line citations below are into
`/home/javier/Documents/Projects/Neocron/tinns/tinns/gameserver/`.

---

## TL;DR / Headline finding

**TRIGDOOR / TRIGDD / DDOOR / SDOOR `params` do NOT encode zone transitions.**
They are purely intra-zone door *mechanics* (open style, linked door half, move
type). There is no destination-world id anywhere in a door's `params`.

Zone transitions are driven by a completely separate object class: **static
"world-change" furniture actors** (rows in the `world_objects` table, not
`world_doors`). The destination is resolved through a 2-table indirection:

```
world_objects.worldmodel_id
   ──► worldmodel.def[worldmodel_id]  →  (functionType, functionValue)
        if functionType ∈ {15,18,20,29}  → it's a zone-change actor
   ──► functionValue indexes appplaces.def[functionValue]
        → (ExitWorldID, ExitWorldEntity, SewerLevel)
```

The server then sends the client a ChangeLocation message carrying
`(ExitWorldID, ExitWorldEntity, SewerLevel)`. **The server never computes the
destination XYZ.** The client resolves the spawn position locally from the
*entity / insertion-point index* against the destination zone's own `.dat`.

---

## 1. Door `params` field-by-field (NOT zone data)

Parsed by `PDoorTemplate::SetDoorParameters()` —
`DoorTemplate.cxx:75-93`. The comma-separated string is split into **at most 4**
integers `mDoorParameters[0..3]` (`DoorTemplate.hxx:23`).

`PDoorTemplate::SetDoorTypeName()` (`DoorTemplate.cxx:95-116`) sets two booleans
from the actor-type string only:

| actor_type | IsDoubleDoor | IsTriggeredDoor |
|------------|:---:|:---:|
| `DDOOR`    | yes | no  |
| `TRIGDD`   | yes | yes |
| `TRIGDOOR` | no  | yes |
| `SDOOR`    | no  | no  |
| `NBUTTON`  | no  | no  |

Field meaning (from `WorldDatStruct.hxx:64-67` comment + the only consumer,
`GetOtherDoorID()` at `DoorTemplate.cxx:60-63`):

| idx | field | meaning | confidence |
|-----|-------|---------|------------|
| 0 | param1 | open style: `2` = simple lateral move, `3` = frontal+lateral | from .dat comment, **stated as uncertain in TinNS itself ("?")** |
| 1 | param2 | **for double doors (DDOOR/TRIGDD): door_id of the OTHER half** in the *same* zone (intra-zone pairing) | HIGH — actively used by `GetOtherDoorID()` |
| 2 | param3 | varies (move sub-type / direction) | UNKNOWN — TinNS does not read index 2 |
| 3 | param4 | varies | UNKNOWN — TinNS does not read index 3 |

Only `mDoorParameters[1]` is ever consumed (the paired door-half id, and only
when `IsDoubleDoor`). Indices 0/2/3 are parsed but **never used anywhere** in
TinNS. There is no world id, no zone id, no GenRep index in door params.

Empirical confirmation from the Postgres `world_doors` dump (pepper_p3): DDOOR
pairs share matching param2 cross-references, e.g. door 9 `params=1,10,1,4`
↔ door 10 `params=1,9,1,0`; door 47 `2,48,2,2` ↔ door 48 `2,47,2,0`. param2 is
unambiguously the linked-half door_id. The lone `TRIGDOOR door_id=33
params=1,4,2` at (197,-1135) is **not** the pepper→citysewer portal — its params
follow the same mechanical pattern and its position does not match any sewer
entrance (those are at wm 380/382, see §5).

---

## 2. Authoritative zone-transition decode logic (TinNS)

### 2a. Door "use" dispatch — proves doors are NOT portals

`decoder/UdpUseObject.cxx:91-108` — when a door is used:

```cpp
const PDoorTemplate* tDoor = CurrentWorld->GetDoor( mRawItemID - 0x80 );
if ( tDoor ) {
  if ( tDoor->IsTriggeredDoor() )      // TRIGDOOR/TRIGDD
    BuildText100Msg(... 6 ...)         // just "needs a trigger" feedback
  else
    BuildDoorOpenMsg(..., IsDoubleDoor) // just animate the door open
}
```

No branch here changes the player's world. Triggered doors only emit a UI text
message; normal doors only broadcast an open-animation. **Door use never
zones.**

### 2b. The real zone-change path — furniture function-type switch

`decoder/UdpUseObject.cxx:341-475`. For a *furniture/static actor*, TinNS looks
up its `worldmodel.def` entry and switches on `GetFunctionType()`:

```cpp
case 18: // "WORLDCHANGEACTOR"
case 20: // "DATFILE WORLDCHANGE ACTOR"
case 29: //Underground Exit
{
  const PDefAppPlace* nAppPlace;
  if (functionType == 29)
    nAppPlace = GameDefs->AppPlaces()->GetDef( nChar->GetLocation() ); // UG exit: keyed by CURRENT world
  else
    nAppPlace = GameDefs->AppPlaces()->GetDef( tFurnitureModel->GetFunctionValue() );

  uint32_t Location = nAppPlace->GetExitWorldID();
  uint16_t Entity   = nAppPlace->GetExitWorldEntity();
  uint8_t  SewerLevel = (functionType==20 || functionType==29) ? 1 : 0;

  BuildChangeLocationMsg( nClient, Location, Entity, SewerLevel, mRawItemID );
}
```

`case 15` (HOLOMATCH EXIT, lines 399-432) uses the same
`appplaces → ExitWorldID/Entity` resolution.

`case 6` (GenRep, lines 343-373) is the *respawn* path: it sends
`BuildCharUseGenrepMsg(nClient, rawId, location, entity)` where entity comes from
`respawn.def` via `GameDefs->Respawns()->GetRespawnEntity(currentWorld,
linkedObjectId)` — relevant for death/GenRep, not for walking through a portal.

### 2c. worldmodel.def format (function type/value)

`definitions/WorldModels.cxx:7-40` →
`setentry <index> "<name>" <useFlags> <functionType> <functionValue> <hackDiff> <hackPenalty>`

Zone-change function types: **15** HOLOMATCH EXIT, **18** WORLDCHANGEACTOR,
**20** DATFILE WORLDCHANGE ACTOR (also sets SewerLevel=1), **29** Underground
Exit (keyed by current world). (Type 14 = HOLOMATCH ENTRANCE → AppPlace for the
holomatch case 15 lookup.)

### 2d. appplaces.def format (the destination table)

`definitions/AppartementPlaces.cxx:7-35` + `AppartementPlaces.hxx:7-25` →
`setentry <index> "<name>" <ExitWorldID> <ExitWorldEntity> <SewerLevel>`

* `ExitWorldID`  = destination worldId (matches `world_defs.id` in Postgres).
* `ExitWorldEntity` = destination *insertion-point / entity index* (resolved by
  the **client**, see §4).
* `SewerLevel` = sewer depth hint (also forced to 1 for ft 20/29).

### 2e. ChangeLocation wire message

`MessageBuilder.cxx:1351-1381` `BuildChangeLocationMsg`. Inner payload
(`0x03 → 0x1f → localId → 0x38`): `04 <entityType> <Location u32> <Entity u16>`.
It carries **only** `(Location, Entity, entityType)` — **no coordinates**. The
server does not place the player; the client does.

---

## 3. Cross-check vs known adjacency

* appplaces.def "sewer exit" entries confirm the model is symmetric and
  source-aware: e.g. `setentry 53 "sewer exit" 1 52 0` → exits to world **1**
  (plaza_p1) with Entity **52** (the paired *entrance* appplace index used as the
  client insertion key). `setentry 71 "sewer exit" 101 70` → world **101**
  (plaza_p3). `setentry 81 "sewer exit" 102 72` → world **102** (plaza_p4).
  `setentry 111 "sewer exit" 5 110` → world **5** (pepper_p1). These exit
  ExitWorldIDs (1,2,101,102,5,6,7) line up exactly with the known
  worldId↔sector map (plaza_p1=1, p2=2, p3=101, p4=102; pepper p1/p2/p3=5/6/7).
  This proves field 3 of appplaces = real destination worldId.

* **Plaza p1↔p2↔p3↔p4 are NOT door/actor portals.** The Postgres dump shows
  plaza_p1/p2/p3/p4 contain *only DDOOR* in `world_doors` (no TRIGDOOR/TRIGDD)
  and *no* functionType 18/20/29 actor that targets a sibling plaza sector. The
  inter-sector city movement is the open outdoor border-crossing mechanism
  (coordinate/limit based), handled separately — see §6. The only world-change
  actors physically present in plaza_p1..p4 are sewer entrances + one
  startmission entrance each (see §5).

---

## 4. How the destination spawn position is determined

**The server does not compute it.** `BuildChangeLocationMsg` ships
`(ExitWorldID, ExitWorldEntity, SewerLevel)` and nothing else (verified
`MessageBuilder.cxx:1351-1381`; no `SetCoord`/position call on the change-loc
path in `UdpUseObject.cxx` or `Worlds.cxx`). TinNS performs **no** server-side
reposition for actor/door zone changes.

Therefore the **client** resolves the entry XYZ locally: it loads the
destination zone's `.dat` and uses `ExitWorldEntity` as the
insertion-point/entity index to find the matching arrival marker in that zone
(the paired exit actor / insertion element). For sewers the appplaces "sewer
exit" pairing (entrance idx ↔ exit idx, §3) is exactly this round-trip key.

Implication for Ceres-J: to spawn correctly on zone-cross, Ceres must (a) emit
the same `0x03/0x1f/0x38` ChangeLocation payload with the correct
`(ExitWorldID, ExitWorldEntity, entityType)` from the worldmodel.def→appplaces.def
chain, and (b) leave entry-position to the client — **do not** invent server-side
coordinates for portal transitions. (The separate ~10× design-vs-wire coordinate
scale issue is out of scope here.)

Uncertainty: the exact byte form the *client* uses to map `ExitWorldEntity` →
spawn marker inside the destination `.dat` is not in TinNS (TinNS only emits the
index). Confirm against a retail ChangeLocation pcap when available; the other
agent's Ghidra work on the client may pin the insertion-point lookup.

---

## 5. Concrete source→dest mapping (resolved from live DB + defs)

Resolved by joining `world_objects.worldmodel_id` → worldmodel.def(ft∈{18,20})
→ appplaces.def. "Entity" = appplaces ExitWorldEntity (client insertion key).
Each of plaza p1..p4 and pepper p1..p3 has exactly **one** world-change actor
per listed worldmodel (count=1 each in `world_objects`).

| Source zone (world_path / worldId) | worldmodel_id | ft | fval | appplace name | destWorld | Entity | Sewer |
|---|---|---|---|---|---|---|---|
| plaza_p1 (1) | 302 | 18 | 52 | sewer entrance | 920 (NC2 sewer) | 1 | 1 |
| plaza_p1 (1) | 304 | 18 | 54 | sewer entrance | 921 | 1 | 1 |
| plaza_p1 (1) | 306 | 18 | 56 | sewer entrance | 922 | 1 | 1 |
| plaza_p1 (1) | 2018 | 20 | 818 | Reactor Room entrance | 1573 (startmissions/reaktor) | 1 | 0 |
| plaza_p2 (2) | 310 | 18 | 60 | sewer entrance | 923 | 1 | 1 |
| plaza_p2 (2) | 312 | 18 | 62 | sewer entrance | 924 | 1 | 1 |
| plaza_p2 (2) | 314 | 18 | 64 | sewer entrance | 925 | 1 | 1 |
| plaza_p2 (2) | 2024 | 20 | 824 | Abandoned Storage Room entrance | 1579 (startmissions/zhideout) | 1 | 0 |
| plaza_p3 (101) | 320 | 18 | 70 | sewer entrance | 926 | 1 | 1 |
| plaza_p3 (101) | 2016 | 20 | 816 | Biological research Laboratory | 1571 (startmissions/petlaboratory) | 1 | 0 |
| plaza_p4 (102) | 330 | 18 | 80 | sewer entrance | 927 | 1 | 1 |
| plaza_p4 (102) | 2022 | 20 | 822 | Technical Workshop entrance | 1577 (startmissions/workshop) | 1 | 0 |
| plaza_p4 (102) | 2026 | 20 | 826 | Dirty Sewer entrance | **1581 (citysewer/plazasewer_1a)** | 1 | 0 |
| pepper_p1 (5) | 360 | 18 | 110 | sewer entrance | 940 | 1 | 3 |
| pepper_p1 (5) | 362 | 18 | 112 | sewer entrance | 950 | 1 | 5 |
| pepper_p1 (5) | 364 | 18 | 114 | sewer entrance | 942 | 1 | 3 |
| pepper_p1 (5) | 366 | 18 | 116 | sewer entrance | 943 | 1 | 3 |
| pepper_p1 (5) | 2000 | 20 | 800 | Blacksmith Housing entrance | 1555 (startmissions/Blacksmith) | 1 | 0 |
| pepper_p1 (5) | 2004 | 20 | 804 | Ghost District entrance | 1559 (startmissions/fhideout_nc) | 1 | 0 |
| pepper_p1 (5) | 2006 | 20 | 806 | Lost Outskirt entrance | 1561 (startmissions/hideout) | 1 | 0 |
| pepper_p1 (5) | 2014 | 20 | 814 | Machine Room entrance | 1569 (startmissions/machine2) | 1 | 0 |
| pepper_p2 (6) | 370 | 18 | 120 | sewer entrance | 951 | 1 | 5 |
| pepper_p2 (6) | 372 | 18 | 122 | sewer entrance | 945 | 1 | 4 |
| pepper_p2 (6) | 821 | 20 | 5614 | Electric Vibes Club | 1911 (doy/doyclub2) | 1 | 0 |
| pepper_p2 (6) | 2002 | 20 | 802 | Digging Site entrance | 1557 (startmissions/Digging) | 1 | 0 |
| pepper_p2 (6) | 2008 | 20 | 808 | Temple of Cleaning Fire | 1563 (startmissions/inquisitor) | 1 | 0 |
| **pepper_p3 (7)** | 380 | 18 | 130 | **sewer entrance** | **946 (NC2 pepper sewer)** | 1 | 4 |
| **pepper_p3 (7)** | 382 | 18 | 132 | **sewer entrance** | **947** | 1 | 4 |
| pepper_p3 (7) | 2012 | 20 | 812 | Construction Facility entrance | 1567 (startmissions/machine1) | 1 | 0 |
| pepper_p3 (7) | 2020 | 20 | 820 | Smugglers Cave entrance | 1575 (startmissions/smuggler) | 1 | 0 |

pepper_p3 sewer-entrance actor positions (design frame, from `world_objects`):
wm 380 = object_id 95 @ (2472,-2595,-351); wm 382 = object_id 96 @
(3518,-452,-503). These are the real pepper→sewer portals — *not* the
`TRIGDOOR door_id=33` at (197,-1135) (that is an intra-zone trigger door).

Caveat on the sewer destWorldIds (920-951, 940-947): these are the
ExitWorldIDs as written in this install's `appplaces.def`. They are **not
present in the Postgres `world_defs` table** for this Ceres dataset (only
startmission/citysewer dest ids 1555-1581 resolve there). NC2's runtime sewer
worldId space differs from the NC1-era numbering in the def; the *startmission*
and `citysewer/plazasewer_1a` (1581) destinations are fully resolvable and
should be used as the high-confidence reference crossings. Treat the bare
920-951 sewer ids as "appplaces says X, runtime worldId mapping for sewers
needs a retail pcap to confirm".

---

## 6. Outdoor sector borders (NOT door-driven) — for completeness

`Worlds.cxx:210-291` (`CheckVhcNeedZoning` / `GetVhcZoningDestination`) shows
*outdoor terrain* sector crossing is purely **coordinate-limit** based:
`posX/posY` crossing `mBottomZoneOutLimit`/`mTopZoneOutLimit` → step the
worldmap grid `destWorldId = 2001 + 20*V + H` (`Worlds.cxx:306-310, 775-793`).
This applies to wasteland terrain worlds (worldId ≥ 2001), not to the indexed
city zones (plaza/pepper p-sectors). On-foot variant is the same idea
(border-region trigger), no door/actor involved. Out of scope for the portal
`params` task but documented so it is not re-investigated as a door problem.

---

## Summary of confidences

* **HIGH:** door params carry no zone data; param2 = paired-door id; zone change
  = worldmodel.def(ft 15/18/20/29) → appplaces.def(ExitWorldID/Entity/Sewer);
  ChangeLocation wire carries only (Location,Entity,type), no coords; server
  does not place the player; the §5 startmission/citysewer mappings.
* **MEDIUM:** door param1 open-style (TinNS itself marks it "?"); plaza p-sector
  inter-crossing being the outdoor/region mechanism (no portal actor exists, so
  by elimination, but the on-foot border code path was not located in TinNS —
  only the vehicle one).
* **LOW / open:** runtime worldId of the bare sewer destinations (920-951);
  exact client-side `ExitWorldEntity` → spawn-marker byte resolution inside the
  destination `.dat` (TinNS only emits the index; needs retail pcap or the
  other agent's client Ghidra work).
