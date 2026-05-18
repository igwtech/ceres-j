# RE_state_sync.md — Authoritative S→C state-sync + Type-15 spec

Reverse-engineered from `neocronclient.exe` (Ghidra project
`/home/javier/Documents/Projects/Neocron/Neocron2clien`, program
`neocronclient.exe`). Every claim below is traced to a decompiled
function and its address. No speculation. Raw dumps preserved at
`ceres-j/docs/re_state_sync_dump{,2,3,4,5,6}.txt`; scripts at
`ceres-j/tools/ghidra/REStateSync{,2,3,4,5,6}.java`.

This document explains, byte-exactly, why **every server-authoritative
state change is ignored by the client** and why scripted NPCs log
`@WWORLDMGR : Corrupted Message Type:15, Size:21`.

---

## 0. The two transport layers (must not be conflated)

The protocol has **two stacked dispatchers**. Conflating their numbering
spaces is the root of ~6 failed pcap cycles.

### 0.1 Reliable/session layer — `FUN_0055ec10`

`FUN_0055ec10(buf, …)` switches on `*param_4` (the **link-control
opcode**, first byte of the decrypted reliable wrapper):

- `case 1..10` = LC handshake (SYN/SYN-ACK/ACK/keepalive). Not payload.
- **`default:` (the only payload path)** — at `0055ed?? `:
  `if (0x0e < *param_4 && local_1c != 0xffffffff && in_ECX+0x1060 != 0)`
  → `*param_4 = *param_4 - 0xf;` → `(**(code**)**(in_ECX+0x1060))((local_1c)&0xff, &local_14)`.

  i.e. **only opcodes ≥ 0x0F are application payload**, and the opcode
  is biased by `-0x0F` before being handed up. This is the
  `0x03/0xNN` reliable family.

### 0.2 Application layer — the ClientNetBuffer reader `FUN_004b8cd0`

The decrypted, reassembled application stream is a length-prefixed
queue. `FUN_004b8cd0(int* desc)` (`004b8cd0`) reads one message:

```
iVar1 = *(int*)(buf_base + read_off);      // 4-byte LE message length
read_off += 4;
desc[2]_lowbyte = *(buf_base + read_off);  // 1-byte CHANNEL tag
read_off += 1;
*desc      = iVar1;                        // desc[0] = message size
desc[1]    = buf_base + read_off;          // desc[1] = ptr to message body
read_off  += iVar1;
```

So the 12-byte descriptor is `{ [0]=size i32, [1]=body ptr, [2]=channel
byte }`. **`*(char*)desc[1]` (first byte of the body) is the WWORLDMGR
"Message Type".** This is a *different byte* from the reliable
`0x03/0xNN` opcode in §0.1.

The dispatch loop (`FUN_004b8fb0` @ `004b8fb0`, `FUN_00558950` @
`00558950`, `FUN_00541800` @ `00541800`) is uniformly:

```
while (FUN_004b8cd0(&desc)) FUN_00541f20(&desc);
```

---

## 1. WWORLDMGR message dispatcher — `FUN_00541f20` @ `00541f20`

`switch (*(char*)desc[1])` — the **Message Type**. Verified cases:

| Type | char | Handler | Meaning |
|------|------|---------|---------|
| 0x11 | `\x11` | id4 lookup `FUN_005412d0`, vt+0x18 | request/stats by 32-bit id |
| 0x13 | `\x13` | id2&0x3ff lookup, `LAB_0054201f` vt+0x18 | per-entity NetMessage |
| 0x15,0x16,0x1a,0x1b,0x25,0x27,0x32 | — | same as 0x13 | per-entity NetMessage / playerinfo |
| 0x17 | `\x17` | id4 lookup, vt+0x20 | per-entity action |
| 0x1c | `\x1c` | id4 lookup, vt+0x28(0x1f47) | **delete entity** |
| **0x1e** | `\x1e` | `FUN_00540ab0(type@+1, id@+3, …, body@+7)` | **WA / entity SPAWN** |
| 0x1d,0x1f,0x23,0x26,0x3c,0xb1 | — | id4 lookup, vt+0x18 | request/stats |
| 0x28 | `(` | id2&0x3ff lookup; **if absent → request spawn (`0x1d`/`0x101`)**; else vt+0x18 | WorldInfo update / spawn-request trigger |
| 0x50 | `P` | `FUN_00541400` | (P-type) |
| **default** | — | `thunk_FUN_004471c0("@WWORLDMGR : Corrupted Message Type:%i, Size:%i", *pcVar2, *param_1)` | **NO HANDLER** |

