# Progress: NCE 2.5 "Connection to worldserver failed" investigation

**Last updated:** 2026-04-11

## Symptom

Modern retail Neocron 2 client (NCE 2.5.766) connecting to Ceres-J:

1. TCP auth succeeds (InfoServer on 7000 → GameServer on 12000)
2. UDP handshake completes (3x 0x01 packets)
3. Client shows **"Synchronizing into City Zone"** loading screen
4. BSP file loads successfully
5. Client logs **"WorldClient: Joining session . . ."**
6. **Exactly 15 seconds later** the client shows
   `WORLDCLIENT : CONNECTION TO WORLDSERVER FAILED` and bounces back to the
   login screen. No crash, graceful error return.

The failure is on the normal-path sync state machine — BSP load and the
"Synchronizing" UI event both fire, proving a lot of the protocol is
working, but the final "Joining session" step never completes because
the client's session-target fields contain uninitialized values.

## Current state (end-of-session 2026-04-11)

All four progressive experiments tried tonight (A, B-1, B-2, B-3) are
**reverted**. `WorldEntryEvent.java` is back to the baseline burst that
was committed in `fb1eb0f`. 111/111 unit tests green. Container rebuilt
and running. Behavior matches baseline.

**Unsolved.** The root cause is identified at the state-machine level
(WorldClient fields `+0x2d4/+0x2d8` are never populated by any packet
Ceres-J sends) but no experimental packet unblocked it, likely because
the dispatch path into `FUN_0055aa30` case `'\x05'` ("Client accepted"
NetMgr message) requires wire framing we haven't fully mapped.

## Confirmed facts

### Protocol/architecture

- **Three network channels** used by the modern client:
  1. **TCP port 7000** — InfoServer (login / auth / server list).
     `NETBASEIP` in `neocron.ini` points here.
  2. **TCP port 12000** — GameServer (post-login game session). Client
     is handed off here via the InfoServer's `0x83 0x83` "worldhost
     info" packet which embeds
     `[IP=c0 a8 44 6c=192.168.68.108][port=e0 2e=12000]["Ceres-J Docker\0"]`.
  3. **UDP per-player ports 5001–5999** — allocated by
     `UdpPortPool.java` when `AuthB` runs. GameServer UDP traffic.

- **Ceres-J's PatchServer on port 8020 is NOT part of the login flow.**
  The file `patchserver/PatchHandshakeA.java` sending `80 01 0x73` is
  unused by the client. This bit me once tonight — initial grep made
  me think there was a byte mismatch, but the actual handshakes come
  from `infoserver/HandshakeA.java` and `gameserver/HandshakeA.java`,
  both of which correctly send `80 01 0x66`.

- **NetMgr password handshake** at the TCP layer: server sends
  `{0x80, pos, expected_char}` where `expected_char` must equal
  `"xfghsdkjskfdlgj"[pos]`. Observed in strace:
  - `{0x80, 0x01, 0x66}` → `password[1]='f'` ✓
  - `{0x80, 0x03, 0x68}` → `password[3]='h'` ✓

### Retail wire format

- **Retail S→C UDP is encrypted.** Stats across 4 captures
  (`/tmp/nc2_strace_RETAIL_*.log`):
  - Overall entropy: **7.988–7.993 bits/byte** (essentially random)
  - First-byte uniqueness: 77–92%
  - Null-byte frequency: 0.35%
  - Per-position entropy saturates at log₂(sample size)

- **`PacketObfuscator`'s per-packet XOR is NOT the retail cipher.**
  Applying the XOR to retail packets leaves entropy unchanged at ~7.99.
  The per-packet XOR we have in Ceres-J might be correct for older
  clients but modern NCE 2.5 uses something else (probably
  `ObfuscateStreamBuf` with per-stream position state).

- **The client accepts plaintext anyway.** Our Ceres-J sends plaintext
  and the client processes it correctly (CharInfo reaches
  `FUN_0055c270`, BSP loads, `Synchronizing` UI event plays). This is
  because plaintext first-bytes `{0x04, 0x13}` match the known-header
  set, so the decryption layer computes `seed = header ^ header = 0`
  and passes the data through unchanged.

