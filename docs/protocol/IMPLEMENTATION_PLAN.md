# Ceres-J Server Implementation Plan

_Phased build-out matching the retail wire protocol decoded under
`docs/protocol/`. Each phase is independently shippable and validated
against the existing capture corpus before moving on._

## Guiding principles

1. **One feature, one capture** — every phase has a designated retail
   capture (or marker subset) that drives the byte-level acceptance
   test.
2. **Carrier first, behavior second** — implement the wire
   encoding/decoding before adding gameplay logic on top. Avoid
   bundling.
3. **Reuse the ECS** — `server.ecs.Components` is already a sparse-set
   component store. New entity kinds (drones, vehicles, mobs, dropped
   items) get components there, not parallel object hierarchies.
4. **Subtag dispatch is a hot path** — centralize the `0x03/0x1f` and
   `0x03/0xNN` reliable-channel demux so future tags plug in without
   touching `Player.run()`.
5. **No speculative features** — if the protocol hasn't been observed
   for it, do not build it. Out of scope for this plan: PvP arenas,
   faction sympathy, Outzone, clan war, GameMaster commands beyond
   what `AdminCommandHandler` already exposes.

---

## Phase 0 — Architectural prerequisites (decide before coding)

These are blocking decisions. Resolve them in this order.

### 0.1 Subtag dispatcher refactor (1 day)

The reliable channel `0x03/0xNN` and the unreliable
`0x1f`/`0x20`/`0x32` paths currently route through ad-hoc handlers.
Before adding 7+ new subtag families (trade, group, dialog, mission,
citycom, drone, vehicle), centralize dispatch.

- **Decision**: introduce a `SubtagRouter` keyed by
  `(outerTag, innerTag)` that resolves to a `ClientUDPPacket`
  constructor. Default fallback = `UnknownClientUDPPacket`.
- **Rationale**: protocol decode shows ~30 subtags under `0x03/0x1f`
  alone. A switch-statement ladder will rot fast.
- **Files to add**: `server.gameserver.packets.client_udp.SubtagRouter`,
  `server.gameserver.packets.client_udp.subtag.*` (one class per
  subtag family).
- **Files to modify**: `PlayerUdpListener.java`,
  `GameServerUDPConnection.java`.

### 0.2 Mission/dialog scripting host: **LuaJ vs. Java**

This is the single biggest architectural call in the plan.

| Option | Effort | Fidelity to retail | Maintenance |
|---|---|---|---|
| **A. LuaJ runtime + reuse client `scripts.pak/dialogheader.lua` server-side** | 3-5 days bootstrap + per-NPC ~hours | Highest — same script the client invokes via `SendScriptMsg` | Tracking client `.lua` updates is automatic |
| **B. Re-implement each NPC dialog in Java (visitor pattern)** | 1-2 days per NPC tree | Drift risk: server logic diverges from client labels | All Java, easy to debug, no sandboxing |

**Recommendation: A (LuaJ)** with a tightly scoped Java bridge surface.

- The 55 Lua RPC commands extracted in `CLIENT_LUA_BRIDGE.md` are
  already a stable contract.
- Mission IDs land as ASCII strings (`mc5_<npc>` format) — the
  canonical mapping lives in client Lua, re-keying it in Java is
  duplicate work that will silently desync.
- LuaJ is a single jar, ~600KB, no native deps, BSD-licensed. Add
  `org.luaj:luaj-jse:3.0.1` to `pom.xml`.
- Sandbox: only expose `cere.npc`, `cere.player`, `cere.mission`,
  `cere.world` Java-bound tables. No `os`, `io`, `loadfile` from
  server scripts.

**Action**: add `lua/` resource path under `src/main/resources/` and
a copy job in `pom.xml` that pulls dialog scripts out of the client
`scripts.pak`. The first NPC (`mc5_runner` recruiter at the Genesis
exit) is the smoke test.

### 0.3 Concurrency model decision

`Player extends Thread` (one OS thread per session) currently dominates
concurrency. Adding multiplayer features (trade, group, chat
broadcast) means cross-player mutation. **Do not refactor to NIO/virtual
threads now** — that's a separate effort. Instead:

- **Decision**: introduce a `WorldMessageBus` (single-writer per zone,
  `ConcurrentLinkedQueue` or `LinkedBlockingQueue`). All cross-player
  mutations route through it.