**There is NO `case '\x0f'` (Type 15).** A message whose body byte 0 is
`0x0F` *always* falls into `default:` and logs
`@WWORLDMGR : Corrupted Message Type:15, Size:<desc[0]>`.

Therefore `Corrupted Message Type:15` does **not** mean "a Type-15
packet with the wrong length". It means **the client received an
application message whose first body byte was 0x0F on a transport where
0x0F is not a valid WWORLDMGR Type**. The `Size:21` is just
`desc[0]` — the framed length of whatever the reassembler handed up.

### 1.1 What actually creates a scripted NPC

Entity instantiation is **Type 0x1E** → `FUN_00540ab0` @ `00540ab0`:

```
FUN_00540ab0(type, id, time, len, bodyptr)
  iVar2 = FUN_004d8a50(world, 3, type, id, len, bodyptr, …)   // WA registry walk
  if (iVar2) { (iVar2+8)=id; FUN_0053ffb0(iVar2); return iVar2; }
  else FUN_004471c0("@WWORLDMGR : Unable to Spawn WA: %i", type);
```

`FUN_004d8a50` @ `004d8a50` is a **registry walk**: it hash-looks-up a
chain by `param_3` (the class id) and invokes each registered factory
`(node+8)(world, category, type, id, size, body, extra)`. The factory
table is `FUN_00567e50` @ `00567e50`, which dispatches on
`(param_2=category, param_3=WA-type, param_5=expected size)`.

The complete WA-type registry (from `thunk_FUN_005e2ca0(type,name,size)`
calls in the FX-system init, dump6 lines 448-493):

| type | name | required size | type | name | required size |
|------|------|---------------|------|------|---------------|
|0x10000|Corona|0x4c|0x1000f|**Spectator**|0x1c|
|0x10001|NModelController|0x10|0x10010|ContentSoundOverride|0x10|
|0x10005|AmbientSound|0x40|0x1b|NDoor|0x1c|
|0x10006|StaticSound|0x20|0x1c|NMovingPlat|0x1c|
|0x1000a|GliderSystem|0x10|0x1d|Terminal|0x24|
|0x1000b|MiscCharSystem|0x10|0x19|StaticActor|0x24|
|0x1000e|AmbientContentSound|0x18|0x25|TutorialItem|0x14|
|0x10008|DistanceFog|0x28|0x27|SubwayTrain|0x18|

**`0x1000f` is "Spectator" (a SPECCAM, `FUN_006b1640`@`006b1640`), NOT a
scripted NPC.** No entry in this registry is a scripted player/NPC. The
SCRIPTEDPLAYER class is **not** a WA-factory type — it is constructed by
the WORLDMGR per-entity path (§1, the `0x13`/`0x28`/`0x1e` lookup &
`(vt+0x18)(desc)` parse on an entity object whose ctor is
`FUN_0069a580`/`FUN_00699fd0`), reached when the server's WorldInfo /
spawn data drives `FUN_005412d0` to materialise the entity.

### 1.2 SCRIPTEDPLAYER ctors — exact wire structure

Two ctors exist; the registry-stream one (`FUN_00699fd0` @ `00699fd0`,
called from `FUN_00567e50`) parses a **raw byte stream `param_3`**:

```
param_3[0]      LE32   world/entity id              -> this[0x1ec]
param_3[1]      LE16   class/type id                -> this[0x163]   (this is the lookup key)
(int)param_3+6  LE16 ┐
param_3[2]      LE16 ├ pos triple (x16,y16,z16) via FUN_0054e210 -> this[0x24]
(int)param_3+10 LE16 ┘
(int)param_3+0xd LE32  HP-ish float                 -> this[0x166]/[0x167]
(int)param_3+0x11 10×1B  skill/attr array (loop ×10) -> FUN_0077f410 slot 3
(int)param_3+0x1b LE16  flags                        -> this[0x1e6]
(int)param_3+0x1d ASCIIZ **script_name**             -> FUN_006ad480 / FUN_007afe80
  then ASCIIZ token, then param_3[3] (1B) × 6-byte (x,y,z) waypoint list
```

The struct ctor `FUN_0069a580` @ `0069a580` (called by the WA path with
a *pre-parsed struct* `param_3`):