### State machine (Ghidra-confirmed)

- **`FUN_0055ceb0`** — master tick loop. Each tick calls `FUN_0055aa30`
  (NetMgr msg pump), then `FUN_0055b6f0` (state machine) if
  `+0x2a8 ∈ {2,3,4}`, and `FUN_00558950` directly if
  `+0x144 != 0 && +0x2e5 != 0 && +0x2ac == 4`.

- **`+0x2a8`** — outer state. 1=pre-connect, 2=connected, 6=reconnect.
- **`+0x2ac`** — inner state. 1=joining, 2=waiting-for-join,
  3/6=sync-req, 4=in-world, 5=world-change-pending, 7=client-up-to-date.
- **`+0x2ae`** — sync-bit field. bit0=CharInfo, bit1=CharsysInfo,
  bit2=?, bit3=Changepos. Ready when low nibble == 0xF.

- **Two state-machine functions**: `FUN_0055b6f0` (active, called from
  tick) and `FUN_0055bdc0` (alternate variant). My earlier claim that
  `FUN_0055bdc0` has 0 refs was a **Ghidra thunk-resolution artifact**;
  the Init.Log proves both are called. They log slightly different
  strings on the same transition.

- **Writers to `+0x2ac`** (all 8 literal writes accounted for):
  | Function | Writes | Context |
  |---|---|---|
  | `FUN_0055a5e0` | `=1` | NetHost "Client accepted" (case 0x05) |
  | `FUN_0055aa30` | `=1`, `=5` | NetMgr msg pump: case 0x05, case 0x0c |
  | `FUN_0055b6f0` | `=2`, `=1`, `=5`, `=1` | State machine — case 1→2, case 2→1 timeout, case 5 self-loop, case 7→1 |
  | `FUN_0055bdc0` | `=2`, `=1` | Alternate variant |
  | `FUN_00558950` | `=7`, `=5/6` | Client-up-to-date, then post-BSP-load |
  | `FUN_00559050` | `=1` | Full reconnect reset |
  | `FUN_00559920` | `=4`, `=3` | case 3 sync response (3/6→4), case 0x19/0x05 World Change denied (=3) |

- **No literal write of `+0x2ac = 3` exists on the normal login path.**
  Only in the abnormal "World Change denied" branch (`FUN_00559920`
  case 0x19/sub 0x05), gated by `+0x145 != 0 && +0x147 == 0`.

- **`FUN_00559520` — the "Joining session" handler** — reads
  `+0x2d4/+0x2d8/+0x278/+0x2f0`, NOT `+0x2cc/+0x2d0`. Critical: those
  fields are populated ONLY by `FUN_0055aa30` case `'\x05'`
  ("Client accepted" NetMgr msg). If that case never fires, the fields
  stay at their constructor-set values and the session-create fails
  silently (UDP sends don't error, 15s timeout in state-machine case 2
  fires).

- **`FUN_00559920`** is the gamedata dispatcher. Switch cases:
  | case | meaning |
  |---|---|
  | 1 | time sync (9 or 13 bytes) — no state check |
  | 3 | sync response (13 bytes, requires state 3 or 6) → writes `+0x2ac = 4` |
  | 0x19 | sub-switch with {4 SRV info, 5 World Change denied, 6, 7 cmd id ack, 0x0a Changepos, 0x0c world change triggered, 0x0e, 0x0f, 0x12, 0x20} |
  | 0x21 | vtable passthrough |
  | 0x22 | CharInfo/CharsysInfo → routes to `FUN_0055c270` |
  | 0x24 | `FUN_00555b50` |
  | 0x29 | batch loop |
  | 0xb1 | `FUN_00541f20` |