- Players post intents (`TradeOpenIntent`, `GroupInviteIntent`,
  `ChatBroadcastIntent`); the zone tick consumes them in order.
- `internalEvents/` already shows the heartbeat-tick pattern. Extend
  it.

### 0.4 Persistence boundary

PostgreSQL is already wired. Several upcoming features (group state,
active trade, mail, drone lease) are **session-scoped** — they should
NOT persist. Persistent: character cash/inventory/skills/buddy-list/mail.
Transient: open trade window, group composition, drone instance,
vehicle pilot. Tag this in code via comments on each new manager.

---

## Phase 1 — Character lifecycle completion (~1 week)

**Why first**: the corpus has many CHARACTER_CREATE/DELETE captures and
these flows are short-lived but block all multiplayer testing (you need
fresh chars to reproduce specific captures). Also small in scope, builds
confidence in the dispatcher refactor.

### Scope

- **Character creation** (`docs/protocol/packets/tcp_c2s_8482.md`,
  `flows/character_creation.md`):
  - Op-byte `0x05` preview (validate name + return server slot) → reply
    `0x8386` status `0x00`.
  - Op-byte `0x07` commit (persist row + reply `0x8386` status `0x00`
    or `0x06` with ASCII reason).
  - Op-byte `0x03` delete (already partly stubbed, finalize ack
    semantics).
- **Character delete**: confirm `0x8482 op=0x03` path → DB cascade
  delete → `0x8386 0x00`.
- **Wire test**: drive bytes from `captures/CHAR_CREATE*` and
  `CHAR_DELETE*` markers and assert byte-identical server replies.

### Files

- Modify `server.gameserver.packets.client_tcp.CreateCharacter`,
  `.DeleteCharacter`, `.CheckCharacterName` to dispatch on op-byte
  rather than separate opcodes.
- Modify `server.database.playerCharacters.PlayerCharacterManager`
  for atomic create/delete with name-uniqueness check.
- Add `server.gameserver.packets.server_tcp.CharOpAck` (replaces
  ad-hoc `0x8386` emission).
- Tests under `src/test/java/server/protocol/CharLifecycleTest.java`.

### Acceptance

- `CHAR_CREATE` capture replayed: every server reply byte-equal except
  session/UID fields.
- Name-collision path emits `0x8386 0x06` + reason ASCII matching
  retail.
- Delete then immediate re-create with same name succeeds.

---

## Phase 2 — Subtag dispatcher + cross-channel chat (~1 week)

**Why second**: chat is the lowest-risk high-visibility multiplayer
feature. Validates the `SubtagRouter` from Phase 0.1 and the
`WorldMessageBus` from Phase 0.3 in production-shaped flows.

### Scope

- Implement `SubtagRouter` (Phase 0.1).
- Local chat already exists at `0x1b` — verify and migrate it to the
  new router.
- `0x3b` cross-channel chat:
  - byte-0 channel: `0x00 buddy`, `0x02 clan`, `0x03 team`,
    `0x04 whisper`, plus the pre-existing global path.
  - Server reflects via TCP `0x8317` (already partly observed; finalize
    the encoding using `tcp_s2c_8317.md`).
- ChatManager fan-out by channel: buddy needs buddy-list (Phase 4
  dependency — stub for now), clan needs clan roster (out-of-scope;
  leave NotImplemented), team uses Phase 4, whisper is name-resolved
  via `0x33`.

### Files

- Add `server.gameserver.packets.client_udp.subtag.ChatSub3b` (handles
  all four channels).
- Modify `server.gameserver.ChatManager` to route by channel byte; add
  `whisperByName(String, String)`.
- Modify `server.gameserver.packets.server_tcp.GlobalChat_TCP` (rename
  to `Chat8317`, preserve old class as deprecated alias if anything
  imports it).
- Add `server.gameserver.PlayerDirectory` (name → online-player lookup;
  backed by `PlayerManager`).

### Acceptance

- Captures with `KILL_MOB2`/`OUTSIDE_AREAM5_TRADING_PLAYER` markers
  contain whisper traffic — replay and confirm 1:1 fan-out.
- Two-client local test: whisper, team, global all render correct
  sender + channel byte.

---

