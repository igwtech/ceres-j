# Light ECS — Ceres-J Entity/Component Model

Ceres-J is migrating its player, NPC, and item runtime state to a **light
entity/component/system** layout. This document describes the design and the
migration strategy.

## Design philosophy

- **No framework.** No Artemis, Ashley, Dominion, Ecs4j, and no bespoke
  abstraction layer with `IComponent` / `ISystem` / `IEntity` interfaces. The
  entire ECS core is four small classes in `server.ecs`.
- **Parallel arrays, not objects.** Each component lives in its own
  [sparse-set][1]: one dense value array, one dense entity-index array, and one
  sparse lookup array mapping entity index to dense slot. Cache-friendly
  iteration, O(1) lookup, packed storage.
- **Handles, not pointers.** An entity is a packed `long` holding a 32-bit slot
  index plus a 32-bit generation counter. Dead handles are detected cheaply
  after a slot is recycled.
- **No system manager.** There is no update loop inside the ECS. Existing game
  loops (packet handlers, zone ticks, PlayerManager) call ECS functions
  directly — the ECS is just storage.
- **No reflection, no annotations, no generics gymnastics.** If you can't
  understand a component array by reading the file top-to-bottom, it is too
  clever.

[1]: https://programmingpraxis.com/2012/03/09/sparse-sets/

## Core classes

All under `server.ecs`:

| File | Purpose |
|---|---|
| `World.java` | Entity pool. `createEntity()`, `destroyEntity(handle)`, `isAlive(handle)`. Maintains a free-list and a generation counter per slot. |
| `ComponentArray.java` | Sparse-set storage for reference-typed components. `set/get/has/remove/entityAt/valueAt`. |
| `IntComponentArray.java` | Primitive-int variant. Same API, no boxing — use for hot numeric fields (coordinates, HP, skill values). |
| `Components.java` | The concrete bundle of component arrays the server uses. Plain `public final` fields — no registry. |
| `EcsRegistry.java` | Process-wide singleton exposing a single `World` + `Components` pair. |
| `PlayerCharacterBridge.java` | Two-way sync between the legacy `PlayerCharacter` class and the component arrays. Enables incremental migration. |

## Handles

```java
long handle = World.pack(index, generation);
int  slot   = World.index(handle);   // low 32 bits
int  gen    = World.generation(handle); // high 32 bits
```

`World.NULL` (= `0L`) is the "no entity" sentinel. Slot 0 is reserved so it
can never collide with a real entity. `isAlive(handle)` returns `false` for
`NULL`, for slots past the high-water mark, and for handles whose generation no
longer matches the current slot generation (i.e. the entity was destroyed and
the slot was recycled).

## How to add a new component

1. Open `Components.java`.
2. Add a `public final` field for your new component array. Use
   `IntComponentArray` for a single `int`, or `ComponentArray<T>` for a POJO.
3. Start writing it from wherever the data enters the system, and start reading
   it from wherever it is consumed. Done.

There is no registration step. There is no `IComponent` to implement. There is
no annotation processor. You just add a field.

### Example

```java
// In Components.java
public final IntComponentArray stamina = new IntComponentArray();
public final ComponentArray<String> guildTag = new ComponentArray<>();

// At the call site
int e = World.index(player.getEcsEntity());
EcsRegistry.components().stamina.set(e, 100);
EcsRegistry.components().guildTag.set(e, "[ghosts]");
```

## How to query entities

### Single-component iteration

`ComponentArray` keeps the dense array packed, so iterating only visits entities
that actually have the component:

```java
Components c = EcsRegistry.components();
for (int i = 0, n = c.posX.size(); i < n; i++) {
    int entity = c.posX.entityAt(i);
    int x      = c.posX.valueAt(i);
    int y      = c.posY.getOrDefault(entity, 0);
    int z      = c.posZ.getOrDefault(entity, 0);
    // ... do something with (entity, x, y, z)
}
```

### Two-component intersection

Iterate the smaller array and ask the second whether it has each entity:

