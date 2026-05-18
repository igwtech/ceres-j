# RE_tcp_confirm.md — Authoritative per-action transaction-confirmation spec

Reverse-engineered from the retail pcap
`strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap` (server
`157.90.195.74`) and the Ghidra project
`/home/javier/Documents/Projects/Neocron/Neocron2clien`
(`neocronclient.exe`). Every claim is traced to a pcap timestamp
or a decompiled function + address. **No speculation.** Raw
decompilation dumps preserved at `/tmp/re_dump_backup/`
(`re_tcp_dispatch_dump.txt`, `re_a0_posture_dump.txt`); Ghidra
scripts at `tools/ghidra/FindTCPDispatch.java`,
`tools/ghidra/FindA0AndPosture.java`; the 0x13-unwrapper at
`tools/unwrap13.py`.

This document answers: *for each transactional action (sit,
equip/holster, use-object/interact, door/portal/zone), which
packet does the retail server send that makes the client
actually change state / play the animation, and exactly where
does Ceres diverge.*

---

## 0. Headline verdict (read this first)

The lead hypothesis was: *"sit / equip / use-object / portal are
confirmed over TCP — the client only changes state on the TCP
S→C confirmation."*

**Pcap + disassembly verdict — the hypothesis is correct for
exactly ONE action class and wrong for the rest:**

| Action | Confirmation transport | Confirmation packet |
|--------|------------------------|---------------------|
| **Zone / portal / world-change** | **TCP** ✅ | `0x83/0x0d` (begin) → `0x83/0x0c` (Location) |
| **Sit on chair** | **UDP** (`0x03/0x1f`) ❌ not TCP | first: `1f .. 17 ..` echo; then `1f .. 21 ..` broadcast |
| **Stand up** | **UDP** (`0x03/0x1f`) ❌ not TCP | `1f .. 22 ..` |
| **Use-object / interact (non-portal)** | **UDP** (`0x03/0x1f`) ❌ not TCP | per-entity `0x03/0x1f` NetMessage echo |
| **Equip / holster (toolbelt)** | **NOT IN THIS PCAP** — see §6 | unknown — needs a fresh capture |
| Generic action/state apply (rare) | **TCP** | `0x83/0x17` → UI event `0xfa8` (not exercised in this pcap) |
| Session/transaction lock-release | **TCP** | `0xa0/0x01` (login) / `0xa0/0x02` (events) — *not* per-action animation |