## Phase 3 — Mob AI + drone protocol (~1.5 weeks)

**Why third**: many subsequent flows (combat-driven death/respawn,
missions where you kill a mob, drone-piloting) require working mob
state. Drone protocol shares the `0x03/0x2d` carrier with mobs —
implementing them together is half the work.

### Scope

- **Mob state machine** keyed off the 5 `0x03/0x2d` 54B states:
  `0x75 idle`, `0x71 combat`, `0x70 transition`, plus the two rare.
  Position float at offset 6-9 (decoded), 3-value mob class enum at
  the documented offset.
- **Mob AI**: minimum viable — idle wander → aggro on player within
  range → combat (existing `DamageEvent` carrier) → death (drop
  `0x03/0x26 RemoveWorldItem` + loot 0x26 listing). No pathfinding
  beyond straight-line for v1.
- **Drone protocol** (`docs/protocol/packets/udp_c2s_03_2d.md` 41B
  variant, drone class indicator `0x02`):
  - Drone is a separate ECS entity, NOT parented to player.
  - C->S 41B has 3 LE32 floats (XYZ control input).
  - Server echoes drone position via `0x03/0x2d` 54B S->C (same carrier
    as mobs).
  - Drone fires using same `0x01/0x0e` weapon-fire subtag as the player;
    `attacker_id` = drone entity id.

### Files

- Add `server.gameserver.MobManager`, `server.gameserver.MobAI` (state
  machine), `server.gameserver.MobSpawner` (refactor existing
  `NPC.java` spawning).
- Add ECS components in `Components.java`: `mobState`, `mobClass`,
  `aggroTargetId`, `parentEntityId` (also reused by Phase 6 vehicles).
- Add `server.gameserver.packets.client_udp.subtag.DroneControl`
  (0x03/0x2d 41B path).
- Add `server.gameserver.DroneManager`.
- Modify `Components.java` to add a `controllerEntityId` (the player
  who summoned the drone).
- Modify `server.gameserver.packets.server_udp.NpcDataBroadcast` to
  emit the right state byte from the new state machine.

### High-risk

- **Concurrency**: aggro recompute under load. Use the
  `WorldMessageBus` per zone — never let two zone ticks race a mob.
- **State-byte exhaustiveness**: the corpus has 5 observed values; if
  a real client expects a 6th in some edge case (e.g. stunned), log
  and treat as `0x70 transition`.

### Acceptance

- `KILL_MOB`, `KILL_MOB2`, `MOB_AGGRO`, `MOB_DEAGGRO`,
  `MOB_COMBAT_AND_DESPAWN` markers replay byte-equal in S->C broadcast
  traces (modulo entity IDs).
- `DRONE_INUSE`, `DRONE_INUSE_FIRING`, `DRONE_INUSE_KILL` capture
  roundtrips: drone position S->C echoes within 100ms of C->S input.

---

## Phase 4 — Group/team + buddy + ShortPlayerInfo (~1 week)

**Why fourth**: unlocks team chat from Phase 2 and is a prerequisite
for the trade window UX (right-click buddy → trade).

### Scope