```
param_3[0]   LE32  id            -> this[0x1ec]
param_3[1]   LE16  class id      -> this[0x163]
param_3[0xb] LE16  flags         -> this[0x1e6]
param_3[2..3] (8B) pos           -> this[0x24]
param_3[4]   LE32  ?             -> this[0x26]
param_3[8]   1B    waypoint cnt  -> this[0x193]; param_3[9]=ptr to cnt×12B
param_3[6]   ptr   **script_name**; param_3[7] model -> FUN_007afe80
```

Both end in `FUN_007afe80(script, model)` → `FUN_0081e310` → script
factory hash lookup; miss → `"Unable to find script: %s"` / `"…Script
spawn failed…"` (gated by `*DAT_0118d53c < 3`).

**Decisive:** the client keys the entity on `class id` and resolves the
**ASCII script_name** that the *server* placed in the spawn body. The
client's `scripts.pak` only holds the *implementation*; the
*assignment* is server data. An empty/misaligned script token → spawn
fail → NPC never renders.

---

## 2. Authoritative HUD / character state application

### 2.1 HUD pool tick functions read NO network input

- `FUN_007e87d0` @ `007e87d0` (HP), `FUN_007e8930` @ `007e8930` (PSI),
  `FUN_007e8a20` @ `007e8a20` (STA).
- Each computes `delta = FUN_007e7c00(skillSel,0,rateConst)` (a
  **timer×skill** regen/decay; `FUN_007e7c00`@`007e7c00` reads
  `FUN_004daa70`/`FUN_004daaa0` skill tables, never a socket) and
  clamps the live pool float (`in_ECX+0x41c..+0x434`) toward a
  subskill-derived ceiling.
- **No packet writes the displayed pool max or current at runtime.**
  The ceiling is set only by the CHARSYS section-2 parser at
  (re)load (§2.3). Confirms `hud_pool_path_confirmed` /
  `charsys_dead_code`.

### 2.2 FULLCHARSYSTEM event dispatcher — `FUN_00803cd0` @ `00803cd0`

`switch(param_1)` on a **UI/CHARSYS event id**. Relevant cases:

| event | behaviour |
|-------|-----------|
| **0x6e** | **LIVE CHARSYS parse.** `FUN_007ef260()`; `FUN_008447d0(param_3[1], *(short*)(param_3+2))` (TLV parse); log `CHARSYS : Buffer loaded : F %i U %i`; `FUN_007ecaf0()`; `FUN_0080b8b0()` (FullCharsysInfo **recompute → re-derives HUD ceilings**). **Unconditional.** |
| 0x85 | `FUN_007fca40(param_3[1],param_3[2],0)` — single HUD value apply |
| 0x95 / 0xa7 | gated by `in_ECX[0xe19]!=0`; pure getters into `param_3` (queries, not parses) |
| **0xa8** | `(**in_ECX)(0xa7,4,&v)` then loops `(**in_ECX)(0x95,0xc,&v)` — a **QUERY** of cached values. **Does NOT parse the buffer.** |
| **0xb3** | `if (in_ECX[5][10]==0) return 1;` — **early-returns unless the "0x29/0x0a" world flag is set**. Only then `FUN_00842a80`, `FUN_007ef260`, `FUN_008447d0` (parse), `FUN_0080b8b0` (recompute). Same pipeline as 0x6e but **gated**. |

`FUN_008447d0` @ `008447d0` is the TLV section loop:
`while (off<len) off += FUN_00845400(buf+off, len-off)`. Per-section
parsers (`charsys_section_handlers.txt`): section 2 = `FUN_00845820`
(POOLS — writes ceilings `in_ECX+0x3f4/+0x3f8/+0x3fc`, synaptic
`+0x448`, soullight `+0x454`), section 8 = `FUN_00846470` (cash → see
§3), section 4 = `FUN_00846960` (subskills, throws
`CHARSYS : WRONG CHAR SKILL READ SIZE` if a sub-record < 9 bytes).

### 2.3 Who fires 0x6e / 0xb3 / the CHARSYS parse — the gap

The network handler that drives the CHARSYS buffer is the multipart
reassembler `FUN_0055c270` @ `0055c270` (called for reliable
`0x03/0x07`). Its two branches:

- **fragment discriminator `0x01` ("CharInfo received")**: fires UI
  event **`0xa7` with 0x45 bytes** (only the *first* time, guarded by
  `in_ECX+0x2ae & 1`), and — *only if total size > 0x42* — UI event
  **`0x13ef`** with `(buf+0x48, size-0x48)`.