- **Dispatch dead-end**: `FUN_00559920` has 1 caller (a thunk). Where
  the thunk is called from, and whether our Ceres-J gamedata packets
  actually route into this function, is unclear. The `cases` in the
  switch don't match `PROTOCOL.md`'s reliable sub-type table
  (0x07, 0x0d, 0x25, 0x2c, 0x2e, 0x2f, 0x30). `FUN_00559920` is
  probably a sub-dispatcher for ONE sub-type (likely 0x1f "GamePackets")
  and the cases 1/3/0x19/0x22 are the sub-sub-types within that family.

### Client ground truth (from `/home/javier/Neocron2/logs/`)

**`Init.Log` on failed login:**
```
WorldClient: Connecting to NetHost . . . succeed!
WorldClient: Delete world for world change . . .
  WorldManager: Shutting down . . . (cleanup)
WorldClient: Create new world to change to . . .
  WorldManager: Getting start position.
  FX-System for worlds/actors/other created.
WorldClient: World changes successfull!
WorldClient: Joining session . . .
WorldServer: Connecting to WorldServer failed!
WORLDCLIENT : Connection failed : WORLDCLIENT : Connection to worldserver failed
```

**`error_*.log` on failed login:**
```
@PWORLDHOST Connect to 1816438976, 12000
Reading desc from file: .\worlds\plaza/plaza_p1.dat successful
HostName : 0<garbage>
WINSOCKMGR : Socket created on 0
Std HostIP : 0.0.0.0
Bind recv socket HostIP 0, Addr:0, Port:0
Bind send socket HostIP 0, Addr:0, Port:1
@WWINSOCKMGR : Receive 0 Buffer     (x 17)
Exception created WORLDCLIENT : Connection failed : WORLDCLIENT : Connection to worldserver failed
```

- **`1816438976` decodes as little-endian `c0 a8 44 6c` = `192.168.68.108`**
  (Javier's LAN IP). Combined with port 12000 it's the TCP game server
  address, so `+0x268/+0x26c` are correctly set from whatever packet
  populates them. The log is not evidence of a bug — it's printing the
  right value in a slightly misleading `%i` format.
- `HostName : 0<garbage>` — uninitialized local hostname buffer. The
  client's `gethostname()` call probably failed (see the string
  `SOCKETMGR : Get Hostname failed` in the binary).
- The `Receive 0 Buffer` x 17 followed by `Exception created` after 15s
  is the fingerprint of `FUN_0055b6f0 case 2` timing out while waiting
  for a session-join response that never arrives.

### Ceres-J server log on failed login

```
AuthB: key=0xad spot=0 usernameLen=9 passwordLen=9 pos=26 bufSize=53
AuthB: user='msn3wolf' spot=0
AuthB: player activated, sessionID=c5 0d 41 c2 cb 03 04 78 (transformed: ba 72 3e bd b4 7c 7b 07)
AuthB: allocated UDP port 5001 for 'msn3wolf'
PlayerUdpListener: started on port 5001 for 'msn3wolf'
GetGamedata / GetGamedataAnswer / GetUDPConnection events fire
UDP[5001] received 14 bytes header=0x01  (x 3 handshakes)
HandshakeUDPAnswer → HandshakeUDPAnswer2 → scheduling world entry
WorldEntryEvent: streaming world state for Asddf mapId=1
UDP send ... firstbyte=0x04 (UDPAlive)
UDP send ... firstbyte=0x13 (x 9-12 gamedata packets)
WorldEntryEvent: completed for Asddf
(60 seconds later)
PlayerUdpListener: stopped port 5001
```

Server side is functioning. 15s later the client bounces back to
login; 60s from WorldEntryEvent the per-player UDP listener times out
on the server side.

### Packet-level diff (from strace)

Comparing `/tmp/nc2_strace_RETAIL_ACC1_CHAR1.log` vs
`/tmp/nc2_strace_CERESJ_OPTB3.log`:

| Direction | Retail | Ceres-J |
|---|---|---|
| UDP S→C (`recvmsg`) | 88 packets, diverse sizes (20B x22, 446B x9, 314B x5, 69B x5, 30B x4 ...) | 13 packets (one WorldEntryEvent burst only) |
| UDP C→S (`sendmsg`) | 72 packets, diverse sizes (40B x11, 16B x10, 84B x8, 28B x7, 14B x3 ...) | **3 packets (only the initial handshakes)** |