The user-observed symptom ("Ceres sends a UDP `firstbyte=0xd6
len=23` and the client never sits") is **not** a missing TCP
packet. `0xd6` is the LFSR-**encrypted** wire byte 0 of a `0x13`
reliable datagram (plaintext byte 0 = `0x13`); the real defect
is that Ceres sends the **wrong UDP `0x03/0x1f` sub-action** for
the *first* sit (§3.4).

---

## 1. TCP wire framing (pinned)

netlib.dll (NETMGR/SRVNETMGR) is pure Winsock transport and
strips the on-wire `fe [len LE2]` frame. The protocol handler in
`neocronclient.exe` receives the *unframed* message
`[subsystem 1B][op 1B][body…]`.

On-wire:  `fe [payloadLen LE16] [subsystem] [op] [body…]`

Decoder: `tools/pcap-decode.py -i <pcap> --server-ip
157.90.195.74 --proto tcp` (and `--proto both`).

### 1.1 The TCP NetHost dispatcher — `FUN_0055a5e0` @ `0055a5e0`

(`re_tcp_dispatch_dump.txt` lines 4796-4941.) Handles the
*session-control* socket. `cVar2 = *pcVar7` (byte 0):

```
if (cVar2 == -0x7d)            // 0x83  → enter op switch
  pcVar7[1] == 0x03            //   connection rejected / report
  pcVar7[1] == 0x05            //   "Client accepted %i" (session join)
  pcVar7[1] == -0x71 (0x8f)    //   recognised → silent
  else "@WWORLDCLIENT : Unknown NetHost Message Type: %i"
else  "@WWORLDCLIENT : Unknown Message Type: %i"
```

### 1.2 The in-game TCP dispatcher — `FUN_0055aa30` @ `0055aa30`

(`re_tcp_dispatch_dump.txt` lines 5021-5357.) **This is the
authoritative TCP S→C handler for gameplay.** `cVar3 =
*local_480` (byte 0); only `-0x7d`(0x83) and `-0x79`(0x87 =
`NETMSG_GAMEMASTERTOOL`) are accepted, everything else logs
`@WWORLDCLIENT : Unknown Message Type: %i`. Then
`switch(local_480[1])` (the op byte):

| op | retail bytes (this pcap) | client effect (decompiled) |
|----|--------------------------|----------------------------|
| `0x03` | — | connection rejected → fatal |
| `0x05` | `fe1c008305 …` t=159.79 | session accept; reads char/world ids `+2/+6/+0xa/+0xe/+0x14/+0x18` → `in_ECX+0x2b0…` ; state `0x2a8=2,0x2ac=1` |
| **`0x0c`** | `fe1d00830c 65000000 00000000 00000000 "plaza/plaza_p3\0"` | **WORLD/ZONE CHANGE.** ASCIIZ worldname @`+0xe` → `.\worlds\%s.bsp`; coords @`pcVar9+2/+6/+0xa` → `in_ECX+0x150/+0x154/+0x158`; state `0x2c4=0x101,0x2a8=2,0x2ac=5`; logs `WORLDCLIENT : new world name received %s %i` |
| **`0x0d`** | `fe0400830d 0000` t=238.35 | **ZONE TRANSITION BEGIN.** `" connecting ... please wait..."`; `FUN_004d23b0(0x3f2,0,0)` (loading UI); state `0x2a8=6` |
| `0x17` | *(not present in this pcap)* | generic per-entity action/state apply: parses name@`+9`, cmd-type `pcVar9[7]` (0x00–0x05/0x80/0x81/0x82/0xfd/0xfe/0xff) → UI event **`0xfa8`** size 0x1c |
| `0x18` | *(not present)* | DCB-style query reply → UI event `0xfac` |
| `0x88` | *(not present)* | wraps body into a `0x15`-typed reliable msg `[0x15][id&0x3ff LE2][tag][payload]` → WWORLDMGR enqueue (`in_ECX+0x58` vt+0x18) — TCP→UDP bridge |
| `0x8f` | `fe0700838f 0000000000` ×13 | **`case -0x71: break;` — NO-OP. Pure ~10.9 s keepalive.** |
| default | — | `@WWORLDCLIENT : Unknown NetHost Message Type` |

### 1.3 The `0xa0` subsystem is NOT in either WorldClient dispatcher

Both `FUN_0055a5e0` and `FUN_0055aa30` reject any byte-0 that is
not `0x83`/`0x87`. The retail `0xa0/0x01` (`fe0a00a001
150000000000803f`) and `0xa0/0x02` (`fe0a00a002 …`) frames are
consumed by a *separate* session/UI TCP socket, not the
WorldClient gameplay handler. Payload is the invariant
`15 00 00 00 00 00 80 3f` (`0x15` + zeros + float `1.0`).
Timeline: `a0/01` at t=157.13, 159.49 (post-auth, paired with
C→S `fe0200a003`); `a0/02` at t=171.49, 232.59, 293.72
(server-initiated, ~event-driven). **It is a session-level
transaction/lock-release ack, not the per-action animation
trigger** (consistent with `InteractionAck.java`'s own javadoc).

---

## 2. Zone / portal / world-change — TCP-confirmed ✅ (hypothesis correct)

Ground truth (this pcap, `--proto both`):

```
t=238.170  C→S UDP  0x03/0x22/0x03  0300035a86010000000000000000000000   (zone-transition request)
t=238.352  S→C TCP  0x83/0x0d  fe0400830d0000                            (BEGIN: loading screen)
t=238.847  S→C TCP  0x83/0x0c  fe1d00830c 65000000 00000000 00000000 "plaza/plaza_p3\0"   (LOCATION)
t=238.853  C→S UDP  0x03/0x08  ReliableAck
t=239.052+ S→C UDP  0x03/0x2e,0x2f,…  new zone entity stream
```

`0x83/0x0c` body layout (verified against `FUN_0055aa30
case '\f'`):

```
83 0c [zoneId LE32] [LE32 #1] [LE32 #2] [worldname ASCIIZ]
        +2..+5       +6..+9     +0xa..+0xd  +0xe…
```

Retail sample 1: `83 0c 01 00 00 00 | 00 00 00 00 | 00 00 00 00 |
70 6c 61 7a 61 2f 70 6c 61 7a 61 5f 70 31 00` = zoneId 1,
`"plaza/plaza_p1"`.
Retail sample 2 (t=238.85): zoneId `0x65`,
`"plaza/plaza_p3"`. The session toggles p1↔p3 ~10 times
(t=238 → 296) as the player walked the plaza-sector boundary —
each crossing is a TCP `0x83/0x0c`, **no UDP coordinate is
sent** for it.

The `FUN_0055aa30 case '\f'` strips a `terrain/` prefix to pick
`.\worlds\%s.bsp` vs `.\%s.bsp`, then sets the world-change
state machine; `FUN_00558950` (the WorldClient pump) later
consumes it (`"WORLDCLIENT : Load World %s"`). **The client only
performs the zone transition on receipt of TCP `0x83/0x0c`** —
this is the one action class where the lead hypothesis holds.

### 2.1 Ceres status — zone/portal

`server_tcp/Location.java` builds
`83 0c [location LE32] [1|0 LE32] [0 LE32] [worldname] 00`.
**Byte layout matches retail exactly** (zoneId, flag, zero,
ASCIIZ name). `server_tcp/Packet830D.java` builds
`83 0d 00 00` — **matches retail** `fe0400830d0000`. Gap is
*not* byte-fidelity; it is **emission ordering / triggering**:
retail sends `0x83/0x0d` *then* `0x83/0x0c` in response to the
C→S UDP `0x03/0x22/0x03` zone-transition request, and the client
then ReliableAcks (`0x03/0x08`) and the new zone stream begins.
Any Ceres path that changes a player's zone **must emit
`Packet830D` then `Location` over TCP** (in that order) or the
client never runs the world-load state machine
(`FUN_0055aa30 case 0x0d/0x0c` → `FUN_00558950`). Verify the
walk-boundary p1↔p3 case also emits `0x83/0x0c` (retail does it
~10× per session with no UDP coords).

---

## 3. Sit on chair — UDP-confirmed ❌ (hypothesis wrong: NOT TCP)

### 3.1 The C→S request (UDP, inside the `0x13` reliable channel)

`0x03/0x1f` sub-action `0x17`:

```
1f [subjectLocalId LE16] 17 [rawObjectId LE16…] 00
```

5 sit requests in this pcap (unwrapped via `tools/unwrap13.py`):

| t | inner `0x1f` data | objectId |
|---|-------------------|----------|
| 199.387 | `03 00 17 00 c8 0c 00` | `0x0cc8` |
| 204.188 | `03 00 17 00 48 08 00` | `0x0848` |
| 208.321 | `03 00 17 00 48 08 00` | `0x0848` |
| 211.237 | `03 00 17 00 48 08 00` | `0x0848` |
| 216.386 | `03 00 17 00 44 08 00` | `0x0844` |

### 3.2 The S→C confirmation (UDP `0x03/0x1f` — NO TCP)

Two distinct forms:

**(a) First sit on a given object — direct `0x17` echo:**
```
t=199.387  C→S  1f 03 00 17 00 c8 0c 00
t=199.834  S→C  1f 03 00 17 00 c8 0c 00     ← byte-identical echo
```

**(b) Subsequent sits / refresh — `0x21` posture broadcast:**
```
1f 03 00 21 00 [rawObjectId LE32] 00
```
e.g. `0300210048080000` (objectId `0x0848`, seatId 0), emitted
~4× per sit + a ~3 s refresh while seated:
t=204.561(×4),204.718,205.056 ; 208.551(×2),208.845(×3) ;
211.597(×4),211.917 ; 216.660(×3),216.784 (objectId `0x0844`).

**There is NO TCP packet anywhere in the t=199–217 sit window**
(the only TCP between t=187 and t=232 is the `0x83/0x8f`
keepalive). Sit confirmation is 100 % on the UDP `0x03/0x1f`
per-entity NetMessage path.

### 3.3 Ghidra — the client sit applier

The S→C `0x03/0x1f` rides the `0x13` reliable splitter
(`RE_state_sync.md` §0.3) → WWORLDMGR `FUN_00541f20` Type `0x17`
("per-entity action", vt+0x20) → **`FUN_0064ec90` @ `0064ec90`**
(the PlayerAction dispatcher, `switch` on the `0x1f`
sub-action byte; `re_a0_posture_dump.txt` line 9262):

- **`case 0x17`** (line 9435): logs `"Use Item msg"`,
  `iVar9 = thunk_FUN_005412d0(*(puVar1+4))` (entity lookup by
  id), then `thunk_FUN_007a4890(<ctrl>, iVar9, 1)` — **this is
  the call that puts the local player into the seated state /
  plays the sit animation.** Reached only by the *direct `0x17`
  echo* (form a).
- **`case 0x21`** (line 9522):
  `thunk_FUN_00662c00(*(puVar1+4), puVar1[8], 1)` — the
  *observed* posture-state broadcast applier (form b).
- **`case 0x22`** (line 9526): pos (`FUN_0054e210`) + rot
  (`FUN_0054e100`) → `FUN_0064e1c0(pos,rot,1)` — **stand-up +
  reposition**.
- `case 0x4c` (line 9973) exists too (weapon-draw sub-action,
  see §6).

### 3.4 Ceres status — sit (the exact gap)

`server_udp/SitOnChair.java` emits **only form (b)**:
`1f <localId LE2> 21 <rawObjectId LE32> <seatId>` — byte-correct
for the `0x21` broadcast, routes to `FUN_0064ec90 case 0x21`
(`FUN_00662c00`, the *observed* posture applier). It **never
emits the first-time `0x17` echo** (form a) that
`FUN_0064ec90 case 0x17` → `FUN_007a4890` needs to transition
the **acting player's own** seated state.

`client_udp/UseItem.java` calls `z.sendPlayerSit(...)` →
`Zone.sendPlayerSit` (Zone.java:353) which loops all receivers
sending `new SitOnChair(...)` (the `0x21` form) to everyone
including the sitter. Result: every client updates the *peer*
posture of the sitter, but the **sitter's own client never gets
the `0x17` echo**, so `FUN_007a4890` is never called and the
local player does not sit — exactly the reported symptom.

**Precise fix lever (spec, no code here):** on a validated chair
use, the server must send the acting player a UDP `0x03/0x1f`
sub-action **`0x17`** echoing its request bytes
(`1f <sitterLocalId LE2> 17 <rawObjectId LE16> 00`) *in addition
to* the `0x21` broadcast to observers. Retail does the `0x17`
echo once per new object, then `0x21` for refresh/observers.

### 3.5 Ceres status — `Packet838F` is mis-modelled

`UseItem.java` sends `Packet838F` (`0x83/0x8f`) on every
sit/use, and `Packet838F.java`'s javadoc claims it is an
"interaction-state-commit ack". **Disproven:** in
`FUN_0055aa30` the `0x8f` op is `case -0x71: break;` — a literal
no-op. In this pcap `0x83/0x8f` arrives 13× at a strict
~10.9 s period (deltas: 10.79, 10.86, 10.93, 10.93, 10.83,
10.85, 10.98, 10.82, 10.91, 10.95, 10.95, 10.96 s),
**uncorrelated with the 5 sits at t=199–216**. `0x83/0x8f`
is a pure keepalive; emitting it per-action is non-retail
(harmless to the client — it is dropped — but it is not the
sit commit and should not be relied on as one).

---

## 4. Stand up — UDP-confirmed ❌ (NOT TCP)

C→S `0x03/0x1f` sub-action `0x22` (3-byte: `03 00 22`) at
t=206.37, 209.59, 212.40, 217.42 (interleaved with the re-sits).
Applied by `FUN_0064ec90 case 0x22`
(pos+rot → `FUN_0064e1c0`). No TCP involved. Ceres
`server_udp/ExitSeat.java` / `Zone.sendPlayerExitSeat`
(Zone.java:373) emits `0x03/0x1f/<localId>/0x22` — transport &
sub-action correct; same first-person-vs-observer caveat as §3.4
applies (verify the actor itself receives a `0x22` it can apply).

---

## 5. Use-object / interact (non-portal) — UDP-confirmed ❌

Same channel as sit: the client's C→S `0x03/0x1f` use packet is
confirmed by a S→C `0x03/0x1f` per-entity NetMessage echo routed
through `FUN_00541f20` Type-0x17 → `FUN_0064ec90`. The recurring
S→C `0x1f 03 00 25 23 24` / `… 25 13 …` payloads are the
per-entity "use result / object-state" NetMessages on the same
path (`0x25` = object-state sub-tag). No TCP `0x83/0x17` was
exercised in this pcap; the generic TCP action-apply path
(`FUN_0055aa30 case 0x17` → UI event `0xfa8`) exists but is used
for a different (rarer) class of server-pushed state, not the
basic use-object echo.

---

## 6. Equip / holster (toolbelt) — NOT CAPTURED — fresh pcap needed

The lead said equip C→S = `1f 00 00 1f <slot0based>`. **That
byte pattern does not occur in this pcap.** Full inventory of
C→S `0x03/0x1f` sub-actions present (after the `03 00`/`01 00`
localId): `0x3d` (3072, aim/move), `0x01`/`0x03` channel-duality
movement, `0x17` (sit, 7), `0x1a` (8, dialog/use), `0x22`
(stand, 4), `0x4c` (weapon-draw, 30 — `03 00 4c 0f 00 03 00`
at a fixed ~10 s cadence for the whole session, i.e. a periodic
weapon-state refresh, **not** a discrete equip/holster event),
`0x32` (2), `0x3e` (1), `0x19` (3). No isolated equip/holster
toolbelt-slot action was performed by the captured player in a
byte-distinguishable way.

`FUN_0064ec90` *does* have a `case 0x4c` (line 9973) — the
weapon-draw/equip applier — so the equip confirmation, like
sit, is on the **UDP `0x03/0x1f`** per-entity path (sub-action
`0x4c`), **not** TCP. But the precise C→S→confirm byte mapping
for a *discrete toolbelt equip/holster* cannot be pinned from
this capture.

**Still needed:** a retail pcap where the player performs a
clean, isolated toolbelt **equip** then **holster** (drag a
weapon to/from a toolbelt slot, or press the holster key), with
no movement, ideally standing still, so the discrete C→S `0x1f`
sub-action and its S→C `0x1f`/`0x4c` echo can be byte-pinned and
the `FUN_0064ec90 case 0x4c` body layout decompiled in full.

---

## 7. Exact Ceres TCP/confirmation gaps (summary)

1. **Sit (primary defect).** `SitOnChair.java` emits only the
   `0x21` observer-posture broadcast; the acting player never
   receives the first-time `0x03/0x1f` sub-action **`0x17`**
   echo that `FUN_0064ec90 case 0x17` → `FUN_007a4890` requires
   to seat the local player. Add the per-actor `0x17` echo
   (§3.4). This is the "client never sits" root cause — it is a
   **UDP** payload bug, not a missing TCP packet.

2. **`Packet838F` (`0x83/0x8f`) mis-modelled.** It is a no-op
   keepalive in the client (`FUN_0055aa30 case -0x71: break;`),
   on a strict ~10.9 s period, uncorrelated with interactions.
   Treating it as the interaction-commit (and sending it
   per-use in `UseItem.java`) is non-retail. The true
   interaction-commit on the UDP path is the `0x03/0x1f` echo
   itself; there is no separate TCP "commit" for sit/use.

3. **Zone/portal ordering.** `Location.java` (`0x83/0x0c`) and
   `Packet830D.java` (`0x83/0x0d`) are byte-correct, but a Ceres
   zone change must emit **`0x83/0x0d` then `0x83/0x0c`** over
   TCP in response to the C→S UDP zone-transition request, and
   must also emit `0x83/0x0c` for plaza walk-boundary sector
   crossings (retail: ~10×/session, no UDP coords). This is the
   one action where the TCP-confirm hypothesis is correct;
   ensure every zone-change code path actually sends both TCP
   packets.

4. **`InteractionAck` (`0xa0/0x02`) is not a per-action
   animation trigger.** It is not even dispatched by the
   WorldClient TCP handler (`FUN_0055a5e0`/`FUN_0055aa30` reject
   byte-0 ≠ 0x83/0x87). It is a session-level
   transaction/lock-release ack on a separate TCP socket. Wiring
   it per-interaction (as `UseItem.java` does, ×2) does not make
   the client sit/equip; it only affects long-session state
   bookkeeping. Harmless but do not treat it as the confirmation.

5. **Equip/holster** — confirmation path is UDP `0x03/0x1f`
   sub-action `0x4c` (`FUN_0064ec90 case 0x4c`), **not TCP**,
   but the discrete C→S/S→C byte mapping is unpinnable from this
   pcap. Needs the dedicated capture described in §6 before any
   Ceres equip-confirm work.

---

## 8. Function / address index (this document)

| addr | role |
|------|------|
| `0055a5e0` | TCP NetHost/session-control dispatcher (`*pcVar7==0x83`; ops 0x03/0x05/0x8f) |
| `0055aa30` | **TCP in-game dispatcher** (`*local_480∈{0x83,0x87}`; `switch(local_480[1])`: 0x0c zone, 0x0d zone-begin, 0x17 generic-apply, 0x88 TCP→UDP bridge, 0x8f no-op) |
| `00558950` | WorldClient world-load pump (consumes 0x0c/0x0d state; `"Load World %s"`) |
| `0064ec90` | **PlayerAction dispatcher** (`switch` on `0x03/0x1f` sub-action: `case 0x17` sit→`FUN_007a4890`, `case 0x21` posture→`FUN_00662c00`, `case 0x22` stand→`FUN_0064e1c0`, `case 0x4c` weapon-draw) |
| `007a4890` | applies sit/use to a resolved entity (the actual local-seat transition) |
| `00662c00` | applies the observed `0x21` posture broadcast |
| `0064e1c0` | applies stand-up reposition (`case 0x22`) |
| `005412d0` | entity lookup by id (shared with `RE_state_sync.md` §1) |
| `00541f20` | WWORLDMGR Type dispatcher (Type 0x17 → vt+0x20 → `FUN_0064ec90`) |

Cross-reference: `docs/protocol/RE_state_sync.md` (UDP
`0x13`/`0x03` framing & WWORLDMGR layer),
`memory/project_opcode_structure.md` (TCP high-byte=subsystem,
`0x83xx` family), `memory/zone_cross_2phase_handshake.md`
(zone-cross reliable handshake).
