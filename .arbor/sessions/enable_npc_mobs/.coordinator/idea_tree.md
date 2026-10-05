# Idea Tree

**Baseline**: 0 | **Trunk**: 0

## ROOT: Enable NPCs/Mobs: make bridged world_npcs mobs render on the retail client (fix anim-139 / model_name='' blocker) [DONE]

**Result**: Run paused BLOCKED on instrumented harness. Verified: NPC render = raw 0x1b + 0x28 + 0x2d (Ceres now sends all 3); retail 0x1f is player-state not NPC; #178d 0x1b-removal falsified; 0x1e fix rejected (retail sends none). Spiders still do not render + client log unchanged after 0x1b fix (10x anim-139 benign, 5x Corrupted Type:15 persists). The render gap now requires SEEING client behavior = the Frida harness, which has an intermittent Wine/Proton gadget cold-start failure (~7 fails this session, worked 3x). No more retail ground-truth to mine without it.

### 1: H1 PositionStream | Mechanism: client creates the spider actor (0x28 ok, model ok) but needs a full-state 0x2d/0x1b position record to PLACE+render it; Ceres may only send the 9-byte 0x2d keepalive ping (NpcDataBroadcast writes the 6B ping form), never the 55-byte position record. | Hypothesis: bridged mobs get spawn+ping but no positional NpcData, so the actor exists def-side but is never world-placed/rendered. | Observable: per-spider in spider4 trace, only 9B 0x2d (no 55B record) + empty D3D9; fix = emit positional record -> mob appears. | Conflicts: spiders DID get some 0x2d; need to confirm 9B-only. No invented bytes (55B form is retail-pinned). [PRUNED]

**Insight**: NPC render is fully specified by raw 0x1b + 0x28 + 0x2d (all 3 now sent by Ceres). No unanalyzed retail NPC signal remains. The 'why no render' question now REQUIRES the visual harness (see what the client does with the packets). Harness gadget recovery = critical path.

**Result**: Cycle5: retail 0x1f (444x, dominant) is ALL keyed to localId 3 = the PLAYER (HUD/pools/buffs), NOT per-NPC. Ruled out as NPC render signal.

### 2: H3 SpawnCoords | Mechanism: 0x28 carries X/Y/Z; if the bridge populates them from world_npcs coords in a different frame than spider_cave_1.dat, the mob renders out of view / under floor. | Hypothesis: bridged spawn coords are wrong-frame/zero so mobs are placed off-camera. | Observable: decode spider 0x28 X/Y/Z vs player pos + dat bounds; walking to coords reveals mob or it's under floor. | Conflicts: coords are real DB values (no invented bytes). Falsified if coords are sane+in-room. [PENDING]

#### 2.1: EXP1 PlayerNextToSpiders | Mechanism: anim-139 benign + actor created + 0x28 coords sane -> maybe spiders DO render and player was just 1000+ units away in spider4. | Hypothesis: placing Krafteo AT a spider cluster (775,-900) makes spiders visible in D3D9 snapshots, no code change. | Observable: harness snapshot shows a spider model on screen. | Conflicts: none (DB coord edit only). If still empty -> genuine render/lifecycle bug, escalate to H1. [PRUNED]

**Insight**: User confirmed mobs NEVER render anywhere -> the 0x28 spawn + 6B ping that suffices for retail stationary city-NPCs is NOT sufficient for bridged spiders. Difference must be in the spawn-packet bytes or a missing placement packet. Next: byte-diff retail rendering-NPC 0x28 vs Ceres spider 0x28.

**Result**: FALSIFIED by user: zero spiders anywhere in the dungeon. Not positioning. Genuine render bug.

### 3: H2 RawInstantiation | Mechanism: actual world-actor instantiation id lives in raw 0x1b [13..14] (diff id space from 0x28); client may need 0x1b to bind the actor into the visibility set. | Hypothesis: bridged mobs miss the raw 0x1b instantiation (or its handle mismatches the 0x28), so never world-placed. | Observable: trace diff retail pairs 0x28+0x1b per NPC; Ceres has 0x28 but no/mismatched 0x1b. | Conflicts: needs retail creature pcap for the 0x1b template; do NOT invent [13..14] mapping. [DONE] (score: 1)

**Insight**: REAL BLOCKER (pre-existing, found via client log not harness): '@WWORLDMGR Corrupted Message Type:15, Size:21' at every heartbeat = client rejects the spider SPAWN message as a misframed Type-15 create -> NPC never spawns. This is a 0x28/WorldInfo Type-15 FRAMING bug (see re_state_sync_authoritative: client WWORLDMGR reads body byte0=Type, no case 0x0F -> 'Corrupted Type:15'). The #178c '17 state bytes' fix did NOT resolve it for spiders. Next cycle: decode Ceres spider ZoneStateCompoundPacket bytes through the client Type/Size framing to find why it frames as Type:15 Size:21.

**Result**: 0x1b fix verified-correct + implemented + tested (1475 green) + deployed on branch exp/npc-render-0x1b. Server now emits raw 0x1b. BUT client log identical before/after (5x Corrupted Type:15, 10x anim-139) -> 0x1b necessary-not-sufficient; did NOT achieve render. NOT merged to trunk (no positive B_test).

#### 3.1: H5 Type15Misframe | Mechanism: client WWORLDMGR frames the spider spawn as Type-15 create with Size:21 and rejects it as corrupted (5x/run, pre-existing, before AND after 0x1b fix). | Hypothesis: the 0x28/ZoneStateCompoundPacket reliable framing or the Type-15 body size for bridged spiders is wrong, so the client never spawns the actor. | Observable: client log 'Corrupted Message Type:15, Size:21' stops once framing fixed; spider spawns. | Conflicts: #178c thought 17 state bytes fixed this; still broken for spiders. Needs decrypted-stream Type/Size analysis. [DONE] (score: 1)

**Insight**: Retail creates these NPCs via RAW 0x1b placement (749x dominant, sub[0]=0x1b) + 0x28 (120) + 0x2d (205) + 0x1f (444, unanalyzed). My SendPresentWorldID raw 0x1b is byte-structurally correct vs retail. The 0x28-state-block zeros are NOT the Type:15 cause (framing is content-independent, decompile-proven). Type:15 cause still unlocalized (likely multipart/player CharInfo, maybe unrelated to spiders). BLOCKER: cannot iterate visually — Frida harness gadget cold-start fails ~7x this env. The 0x1f channel (444x) is the main unanalyzed retail signal.

**Result**: Cycle4 disasm: client CREATE path is Type-0x1E (case 0x1e -> FUN_00540ab0 -> SCRIPTEDPLAYER ctor FUN_00699fd0); 0x28 only sends a spawn-REQUEST. Agent proposed 0x28->0x1e. FALSIFIED against retail: retail sends ZERO 0x03/0x1e sub-packets -> changing to 0x1e would INVENT bytes retail never sends. NOT implemented.