**The "Ceres-J sends 0 C→S packets after the handshake" is the most
diagnostic single fact.** The retail client sends ~70 packets back to
retail in the same timeframe — sync-req, position-update, ping, etc.
Ceres-J gets zero. The client's state machine is stuck in a state that
produces no outbound traffic (case 2 in `FUN_0055b6f0` just idles for
15 seconds and errors out; cases 3/6 and later would send things).

## Hypotheses: confirmed, falsified, open

### Confirmed (2026-04-12 session)

- **Retail UDP cipher CRACKED** — the wire cipher is NOT ObfuscateStreamBuf.
  It is a per-packet LFSR with cipher-feedback (CFB) mode, implemented in
  the WinSockMGR sendto/recvfrom wrappers:
  - `FUN_00560090` — encrypt (sendto wrapper)
  - `FUN_0055ff30` — decrypt (recvfrom wrapper)
  - `FUN_004e36e0` — 16-bit LFSR PRNG with data feedback
  - Wire format: `[seed_lo][seed_hi][enc_len_lo][enc_len_hi][enc_data...]`
  - Each packet carries its own random 16-bit seed in bytes 0-1 (in the clear)
  - Validated: 88/88 retail packets decrypt to valid 0x13 gamedata with
    sequential counters. Entropy drops from 7.99 to 6.08.
  - Full documentation: `docs/PROTOCOL.md` "UDP Wire Encryption" section
  - Python tool: `tools/decrypt-retail.py`
  - Decoded retail burst: `docs/retail_decoded_burst.txt`

- **Retail burst fully parsed** — all 88 S→C packets decoded and
  sub-packet structure mapped. Key differences vs Ceres-J documented
  in `docs/retail_burst_analysis.md`:
  - Retail sub-packet lengths are 2 bytes LE (Ceres-J uses 1 byte)
  - Retail does NOT send ZoningEnd during login
  - Retail sends InfoResponse (0x23) × 2 and ChatList (0x33) in the
    initial burst; Ceres-J never sent these
  - 10 CharInfo multipart fragments in retail vs our 3
  - Undocumented 0x02 sub-wrapper used for UpdateModel/Weather/TimeSync
  - No 0x19/0x04, 0x19/0x07, or SyncResponse packets in retail —
    proving all our earlier experiments were chasing the wrong targets.

- **Cipher implemented server-side** — `WireEncrypt.java` ports the
  LFSR CFB cipher to Java. `GameServerUDPConnection.sendPacket()` now
  calls `WireEncrypt.encrypt()` on every outgoing datagram, prepending
  the correct 4-byte wire header. This fixes the fundamental plaintext
  misrouting bug where our `0x13` bytes were being stripped by the
  client's WinSockMGR `-0x0F` offset and routed to the wrong handler.

- **WorldEntryEvent updated to match retail** — removed ZoningEnd,
  CommandIdAck, SyncResponse; added InfoResponse (zone + session
  variants) and ChatList; reordered phases to match retail's flow.
  Experimental packet files (`NetHostWorldName.java`, `SyncResponse.java`,
  `WorldInfoSrv.java`, `CommandIdAck.java`) remain in the repo as
  reference but are no longer wired into the burst.

### Falsified

