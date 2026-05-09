# Ceres-J Implementation Status

_Snapshot of progress against [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md)._
_Last updated: **2026-05-08**._

Companion docs: [`INDEX.md`](INDEX.md) (catalog), [`packets/`](packets/)
(per-opcode stubs), [`flows/`](flows/) (multi-packet sequences).

Status legend:
- `pending`         no code written yet
- `in_progress`     code in flight on a feature branch / multi-PR
- `partial`         shipped but not all sub-tasks done
- `implemented`     all sub-tasks coded, no byte-parity test yet
- `tested`          acceptance gate (capture replay) passes in CI
- `shipped`         exercised on a live test server with ≥2 real clients

---

## 1. Phase status

| Phase | Description | Status | Acceptance gate | Notes |
|---|---|---|---|---|
| 0.1 | SubtagRouter centralised dispatch | implemented | — | `packets/SubtagRouter.java` + `SubtagRouterTest`. |
| 0.2 | LuaJ scripting host | partial | first NPC wired | `script/LuaScriptHost`, `ScriptBridge`; no NPC `.lua` ported yet. |
| 0.3 | WorldMessageBus + zone tick | implemented | — | `WorldTickScheduler`, `WorldMessageBusTest`. |
| 0.4 | Persistence boundary / world-data importer | partial | — | DAT importer + def-file mining done; transient-state tagging convention not codified. |
| 1   | Character lifecycle (create/delete) | partial | `CHAR_CREATE`/`CHAR_DELETE` byte-equal | `CharOpAck` (0x8386) shipped + functional test; `CreateCharacter` op-byte routing not unified yet. |
| 2   | Subtag dispatcher + cross-channel chat | implemented | whisper traffic byte-equal | `Chat3bDecoder` + `Chat8317` + `ChatBroadcastIntent` wired; capture replay TODO. |
| 3   | Mob AI + drone protocol | partial | `KILL_MOB*`, `DRONE_INUSE*` byte-equal | All 10 PR parts merged (decode → AI → death). No replay-corpus test, no death-loot drop. |
| 4   | Group/team + buddy + ShortPlayerInfo | partial | `INVITE_TEAM` byte-equal | `GroupManager`, `BuddyManager`, `TeamEvent8388` shipped; `0x33`/`0x32` name lookup not wired; persistence not added. |
| 5   | Trade window | pending | `OUTSIDE_AREAM5_TRADING_PLAYER` byte-equal | TradeManager not started. |
| 6   | Vehicles, subway, parent-entity attach | pending | `IN_SUBWAY_CAR` byte-equal | Movement parent bit-7 partially in `Movement`; no VehicleManager / SubwayManager. |
| 7   | Mission + dialog scripting | pending | `TALKED_NPC*` byte-equal | LuaJ host exists (Phase 0.2) but no MissionManager, no `cere.*` bindings, no dialog subtag handlers. |
| 8   | CityCom RPC + Hometerm mail | pending | `OPEN_HOMETERM*` byte-equal | `0x03/0x2b` decoder + MailManager not started. |
| 9   | Death + respawn + GenRep + implant install | pending | `BEFORE_KILL_MOB23`, `POKE_START` byte-equal | `PlayerDeath` packet exists; respawn state machine not wired. |
| 10  | Hardening (replay harness, stress, metrics) | pending | n/a | No `BytesIdenticalAssertion` utility yet. |

---

## 2. Per-packet parity status

Source: [`INDEX.md`](INDEX.md) (top 30 by retail count). Emit-status determined
by class presence in `src/main/java/server/gameserver/packets/{server,client}_{tcp,udp}/`
and call-sites in managers. Test-status from `src/test/java/server/`.

### TCP S->C

| Catalog key | Best-known name | Samples | Emits? | Byte-parity test? | Notes |
|---|---|---:|---|---|---|
| `tcp_s2c_838f` | ? (high-volume ack-ish) | 1392 | partial | no | Heartbeat-shaped 7B; emitter exists (`a5c35ff` recognises 0x02 ack-channel). |
| `tcp_s2c_a002` | ?-S | 224 | partial | no | Ack channel; recognised but role unconfirmed. |
| `tcp_s2c_830c` | Location | 75 | yes | no | `Location.java` present. |
| `tcp_s2c_8001` | HandshakeA | 71 | yes | no | `HandshakeA.java`. |
| `tcp_s2c_8003` | HandshakeC | 71 | yes | no | `HandshakeC.java`. |
| `tcp_s2c_830d` | GameinfoReady | 54 | yes | yes | `Packet830D` + `Packet830DTest`. |
| `tcp_s2c_8381` | AuthAck | 50 | yes | no | `AuthAck.java`. |
| `tcp_s2c_a001` | SessionReady-S | 34 | yes | no | `SessionReady.java`. |
| `tcp_s2c_8385` | CharList | 27 | yes | no | `CharList.java`; relies on DB rows. |
| `tcp_s2c_8383` | ? | 25 | ? | no | No handler class found; emission path unverified. |
| `tcp_s2c_8388` | ? (TeamEvent) | 25 | yes | yes | `TeamEvent8388` + `TeamEvent8388Test` (Phase 4 pt 1). |
| `tcp_s2c_8305` | UDPServerData | 21 | yes | no | `UDPServerData.java`. |
| `tcp_s2c_8317` | ? (Chat) | 20 | yes | yes | `Chat8317` + `Chat8317Test` (Phase 2). |
| `tcp_s2c_873a` | Gamedata | 12 | yes | no | `Gamedata.java`. |
| `tcp_s2c_8386` | ? (CharOpAck) | 4 | yes | yes | `CharOpAck` + `CharOpAckTest` + functional test (Phase 1). |
| `tcp_s2c_8318` | ? | 2 | no | no | Not emitted; purpose unknown. |