- **fragment discriminator `0x02` ("CharsysInfo received")**: logs
  `WORLDCLIENT : Char sys info rcv %i` and fires UI event
  **`0xa8`** with `(buf, size)`.

`0xa7` is a getter; `0xa8` (case in §2.2) is a **query that never
parses the buffer**; `0x13ef` is consumed elsewhere as a blob. **No
discriminator on the `0x03/0x07` multipart path fires `0x6e` or `0xb3`,
the only two cases that actually run `FUN_008447d0` + `FUN_0080b8b0`.**

`0x6e` (the unconditional live parser) is fired from the CHARSYS
*single-packet* network handler (vtable-B slot 8 → `FUN_00841dc0` →
`FUN_008033d0`, see `charsys_network_handler.txt`), i.e. a **dedicated
single-packet CHARSYS update opcode**, *not* the `0x03/0x07` multipart
that Ceres uses for runtime updates. `0xb3` requires the world flag
`in_ECX[5][10] != 0` (only set in specific world states) and is
otherwise dead (matches `charsys_dead_code`).

**Conclusion (state-sync root cause):** to mutate live character
state (skills/subskills, pools, cash) the client must receive the
CHARSYS buffer through the path that fires **event `0x6e`** (the
single-packet CHARSYS handler), which then runs
`FUN_008447d0`→`FUN_0080b8b0` and re-derives the HUD. Delivering the
same TLV via `0x03/0x07` multipart with discriminator `0x02` only fires
`0xa8` (a no-op query) — **the buffer is never parsed, the HUD never
updates.** This is exactly the user-observed "server updates don't
reach the client".

---

## 3. Cash

Section 8 (`FUN_00846470` @ `00846470`) consumes, after a
`*param_1`(1B header) + `param_1+1`(LE32) + `FUN_00848600()`:
`local_30 = *(u16*)(param_1+5)` (count), `bVar6 = param_1[7]` (stride),
then `count × stride` GR records via `FUN_008484b0`, then trailing
fixed fields (`+0x3840`, `+0x69`, `+0x6d`, `+0x78`, `+0x7e`, `+0x80`,
`+0xa1`, `+0x54`, …). It only runs as part of the §2.3 TLV loop —
i.e. cash is applied **only when the CHARSYS buffer is parsed via the
0x6e/0xb3 path**, never standalone. The verified retail standalone
cash carrier (`0x03/0x1f → 01 00 25 13 [txn][04][cash LE32]`,
memory `cash_and_falldamage_subops`) flows through the WWORLDMGR /
`0x1f` sub-tag path (§1, a per-entity NetMessage), independent of
CHARSYS.

---

## 4. Exact Ceres divergences

### 4.1 Runtime CHARSYS state updates (skills/pools/cash) — BROKEN

`CharsysOnly.java` builds a correct section-2/section-8 TLV but sends
it as `0x03/0x07` multipart with **discriminator `0x02`**
(`DISC_CHARSYS`). Per `FUN_0055c270`, disc `0x02` fires UI event
**`0xa8`**, and `FUN_00803cd0 case 0xa8` is a **query that never calls
`FUN_008447d0`**. The TLV is therefore *never parsed* and
`FUN_0080b8b0` (HUD recompute) *never runs*.
- The disc `0x01` + 72-byte filler trick (`prependCharInfoFiller`)
  reaches event `0x13ef`, which is **not** the CHARSYS parser either —
  it is consumed as an opaque blob. Also a dead end.
- **Fix lever (from disassembly):** deliver the CHARSYS TLV through the
  single-packet CHARSYS handler that fires event **`0x6e`**
  (`FUN_00841dc0`/`FUN_008033d0` chain), OR satisfy the `0xb3` gate
  (`in_ECX[5][10] != 0`). Until then, the *only* working runtime lever
  is the established one: mutate subskills server-side and force a full
  CharInfo re-delivery at zone-load (`hud_pool_path_confirmed`).
  HP/PSI/STA maxima are never directly settable at runtime (§2.1).

### 4.2 Scripted NPC create — `WorldNPCInfo.java` (Type-15 / "Corrupted")