- **"Retail S→C is plaintext with new opcodes"** (user's hypothesis):
  entropy analysis proved the bytes are cipher output (7.99 bits/byte,
  nearly-uniform first-byte distribution).
- **"`ObfuscateStreamBuf` is unused vestigial PAK/file code"**: the
  high entropy of retail captures and the vtable[3/6/7] cipher methods
  documented in `docs/PROTOCOL.md` are strong evidence `ObfuscateStreamBuf`
  IS a wire cipher, at least for some sessions. (Left unresolved:
  whether it's the cipher for our failure session, because we can't
  decrypt retail captures to compare.)
- **"`PacketObfuscator.java` is the correct wire cipher"**: entropy
  stayed at ~7.99 after applying its XOR to retail captures. It is NOT
  the retail cipher.
- **"`FUN_0055bdc0` and `FUN_0055a5e0` are dead code"**: my
  `FindCallers.java` script reported 0 references, but the client's
  Init.Log clearly executes both (the "Connecting to NetHost . . .
  succeed!" line comes from `FUN_005592d0` and "Joining session" from
  `FUN_00559520`, both of which live in the bodies of what I thought
  were dead functions). The call graph needs thunk resolution to find
  the real callers.
- **"`+0x268/+0x26c` contain garbage memory"**: `1816438976` is a
  perfectly valid `192.168.68.108` once decoded as little-endian.
  That field is fine.
- **"`PatchHandshakeA.java` sends the wrong byte (`0x73` instead of
  `0x66`)"**: confirmed red herring — `PatchServer` binds port 8020
  which the client never talks to. The active handshake packets are
  in `infoserver/HandshakeA.java` and `gameserver/HandshakeA.java`,
  both correctly sending `0x66`.

### Confirmed

- **The state machine is active `FUN_0055b6f0` case 2 that times out
  at 15s.** The error message `"WorldServer: Connecting to WorldServer
  failed!"` is uniquely attributable to that case in the decompile.
- **The blocker is that `+0x2d4/+0x2d8` are never populated**, because
  the packet that populates them (`FUN_0055aa30` case `'\x05'`
  "Client accepted" NetMgr msg) never reaches the NetMgr receive queue.
- **The client accepts plaintext UDP from Ceres-J.** Verified by the
  strace showing our plaintext bytes being received by the client, and
  by the fact that BSP load and `Synchronizing` UI events fire in
  response to Ceres-J's packets.

### Open / untested

- **Does the client's NetMgr queue read from TCP, UDP, or both?** The
  NetMgr object is initialized with port 0 (not 9000) in `FUN_005592d0`,
  suggesting it's a stub that doesn't bind a socket. Messages may come
  from TCP via a router we haven't traced. If so, our TCP packets
  `0x83 0x05` ... might already reach the NetMgr queue but be rejected
  because of length or other framing.
- **What's the exact wire layout for a packet that reaches
  `FUN_00559920` case `0x19` sub `0x04`?** The decompile reads from
  `puVar1[3..19]` but we don't know the wrapper layers above it.
  Ceres-J's existing `0x13 → 0x03` framing doesn't reach the `0x19`
  switch; probably needs an additional `0x1f` "GamePackets" sub-type.
- **Is there a `0x19 0x07` "command ID" message in retail's flow that
  Ceres-J never sends?** `FUN_00558950`'s state advancement chain
  (5 → 7 → BSP load → 6 → 4) requires `+0x146 != 0`, which is set by
  a prior command-ID message we don't emit.

## Experiments attempted (all reverted)

### Option A: Compare retail C→S to Ceres-J C→S

Goal: diff the outbound traffic to see which packets retail's client
sends that Ceres-J's doesn't. Would show the missing stimulus.

Blocked: can't decrypt either side's traffic. The stats comparison via
`tools/compare-packet-sizes.py` is still valuable — it showed the
"0 C→S packets after handshake" fingerprint — but it can't identify
specific missing packets.

### Option B-1: Raw UDP `0x83 0x0c` NetHost "world name" (`NetHostWorldName.java`)

File kept at
`src/main/java/server/gameserver/packets/server_udp/NetHostWorldName.java`.
Not wired in.

Sent as a raw 29-byte UDP datagram with first byte `0x83`. Server log
confirmed the send. Strace confirmed the client's kernel received it
(`\x83\x0c\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00plaza_p1\0`).

Result: no effect. Still 3 C→S handshakes, still 15s timeout.
Hypothesis: first-byte `0x83` is not in `{0x01,0x03,0x04,0x08,0x13}`
so the client's UDP dispatcher silently drops it before it reaches
any handler.