### UDP C->S (top observed)

| Catalog key | Best-known name | Samples | Decodes? | Byte-parity test? | Notes |
|---|---|---:|---|---|---|
| `udp_c2s_03_1f` | Reliable/GamePackets | 188947 | partial | yes (router) | `SubtagRouter` dispatches; ~30 inner sub-tags, only chat 0x3b + drone 0x2d wired. |
| `udp_c2s_20`    | Movement | 179481 | yes | no | `Movement.java`; parent bit-7 partial. |
| `udp_c2s_00`    | ? (ack) | 6338 | partial | no | Recognised as ack channel; OOO-list handling incomplete. |
| `udp_c2s_0b`    | CPing | 4868 | yes | no | `CPing.java`. |
| `udp_c2s_1f`    | ? (unreliable subtag) | 3424 | partial | no | Outer present; inner sub-tag routing not unified through SubtagRouter yet. |
| `udp_c2s_03_27` | Reliable/RequestWorldInfo | 2942 | yes | no | `RequestInfoAboutWordlID.java`. |
| `udp_c2s_32`    | ? (subway/vehicle ctrl) | 2156 | partial | no | Decoded as 0x32 raw; vehicle/subway pilot path not built. |
| `udp_c2s_01`    | ? (ack list) | 2009 | partial | no | Reliable ACK list; see Known Gaps. |
| `udp_c2s_03_2d` | Reliable/NPCData (drone-ctrl) | 812 | yes | yes | `DroneControlPacket` + `DroneControlDecoderTest` (Phase 3 pt 1). |
| `udp_c2s_2d`    | ? (mob/drone outer 6B) | 339 | partial | no | Decoder exists; cross-zone routing unverified. |
| `udp_c2s_03_00` | Reliable/? | 187 | ? | no | 240B fixed-size; unidentified. |
| `udp_c2s_03_22` | Reliable/CharInfo | 152 | yes | no | World-entry path only. |
| `udp_c2s_0c`    | TimeSync | 140 | yes | no | `GetTimeSync.java`. |
| `udp_c2s_03_2b` | Reliable/CityCom | 123 | no | no | DCB RPC; not decoded. |
| `udp_c2s_2a`    | RequestPos | 87 | yes | no | `RequestPositionUpdate.java`. |

### UDP S->C (top observed)

| Catalog key | Best-known name | Samples | Emits? | Byte-parity test? | Notes |
|---|---|---:|---|---|---|
| `udp_s2c_1b` | ? (high-volume tick broadcast) | 143484 | ? | no | No Java class clearly named for it; possibly emitted from compound paths. |
| `udp_s2c_03_2d` | Reliable/NPCData | 69755 | yes | yes | `NpcDataBroadcast` + `MobStateBroadcastTest`. |
| `udp_s2c_20` | Movement | 49847 | yes | no | `SMovement.java` / `PlayerPositionUpdate.java`. |
| `udp_s2c_03_1f` | Reliable/GamePackets | 36537 | partial | no | Several sub-emitters; central encoder not unified. |
| `udp_s2c_1f` | ? (unreliable burst) | 14525 | partial | no | InitInfoResponse02 / InitWeather02 / InitUpdateModel02 emit subsets. |
| `udp_s2c_0b` | CPing | 4939 | yes | no | `SPing.java`. |
| `udp_s2c_03_28` | Reliable/WorldInfo | 3344 | yes | no | `WorldInfoSrv` + `WorldNPCInfo`; layout corrected 2026-04-25. |
| `udp_s2c_32` | ? (subway/vehicle echo) | 2335 | no | no | Not emitted; Phase 6. |
| `udp_s2c_02` | ? | 1901 | partial | no | World-entry burst (`InitInfoResponse02` etc.); broader path unknown. |
| `udp_s2c_03_32` | Reliable/? | 1683 | ? | no | Pilot/passenger echo? Not emitted. |
| `udp_s2c_03_1b` | Reliable/Group1B/PosUpdate | 1104 | ? | no | No emitter wired; Group fan-out missing. |
| `udp_s2c_03_2e` | Reliable/Weather | 869 | yes | yes | `WorldWeather` + `WorldWeatherTest`. |
| `udp_s2c_03_23` | Reliable/InfoResponse | 367 | yes | no | `InfoResponse.java`. |
| `udp_s2c_03_2f` | Reliable/UpdateModel | 351 | yes | yes | `UpdateModel` + `UpdateModelTest`. |
| `udp_s2c_03_26` | Reliable/RemoveWorldItem | 253 | partial | no | Emitter referenced but death-drop path not connected (Phase 3). |
| `udp_s2c_03_25` | Reliable/PlayerInfo | 41 | yes | yes | `LongPlayerInfo` + `PlayerInfoPacketsTest`. |
| `udp_s2c_03_2c` | Reliable/StartPos | 37 | yes | yes | `CharInfo`/`CharInfoV1` + `CharInfoContentTest`. |
| `udp_s2c_03_30` | Reliable/ShortPlayerInfo | 18 | yes | yes | `ShortPlayerInfo` + `PlayerInfoPacketsTest`. |
| `udp_s2c_03_07_01` | Multipart/CharInfo | 17 | yes | yes | `CharInfoContentTest` covers reassembly. |