`WorldNPCInfo` emits reliable `0x03/0x28`. In `FUN_00541f20`, body
byte 0 = `0x28` → `case '('`: the client does
`FUN_005412d0(id2 & 0x3ff)`; if the entity does not yet exist it
**does not create it from this packet** — it sends a spawn *request*
(`0x1d`/`0x101`, the `local_30=&uStack_20; uStack_20=CONCAT21(id,0x1d)`
branch) and returns. The entity is only created when the matching
`0x1e`-spawn (or the per-entity `(vt+0x18)(desc)` parse on an
already-created object) supplies the SCRIPTEDPLAYER stream of §1.2.

The `@WWORLDMGR : Corrupted Message Type:15, Size:21` occurs because a
framed application message arrives whose **body byte 0 == 0x0F** — no
WWORLDMGR Type 0x0F exists (§1). This happens when the reassembler /
sub-packet framing is off by N bytes so the dispatcher reads an
interior byte (`0x0F`) as a Type. The pcap-pinned 17-vs-15-byte state
block in `WorldNPCInfo` (task #178c) addresses *one* misframing, but
the authoritative client model says the real lever is **the entity
ctor's `class id` + ASCII `script_name`** (§1.2): the server must
deliver the SCRIPTEDPLAYER body (class id @ stream+1 LE16, script_name
ASCIIZ @ stream+0x1d for `FUN_00699fd0`, or struct `param_3[6]` for
`FUN_0069a580`) on the path that reaches `(vt+0x18)`/the WA spawn — not
merely a `0x28` WorldInfo, which only *requests* a spawn.

Divergence summary:
- `CharsysOnly` (runtime skill/cash/pool): wrong UI event (`0xa8`
  query, not `0x6e`/`0xb3` parse) → never applied. **Primary cause of
  "GM commands don't reach the client".**
- `WorldNPCInfo` `0x03/0x28`: a spawn *request* trigger, not the
  create. Scripted NPCs need the SCRIPTEDPLAYER body (class id +
  ASCII script_name, §1.2) delivered on the per-entity create path; a
  `0x28` alone makes the client ask for a spawn it never receives, and
  any byte-misframe surfaces as `Corrupted Message Type:15`.
- `PoolUpdate.java` / `CashUpdate.java`: the verified retail cash
  carrier is a WWORLDMGR `0x1f` per-entity NetMessage (§3), independent
  of CHARSYS; pool maxima are not runtime-settable at all (§2.1).

---

## 5. Function address index

| addr | role |
|------|------|
| `0055ec10` | reliable/LC dispatch; payload only for opcode ≥0x0F (−0x0F bias) |
| `004b8cd0` | ClientNetBuffer reader → {size,bodyptr,channel}; body[0]=WWORLDMGR Type |
| `004b8fb0`/`00558950`/`00541800` | `while(FUN_004b8cd0) FUN_00541f20` loops |
| `00541f20` | WWORLDMGR dispatcher; **no case 0x0F** → "Corrupted Message Type:15" |
| `00540ab0` | WA spawn helper (Type 0x1E) → `FUN_004d8a50` |
| `004d8a50` | WA factory registry walk |
| `00567e50` | WA factory dispatch (category,type,size); registry at FX-init |
| `006b1640` | WA-type 0x1000f = SPECCAM ("Spectator", size 0x1c) — NOT an NPC |
| `005412d0` | entity lookup by (id>>8)+(id&0xff) hash |
| `0069a580` | SCRIPTEDPLAYER ctor (pre-parsed struct param_3) |
| `00699fd0` | SCRIPTEDPLAYER ctor (raw byte stream param_3) |
| `0081e310` | script-factory hash lookup; miss → "Unable to find script" |
| `00803cd0` | FULLCHARSYSTEM event dispatcher (0x6e live / 0xa8 query / 0xb3 gated) |
| `008447d0` | CHARSYS TLV section loop |
| `00845820` | CHARSYS section 2 (POOLS → HUD ceilings) |
| `00846470` | CHARSYS section 8 (cash) |
| `00846960` | CHARSYS section 4 (subskills) |
| `0080b8b0` | FullCharsysInfo recompute (fires HUD events) |
| `0055c270` | `0x03/0x07` multipart reassembler (disc 0x01→0xa7/0x13ef, 0x02→0xa8) |
| `00841dc0`/`008033d0` | single-packet CHARSYS handler → event 0x6e (the live parse) |
| `007e87d0`/`007e8930`/`007e8a20` | HUD HP/PSI/STA ticks (local timer, no net input) |
| `007e7c00` | timer×skill delta used by the ticks |