### Option B-2: Inner `0x03` sync response (`SyncResponse.java`)

File kept at
`src/main/java/server/gameserver/packets/server_udp/SyncResponse.java`.
Not wired in.

Wrapped in `0x13 → 0x03` (PacketBuilderUDP1303). Inner payload is 13
bytes starting with opcode `0x03` followed by server timestamp,
client echo timestamp, and two ushorts. This is exactly what
`FUN_00559920` case 3 expects.

Result: no effect. The handler requires state to already be 3 or 6
before the packet is processed; state is stuck at 2. Packet is
silently ignored.

### Option B-3: WorldInfoSrv `0x19 0x04` (`WorldInfoSrv.java`)

File kept at
`src/main/java/server/gameserver/packets/server_udp/WorldInfoSrv.java`.
Not wired in.

21-byte inner payload `[0x19][0x04][pad][ipLE][port][pad][entity][cmdid][pad]`
wrapped in `0x13 → 0x03`. Targets `FUN_00559920` case `0x19` sub `0x04`
which writes `+0x2cc/+0x2d0/+0x15c/+0x2ec`.

Result: no effect. Two problems in retrospect:
1. **Wrong target field** — the "Joining session" call in
   `FUN_00559520` reads `+0x2d4/+0x2d8`, not `+0x2cc/+0x2d0`. Those
   fields are set ONLY by `FUN_0055aa30` case `'\x05'`.
2. **Wrong wire framing** — `FUN_00559920` case `0x19` may only be
   reachable via the `0x1f` "GamePackets" sub-type, not directly
   inside a `0x03` reliable wrapper. Our builder skipped that layer.

## Code we did ship