```java
Components c = EcsRegistry.components();
IntComponentArray a = c.health;
IntComponentArray b = c.status;
IntComponentArray smaller = a.size() <= b.size() ? a : b;
IntComponentArray larger  = smaller == a ? b : a;

for (int i = 0, n = smaller.size(); i < n; i++) {
    int entity = smaller.entityAt(i);
    if (!larger.has(entity)) continue;
    // both components present
}
```

### Filter by zone

Use the `zoneId` component as the first filter:

```java
for (int i = 0, n = c.zoneId.size(); i < n; i++) {
    if (c.zoneId.valueAt(i) != targetZone) continue;
    int entity = c.zoneId.entityAt(i);
    // process entity in targetZone
}
```

No query DSL, no fluent builder. Just a `for` loop.

## Migration strategy

### Phase 0 (done): bridge + proof of concept

- `PlayerCharacterBridge.materialize` copies a `PlayerCharacter` into the
  component arrays.
- `PlayerCharacterBridge.exportTo` copies it back.
- `Player.setCharacter(pc)` allocates an ECS entity and materializes the
  character.
- `Player.run()` frees the entity on disconnect.
- **`Movement` packet handler (`client_udp/Movement.java`) writes position,
  orientation, tilt, and status into the ECS first**, then syncs to
  `PlayerCharacter` so downstream code (`Zone.sendPlayerMovement`, persistence)
  sees the update. This is the proof-of-concept subsystem.

Everything else still talks to `PlayerCharacter` directly. Both representations
coexist.

### Phase 1: position & chat

- Port the remaining packet handlers that touch `MISC_X/Y/Z_COORDINATE`,
  `MISC_ORIENTATION`, `MISC_TILT`, `MISC_STATUS` to read/write ECS components
  (`UseItem`, `LocalChat`, `PositionUpdate`, `SMovement`).
- Once all readers use the ECS, the `pc.setMisc(...)` calls inside Movement can
  be deleted and the ECS becomes authoritative for position.

### Phase 2: combat

- Populate `health` / `maxHealth` on login from wherever the legacy hitpoints
  live.
- Replace combat hit/damage handlers with a loop over `health`.
- Add `dead` / `downed` components as flags. Component presence *is* the flag —
  no boolean fields.

### Phase 3: NPCs & items

- Give NPCs ECS entities of their own. Most NPCs only need
  `posX/Y/Z`, `zoneId`, `health`, and maybe `name`. No `PlayerCharacter` bridge
  required.
- Items stay reference-typed inside inventories for now — they have their own
  container graph and moving them into the ECS would touch too much of
  `ItemManager` at once.

### Phase 4: persistence

- Replace `PlayerCharacterManager.save()` with a path that iterates the ECS
  directly and writes to SQLite. At this point `PlayerCharacter` becomes a thin
  DTO used only at load time, and the bridge can be deleted.

## Worked example — porting a hypothetical skill tick

Say skill XP should decay by 1 point per subskill per minute. Before:

```java
// somewhere in a periodic task
for (PlayerCharacter pc : PlayerCharacterManager.getAllCharacters()) {
    for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
        if (PlayerCharacter.SUBSKILLS[i] != null) {
            int v = pc.getSubskillLVLRaw(i);
            if (v > 0) pc.setSubskillLVL(i, v - 1);
        }
    }
}
```

After, once skills have been moved onto the ECS:

```java
Components c = EcsRegistry.components();
for (int slot = 0, n = c.skills.size(); slot < n; slot++) {
    SkillBlock sb = c.skills.valueAt(slot);
    for (int i = 1; i < sb.subskillLvl.length; i++) {
        if (sb.subskillLvl[i] > 0) sb.subskillLvl[i]--;
    }
}
```

No list lookup, no dictionary, no virtual call — one tight loop over the dense
skill-block array. That's the whole point.

## Things this ECS deliberately does not do

- No archetypes. Adding or removing a component does not reshuffle the entity
  between storage pools.
- No query cache. The "query" is a `for` loop; there is nothing to cache.
- No parallelism. The game loop is single-threaded per zone; there is no win
  from splitting component updates across threads at this scale.
- No reactive events. If two systems need to react to a change, they can both
  inspect the component on the next tick, or call each other directly.

Keep it boring. It is already fast enough for an MMO shard.