- `0x33/0x02` name lookup C->S → `0x32/0x02` UID reply S->C (resolve
  buddy name to entity id when the player isn't in the same zone).
- `0x25/0x13` team-state delta wrapper (members joined/left/promoted).
- TCP `0x8388` player-info push (sent on team membership change so the
  client refreshes the HUD widget).
- `0x03/0x31` RequestShortPlayerInfo and `0x03/0x30` ShortPlayerInfo
  reply (already partly implemented — finalize buddy fields).
- Buddy-list persistence (PostgreSQL table `player_buddies` if not
  present; check `database.playerCharacters.PlayerCharacter`).

### Files

- Add `server.gameserver.GroupManager`, `server.gameserver.BuddyManager`.
- Add `server.gameserver.packets.client_udp.subtag.NameLookup33`,
  `subtag.TeamState25_13`.
- Add `server.gameserver.packets.server_tcp.PlayerInfoPush8388` (the
  `0x8388` carrier).
- Modify `Components.java`: `groupId`, `groupRoleByte`.
- Modify `server.database.playerCharacters.PlayerCharacterManager` for
  buddy CRUD.

### Acceptance

- `INVITE_TEAM` marker replays 1:1.
- A 2-character session: invite → accept → both `0x8388` push events
  land with correct member-list deltas.
- Buddy whisper resolves cross-zone via the `0x33`/`0x32` exchange.

---

## Phase 5 — Trade window (~1 week)

**Why fifth**: depends on group/buddy resolution (Phase 4) and on the
cash carrier (already verified). The full sequence is well-decoded.

### Scope

The four-step trade choreography from `flows/p2p_trade.md`:

1. `0x17/0x02` trade-open request (initiator → target's session).
2. `0x25/0x12` item-add deltas (both directions).
3. `0x37/0x02` confirm-state toggle.
4. `0x3c/0x02` cash commit + final atomic exchange.

Server holds **TradeSession** state per pair until both confirm.
Cancellation paths from any of the 4 stages must roll back cleanly.

### Files

- Add `server.gameserver.trade.TradeManager`, `trade.TradeSession`,
  `trade.TradeSlot`.
- Add subtag handlers: `subtag.TradeOpen17_02`, `subtag.TradeAdd25_12`,
  `subtag.TradeConfirm37_02`, `subtag.TradeCommit3c_02`.
- Modify `server.gameserver.packets.server_udp.CashUpdate` (already
  verified byte-correct) to be reusable from `TradeSession.commit()`.
- Modify `server.database.playerCharacters.inventory.PlayerInventory`
  to support transactional move (both characters' inventories must
  commit or both roll back).

### High-risk

- **Atomicity**: the 4-step protocol can desync if either party drops
  mid-confirm. Treat any disconnect during stages 3-4 as full rollback.
  Do not partially apply.
- **Cash overflow**: target receiver's cash + delta must not exceed
  the cap (find the cap value in `CashUpdate.java` comment).
- **Item-id reuse**: dropped items use `0x03/0x26 RemoveWorldItem` and
  item ids on the wire are short. Make sure trade-out doesn't reuse a
  freshly-removed id within the same tick.

### Acceptance

- `OUTSIDE_AREAM5_TRADING_PLAYER` (72 occurrences in corpus — biggest
  single marker for any subsystem) replays end-to-end with byte-identical
  TCP reflections and UDP confirms.
- Cancel-at-stage-3 rolls both inventories back to pre-trade snapshot.
- Cash commit S->C bytes match retail to the byte (this is your
  tightest invariant — `CashUpdate.java` is already proven).

---

## Phase 6 — Vehicles, subway, parent-entity attach (~1 week)

**Why sixth**: small change set (the parent-entity bit-7 mechanism is
already implemented for chair-sit). Subway uses the exact same wire
mechanism as vehicle pilot, so they ship together.

### Scope

- Generalize the existing parent-entity bit-7 in `Movement` so any
  entity (chair, vehicle, subway car) can be a parent.
- Vehicle pilot: control inputs go via raw `0x32` outer 21B C->S with
  vehicle entity id. Reuse drone-style separate-entity model (vehicle
  is an ECS entity with its own pos, with `pilotEntityId` component
  pointing at the player).
- Subway: scripted parent-entity sequence. The car follows a fixed
  spline; passengers are attached via parent bit-7.
- Detach on exit via C->S `0x00 0x27` 5B.

### Files

- Add `server.gameserver.VehicleManager`, `server.gameserver.SubwayManager`.
- Add ECS component `pilotEntityId` (separate from `controllerEntityId`
  from Phase 3 to avoid drone/vehicle confusion).
- Modify `server.gameserver.packets.client_udp.Movement` to emit/consume
  parent-entity bit-7 generically (probably already 90% done).
- Add `server.gameserver.packets.client_udp.subtag.Detach00_27`.

### Acceptance

- `IN_SUBWAY_CAR`, `LEAVE_SUBWAY_CAR`, `ENTER_SUBWAY_CAR`,
  `AT_DEST_SUBWAY_CAR` markers replay byte-equal.
- `ZONING_DRIVING_VEHICLE` and `DISMISS_VEHICLE` flows complete without
  entity orphaning (no leaked vehicles in `MobManager` or
  `VehicleManager` after capture replay).

---

## Phase 7 — Mission + dialog scripting (~2 weeks, the big one)

**Why seventh**: requires LuaJ from Phase 0.2 and depends on group/buddy
(Phase 4) for shared missions.

### Scope

- Implement the LuaJ host (decision 0.2). One LuaState per zone (Lua
  isn't thread-safe), pinned to the zone tick.
- Java→Lua bridge tables: `cere.npc`, `cere.player`, `cere.mission`,
  `cere.world` exposing only the methods that the 55 RPC commands in
  `CLIENT_LUA_BRIDGE.md` need.
- Tag `0x03/0x1f` subtags:
  - `0x1a` dialog navigation (player picks option N, server runs the
    matching Lua callback).
  - `0x2a` mission grant (ASCII mission ID like `mc5_runner`).
  - `0x17` use-object (NPC right-click talk).
- Mission state machine: `available → granted → in_progress →
  completed → rewarded`. Persist current step in `player_missions`
  table (add to schema).
- First wired NPC: `mc5_runner` (Genesis dungeon recruiter) — smoke
  test for the full dialog tree.

### Files

- Add `server.gameserver.script.LuaScriptHost`, `script.NpcLuaBindings`,
  `script.MissionLuaBindings`.
- Add `server.gameserver.mission.MissionManager`, `mission.Mission`,
  `mission.MissionStep`.
- Add subtag handlers: `subtag.DialogNav1a`, `subtag.MissionGrant2a`,
  `subtag.UseObject17`.
- Modify `pom.xml` to add `org.luaj:luaj-jse:3.0.1`.
- Add `src/main/resources/lua/dialogheader.lua` (extracted from client
  `scripts.pak`).
- Add migration `database/migrations/0xx_player_missions.sql`.

### High-risk

- **Lua sandboxing**: do not expose `os`, `io`, `package.loadlib`,
  `loadfile`. Build the env with `JsePlatform.standardGlobals()` minus
  those.
- **Performance**: hot dialogs (vendor list rebuild) can hit the Lua
  tick loop. Cache emitted vendor inventories.
- **Script reload**: support `cere reload-scripts` admin command without
  restarting the server, since Lua tweaking will be iterative.

### Acceptance

- `TALKED_NPC*` and `BEFORE_INTERACT_VENDOR_NPC`/
  `AFTER_INTERACT_VENDOR_NPC` markers replay byte-equal for the
  Lua-driven NPC.
- `OUTSIDE_AREAM5_TALKING_GEORDI_MISSION_COMPLETE` capture: mission
  state transitions persist across logout/login.

---

## Phase 8 — CityCom RPC + Hometerm mail (~1 week)

**Why eighth**: independent of game-state, but needs the LuaJ host to
be stable since Hometerm dialog options come from Lua.

### Scope

- `0x03/0x2b` CityCom RPC channel: ASCII method names `DCBSetup`,
  `Emaillist`, `Archive`, `DCBGetLastEmail`, `VehicleListing`.
- Hometerm mail backend (PostgreSQL `mail` table, threading by
  `subject`).
- Public CityCom kiosks (vehicle listing, faction news) — read-only
  stubs from DB.

### Files

- Add `server.gameserver.citycom.CityComRpc`, `citycom.MailManager`,
  `citycom.VehicleListingProvider`.
- Add subtag handler: `subtag.CityCom03_2b`.
- Add migration `database/migrations/0xx_mail.sql`.

### Acceptance

- `OPEN_HOMETERM`, `OPEN_HOMETERM_DELETEMAIL`, `OPEN_HOMETERM_READMAIL`
  markers replay byte-equal.
- Send mail from char A → log out → log in char B → mail visible in
  inbox.

---

## Phase 9 — Death + respawn + GenRep + implant install (~1 week)

**Why last in core**: depends on mob combat (Phase 3) and inventory
transactions (Phase 5 patterns).

### Scope

- Mob-induced death (player HP → 0): currently no respawn flow exists
  post-death. Wire the existing `PlayerDeath` packet to a state machine
  that:
  1. Snapshots inventory loss percentage.
  2. Awaits `0x03/0x23` 40B GenRep teleport trigger.
  3. Teleports to GenRep spawn point.
- Implant install (poke):
  - `0x25/0x15` ack.
  - `0x20/0x3f` position update during animation lock (server holds
    player still for animation duration).

### Files

- Add `server.gameserver.RespawnManager`.
- Modify `server.gameserver.Player.die()` to enter the new state machine.
- Add subtag handlers: `subtag.GenRepTrigger23`, `subtag.PokeAck25_15`.

### Acceptance

- `BEFORE_KILL_MOB23`, `KILL_MOB5` markers leave the world in a
  consistent state (no zombie entities).
- `POKE_START` marker replays with the correct animation-lock duration
  (server holds movement for the documented frame count).

---

## Phase 10 — Hardening (~1 week, ongoing)

- Replay test harness: load any `pcap` from `docs/protocol/captures/`,
  drive a synthetic client, diff S->C bytes against recorded.
- Stress harness: 50 simulated clients, all features exercised.
- Metrics: tick durations per zone, queue depths in `WorldMessageBus`,
  Lua step counts.
- Logging: structured (JSON) for everything new added in phases 2-9.

---

## Cross-cutting risks

1. **Game-state synchronization**: trade + group + chat all mutate
   state owned by other players. The `WorldMessageBus` (Phase 0.3) is
   the single contract — anything that bypasses it is a bug.
2. **Multiplayer concurrency**: `Player extends Thread` is fine for
   now but every cross-player feature must enqueue to the bus, not
   call manager methods directly across threads.
3. **LuaJ memory growth**: pin one Lua state per zone, never per
   player. Cap script step count per dialog tick to fail-fast on
   infinite loops in scripts.
4. **Wire byte-correctness regression**: phase 0 introduces a
   `BytesIdenticalAssertion` test utility. Every phase's tests use it.
   A change in Phase N that breaks Phase M must fail CI.
5. **Subtag dispatcher fan-out**: when multiple subtags share an
   opcode (e.g. `0x25/0x04` cash, `0x25/0x12` trade-add, `0x25/0x13`
   team-state, `0x25/0x15` poke-ack), tests must cover all four to
   catch routing mistakes.

---

## Acceptance criteria summary

| Phase | Marker(s) replayed byte-equal | Multi-client live test |
|---|---|---|
| 1 | `CHAR_CREATE`, `CHAR_DELETE` | n/a (single-session) |
| 2 | `KILL_MOB2`/whisper traffic | 2 clients, all 5 channels |
| 3 | `KILL_MOB`, `MOB_AGGRO`, `DRONE_INUSE*` | 1 client + drone vs. mob |
| 4 | `INVITE_TEAM` | 2 clients invite/leave/promote |
| 5 | `OUTSIDE_AREAM5_TRADING_PLAYER` | 2 clients, mid-trade disconnect rollback |
| 6 | `IN_SUBWAY_CAR`, `ZONING_DRIVING_VEHICLE` | 1 pilot + 1 passenger |
| 7 | `TALKED_NPC*`, mission-complete markers | 1 client, full Genesis runner mission |
| 8 | `OPEN_HOMETERM*` | mail send/receive across logout |
| 9 | `BEFORE_KILL_MOB23`, `POKE_START` | death + GenRep + implant chain |

---

## Total timeline estimate

| Phase | Duration | Cumulative |
|---|---|---|
| 0 (architecture) | 3-5 days | 1 week |
| 1 (char lifecycle) | 1 week | 2 weeks |
| 2 (chat + dispatcher) | 1 week | 3 weeks |
| 3 (mob + drone) | 1.5 weeks | 4.5 weeks |
| 4 (group/buddy) | 1 week | 5.5 weeks |
| 5 (trade) | 1 week | 6.5 weeks |
| 6 (vehicle/subway) | 1 week | 7.5 weeks |
| 7 (mission/dialog) | 2 weeks | 9.5 weeks |
| 8 (citycom/mail) | 1 week | 10.5 weeks |
| 9 (death/respawn) | 1 week | 11.5 weeks |
| 10 (hardening) | 1 week | 12.5 weeks |

**~3 months** for the full feature-complete server. Each phase is
shippable in isolation; the user can stop at any phase boundary with
a working server that supports the cumulative feature set.

## Implementation tracking

Track progress against this plan in `docs/protocol/IMPLEMENTATION_STATUS.md`
(create when Phase 0 begins). Each phase gets a status row:
`pending | in_progress | implemented | tested | shipped`.

When a phase is `tested`, its acceptance criteria have all passed in
CI. When it's `shipped`, it's been deployed to a live test server and
exercised by ≥2 real clients.