---

## 3. Open decode tasks

Opcodes named `?` in `INDEX.md` ordered by retail hit count. These need a
decode pass before they can be emitted server-side.

| Catalog key | Count | Notes |
|---|---:|---|
| `udp_s2c_1b` | 143484 | Highest-volume S->C packet; identity unknown. Likely a per-zone broadcast (mob+player+object compound). |
| `udp_c2s_00` | 6338 | Acks + heartbeats; needs OOO list spec. |
| `udp_s2c_1f` | 14525 | Unreliable subtag carrier; sub-tag map only partial. |
| `udp_c2s_1f` | 3424 | Same as above, C->S side. |
| `udp_c2s_32` | 2156 | Vehicle/subway pilot input; 21B; Phase 6. |
| `udp_s2c_32` | 2335 | Echo of `udp_c2s_32`; Phase 6. |
| `udp_s2c_02` | 1901 | World-entry init burst; layout partially mapped. |
| `udp_s2c_03_32` | 1683 | Reliable subtag of unknown role. |
| `udp_s2c_03_1b` | 1104 | "Group 1B / position update" — needs flow capture in 2-client session. |
| `udp_c2s_3c` | 60 | 12B `0x3c 01 00 [TAG] [LE32] [LE32]`; AttributeUpdate3c emitter exists but decode path not wired. |
| `tcp_s2c_838f` | 1392 | High-volume TCP heartbeat-shaped; role unconfirmed. |
| `tcp_s2c_a002` | 224 | Ack channel; role unconfirmed. |
| `tcp_s2c_8383` | 25 | 26B fixed-size; unidentified. |

---

## 4. Known fidelity gaps

| Gap | Impact | Blocker |
|---|---|---|
| Reliable ACK / OOO-list incomplete | Client tolerates under low load; risk of drops under stress | none — design work pending |
| Movement-authority "Synchronizing" overlay | Visible in-game on every move, sessions survive | unknown 0x03/0x?? position-ack format |
| HUD CASH widget inactive on Ceres-J despite byte-correct `0x25/0x04` | Wallet doesn't render at login | open: CharInfo Section 8 byte fidelity beyond size match (see `cash_investigation_archive.md`) |
| Soullight runtime sub-opcode unidentified | Cannot toggle SL at runtime | needs retail pcap of an SL change; not in CharInfo nor any UDP packet |
| Death-induced item drop (`0x03/0x26 RemoveWorldItem`) not emitted | Mob death leaves no loot | Phase 3 follow-up + Phase 5 inventory transactional move |
| `0x03/0x07` multipart > 3 fragments untested | CharInfo > ~5KB may corrupt | only 1× retail sample per discriminator beyond 3 |
| `Movement` parent-entity bit-7 only used for chair-sit | Subway / vehicle attach not yet possible | Phase 6 |
| LuaJ host exists but no NPC `.lua` ported | Dialog/mission flows blocked | Phase 7 |
| `0x03/0x2b` CityCom RPC undecoded | Hometerm mail/vehicle listing inert | Phase 8 |
| `RespawnManager` absent | Death state machine has no recovery | Phase 9 |

---

## 5. How to update

When you finish a parity task:

1. Update the matching row in section 2 (flip `Emits?` / `Byte-parity test?` to `yes`).
2. Update the relevant phase row in section 1 if it crosses a status boundary.
3. If a new gap is uncovered, append it to section 4 with impact + blocker.
4. If a new opcode decode lands, move its row from section 3 into section 2 with the proper key.

The phase status flips to `tested` only when a capture-replay assertion in
`src/test/java/server/protocol/` passes against the original retail pcap.
`shipped` requires a live ≥2-client smoke test on the dev server.

This doc is a snapshot, not a plan. The plan lives in
[`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md); keep that file as the
single source of truth for scope decisions.