Commit `fb1eb0f` ("Close several retail-mismatch gaps in the
login/world-entry path") landed the following pre-session fixes. These
DID make incremental progress (we got from "crash after Synchronizing"
to "graceful return to login after Synchronizing") and are KEPT:

- `Location.java` TCP off-by-4 fix — added third `writeInt(0)` before
  the zone name so the client doesn't truncate the first 4 chars.
- `GameServerUDPConnection.rebindClient()` — updates address/port in
  place without resetting the counter or session key. Needed for
  zone-handoff UDP reconnect.
- `HandshakeUDP.HandshakeUDPAnswer2` — 500ms cooldown via
  `Player.lastWorldEntryAt` prevents duplicate `WorldEntryEvent`
  schedules from the event-queue race.
- `AuthB` no longer sends `UDPServerData` (retail only sends it once,
  from `GetUDPConnection`).
- `UDPServerData.flags` = `ProtocolConstants.UDP_SERVER_DATA_FLAGS`
  (`0x00890000`) instead of the previous `0x0000FFFF` — matches retail
  wire bytes.
- `AuthAck` (gameserver) expanded from 13-byte stub to the retail
  31-byte layout. `infoserver/AuthAck.java` is still the 13-byte stub;
  apparently that's correct for the InfoServer channel.
- `CharInfoV1.java` (new) — `0x22 0x01` CharInfo variant 1, sets the
  client's sync bit 0.

## Tooling preserved

### Ghidra headless scripts (`ceres-j/tools/ghidra/`)

- `FindState2acWrites.java` — scans every instruction for scalar
  operand `0x2ac`, groups by function, dumps decompile. Writes
  `ceres-j/docs/state_2ac_callsites.txt`.
- `FindStringRefs.java` — finds functions referencing a needle set of
  log strings, with orphan-reference fallback that walks backward to
  the nearest function start when `getFunctionContaining()` returns
  null (catches thunk-masked calls). Writes
  `ceres-j/docs/state_string_refs.txt`.
- `FindCallers.java` — maps callers of specific function addresses.
  Writes `ceres-j/docs/call_graph.txt`. Note: does NOT resolve thunks;
  extending it to follow thunks recursively is on the Option-C to-do.
- `FindScalarRefs.java` — generic scalar-constant reference finder.
  Used for UI event IDs (`0x3f2`, `0x3f3`). Writes
  `ceres-j/docs/ui_event_refs.txt`.
- `FindSucceed.java` — tailored scan for "succeed!" and other NetHost
  / world-change flow strings. Writes
  `ceres-j/docs/nethost_flow.txt`.

Run pattern:
```bash
cd /home/javier/Documents/Projects/Neocron
/opt/ghidra/support/analyzeHeadless \
    /home/javier/Documents/Projects/Neocron Neocron2clien \
    -process neocronclient.exe -noanalysis \
    -scriptPath ceres-j/tools/ghidra \
    -postScript <Script>.java
```

Note: the existing Ghidra project `Neocron2clien.rep/` has the binary
pre-analyzed. If analysis gets cleared, drop `-noanalysis` for a fresh
full analysis (~6 minutes on this host).

### Python analyzers (`ceres-j/tools/`)

- `analyze-retail-packets.py` — per-position entropy, global entropy,
  null-byte %, first-byte histogram, same-size consistency checks.
  Output has a heuristic encrypted-vs-plaintext verdict.
- `decode-retail-packets.py` — attempts `PacketObfuscator` XOR
  decryption and compares pre/post entropy. Used to falsify "retail
  uses PacketObfuscator cipher".
- `compare-packet-sizes.py` — packet size distribution diff between
  two or more strace captures. Most useful tool of the three; the
  "Ceres-J has 3 C→S packets, retail has 72" finding came from here.

### Documentation (`ceres-j/docs/`)

- `state_2ac_callsites.txt` — 13k lines, 44 functions that reference
  `+0x2ac`, with full decompilation.
- `state_string_refs.txt` — 6 functions matched by sync-state log
  strings.
- `call_graph.txt` — callers of the state-machine functions (via
  thunks).
- `ui_event_refs.txt` — `0x3f2`/`0x3f3` UI event callers.
- `nethost_flow.txt` — NetHost bootstrap and world-change flow.
- `PROTOCOL.md` — wire protocol reference (pre-existing, updated
  throughout session).
- `option_b_test_plan.md` — the B-1 test plan doc.

### Experimental packet classes (kept, not wired in)

All in `ceres-j/src/main/java/server/gameserver/packets/server_udp/`:

- `NetHostWorldName.java`
- `SyncResponse.java`
- `WorldInfoSrv.java`

## Strace captures (`ceres-j/strace/`)

Moved out of `/tmp` (tmpfs, volatile) into persistent storage on
2026-04-11. Totals ~1.4 GB. Gitignored via the existing `*.log`
pattern in the root `.gitignore`.

See `ceres-j/strace/README.md` for a per-file annotation. Quick
reference:

| File | Size | Purpose |
|---|---|---|
| `nc2_strace_RETAIL_ACC1_CHAR1.log` | 153 MB | Retail baseline, account 1 char 1 |
| `nc2_strace_RETAIL_ACC1_CHAR2.log` | 178 MB | Retail baseline, account 1 char 2 |
| `nc2_strace_RETAIL_ACC2_CHAR1.log` | 182 MB | Retail baseline, account 2 char 1 |
| `nc2_strace_RETAIL_ACC2_CHAR2.log` | 173 MB | Retail baseline, account 2 char 2 |
| `nc2_strace_CERESJ.log` | 157 MB | Ceres-J baseline (multi-burst, pre-cooldown-fix) |
| `nc2_strace_CERESJ_OPTB.log` | 246 MB | Ceres-J Option B-1 run (NetHostWorldName) |
| `nc2_strace_CERESJ_OPTB2.log` | 147 MB | Ceres-J Option B-2 run (SyncResponse) |
| `nc2_strace_CERESJ_OPTB3.log` | 176 MB | Ceres-J Option B-3 run (WorldInfoSrv) |

Capture workflow is documented in the README; new captures land at
`/tmp/nc2_strace.log` from `tools/debug-client-strace.sh` and should
be moved into `ceres-j/strace/` with a descriptive label after each
test run.

## Recommended next moves

In descending order of likely payoff vs. effort:

### 1. Extend the Ghidra call graph to resolve thunks

`FindCallers.java` currently stops at thunk targets. Extend it to
recursively follow thunks via `Function.isThunk()` and
`Function.getThunkedFunction()`, then rebuild the call graph for
`FUN_00559920`, `FUN_0055aa30`, `FUN_00558950`, `FUN_0055a5e0`,
`FUN_005592d0`, `FUN_00559520`. The real callers will tell us which
top-level dispatcher routes what wire bytes into each handler.
This is the single highest-value investigation — it answers "what
packet does case `0x05` of `FUN_0055aa30` actually respond to on
the wire?"

### 2. Find the `FUN_00559920` parent dispatcher

The switch cases {1, 3, 0x19, 0x21, 0x22, 0x24, 0x29, 0xb1} don't
match any sub-type table in `PROTOCOL.md`. Options:
- `FUN_00559920` is the `0x1f` "GamePackets" handler and these are
  sub-sub-types. Check by finding the function that calls it with a
  `0x1f`-pre-dispatch.
- `FUN_00559920` is called from the NetMgr layer directly and
  operates on NetMgr message bytes without the usual `0x13/0x03`
  wrapping.

### 3. Decrypt one retail packet manually

Even a single retail packet decrypted would break the whole
investigation open. Candidate approaches:
- Breakpoint the client in a debugger at `FUN_00559920` entry,
  dump `puVar1` bytes, correlate with the strace's encrypted bytes
  at the same timestamp.
- Trace `ObfuscateStreamBuf`'s `FUN_004dff90` / `FUN_004e0220`
  (vtable[3] / vtable[7]) in Ghidra and implement its cipher in Python.

### 4. Patchserver → 8020 port mismatch audit

Low priority, but the fact that `PatchServer` binds 8020 while the
client never connects there suggests either:
- `PatchServer.java` is dead code, should be removed.
- `PatchServer` was supposed to be on a different port and something
  is misconfigured. Historically NC1 patch was on a different port
  than NC2; maybe Ceres-J inherited the wrong default from Irata.

### 5. User-facing regression check on `fb1eb0f`

Everything in that commit is still live. If tomorrow we rip up the
login path further, run `mvn test` after each change — the tests are
the only safety net against regressing the progress we already have.

## Session audit trail (terse)

- **Started** from "still unable to log into world" with a plan file
  at `.claude/plans/refactored-inventing-sifakis.md`.
- Ran Ghidra headless `FindState2acWrites.java` — found 44 functions
  touching `+0x2ac`, mapped the state machine.
- Built `FindStringRefs.java` — found `FUN_00559920` by grepping for
  "WC : Changepos set" and "World Change denied".
- Built `FindCallers.java` — incorrectly concluded `FUN_0055bdc0`/
  `FUN_0055a5e0` were dead (thunk resolution missing).
- Ran entropy analyzer on 4 retail captures → retail S→C is encrypted.
- Wrote `decode-retail-packets.py` with `PacketObfuscator` cipher,
  entropy stayed at 7.99 → PacketObfuscator is NOT retail's cipher.
- Option B-1: `NetHostWorldName` raw `0x83 0x0c` UDP → no effect.
- Option B-2: `SyncResponse` inner-`0x03` wrapped in `0x13→0x03` →
  no effect (state not 3/6).
- Option B-3: `WorldInfoSrv` `0x19 0x04` wrapped in `0x13→0x03` →
  no effect (wrong target field + probably wrong wrapper).
- Read client `Init.Log` + `error_*.log` — decoded `1816438976` as
  `192.168.68.108` (not garbage), located `FUN_00559520` as the true
  "Joining session" function, identified `+0x2d4/+0x2d8` as the
  blocker fields.
- Discovered the patchserver red herring (`0x73` vs `0x66` — `0x73`
  is on port 8020, unused).
- **Reverted** all experiments from `WorldEntryEvent.java`.
- 111/111 tests green, container rebuilt, back to baseline.
