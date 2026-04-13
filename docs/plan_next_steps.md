# Plan: next steps for the login timeout

**Parent doc:** `progress_login_timeout.md` — read that first for full
context on what's been tried and ruled out.

**Goal:** get the modern NCE 2.5.766 client past the 15-second
"Connection to worldserver failed" bounce and into the live world,
controllable by the player.

## Current bottleneck (one-line)

`FUN_00559520` ("WorldClient: Joining session . . .") reads fields
`+0x2d4/+0x2d8/+0x278/+0x2f0` that are populated **only** by
`FUN_0055aa30` case `'\x05'` ("Client accepted" NetMgr msg). We never
trigger that case. Every other downstream step (sync req, sync
response, state 4) is blocked on this one prerequisite.

## Plan structure

Three parallel tracks ordered by payoff. Track A is the highest-value
single investigation. Tracks B and C are cheaper experiments that can
run concurrently if Track A stalls.

---

## Track A: Map the dispatch tree (Priority 1)

**Why this is the top priority:** every other experiment we ran last
night failed for the same reason — we don't know what wire bytes
actually route into `FUN_0055aa30` case `'\x05'`. Without that, every
candidate packet is a guess with ~1% a priori probability of success.
Answering this question definitively unblocks every downstream track.

### A.1 — Extend `FindCallers.java` to resolve thunks

**File to modify:** `ceres-j/tools/ghidra/FindCallers.java`

**Change:** after looking up the direct caller via
`listing.getFunctionContaining(from)`, if the caller is a thunk
(`caller.isThunk()`), walk up via `caller.getCallingFunctions(monitor)`
or the reference graph to find its callers, recursively, until we
reach real code. Cap the recursion at 10 levels to avoid loops.

**Targets to re-scan after the fix:**

| function | what we want to learn |
|---|---|
| `FUN_0055aa30` | who calls this? What routes data into the NetMgr msg pump? |
| `FUN_00559920` | who dispatches here? What's the wire framing? |
| `FUN_0055a5e0` | dead or live? If live, who triggers it? |
| `FUN_005592d0` | same — this is the function that actually logged "succeed!" |
| `FUN_00559520` | who calls "Joining session"? Is it the tick loop or an event? |
| `FUN_00558950` | who calls the BSP-load / command-ID handler? |
| `FUN_00557b90` | **the WorldClient constructor** — who creates it? That caller reads `param_2` which populates `+0x268/+0x26c/+0x270/+0x274/+0x278/+0x280`. Find the CALLER and we find what packet populates those fields. |

**Expected outcome:** a call graph from the top-level WinSock `recv`
wrappers down to the state-machine handlers, annotated with wire
format at each level.

### A.2 — Identify the NetMgr queue fill source

Once we have real callers, answer: **where does `FUN_0055aa30`'s
vtable[2] call actually read from?**

Specifically, the `+0x29c` field holds the NetMgr object. Its vtable[2]
is called with `(local_4a4, local_484, 0)` and returns a `char`
indicating whether a message was available. The internal queue is
filled by... something. Candidates:

1. **Dedicated NetMgr UDP socket** — `FUN_005f2f70(0, "xfghsdkjskfdlgj", 0, 2)`
   was called with port 0, not 9000. Port 0 means "ephemeral bind"
   which is valid. But the strace shows only UDP traffic to 5001 and
   no traffic to port 12000. Either the NetMgr socket is idle, or it
   shares a port with the game UDP.
2. **TCP route** — the InfoServer or GameServer TCP handlers may
   forward specific opcodes into the NetMgr queue. Our `0x83 0x05`
   TCP packet from `UDPServerData.java` may already be being routed,
   but with a format case-5 doesn't accept.
3. **Internal synthesized** — the NetMgr queue might be populated by
   the client's own code in response to some other stimulus (e.g.
   "synthesize a Client-Accepted msg when the TCP session hits a
   certain state").

The thunk-resolved call graph will tell us which of the three.

### A.3 — Validate or falsify the `0x1f` wrapper hypothesis

Cheap hypothesis test. Gemini suggested the `FUN_00559920` switch
cases are sub-sub-types inside a `0x1f` "GamePackets" sub-packet.
Evidence for: cases `0x19` ("Dialog") and `0x22` ("ExitChair") overlap
with `PROTOCOL.md`'s `0x1f` sub-type table. Evidence against: case
`1`, `3`, `0x21`, `0x24`, `0x29`, `0xb1` don't overlap.

**Test:** search the decompile for a function that calls
`FUN_00559920` and has a dispatch on byte `0x1f`. If found, confirmed.
If the caller dispatches on some other byte, we know the wrapper
isn't `0x1f`.

This test can run against the existing `Neocron2clien.rep/` project
with no new captures.

---

## Track B: Retail wire decryption (Priority 2)

Decrypting even ONE retail C→S packet would break the entire
investigation open. The stats analyzer proved the traffic is encrypted
and `PacketObfuscator` is not the cipher. The next logical target:

### B.1 — Trace `ObfuscateStreamBuf` in Ghidra

The class lives at vtable `0x00a1fd1c` in the binary. Decompile targets:

| vtable slot | function | purpose |
|---|---|---|
| `vtable[3]` | `FUN_004dff90` | encrypt outgoing bytes (overflow/write) |
| `vtable[6]` | `FUN_004e02a0` | decrypt peek (no advance) |
| `vtable[7]` | `FUN_004e0220` | decrypt and advance |

Output the full decompile of all three into
`ceres-j/docs/obfuscate_stream_buf.txt`. Also decompile the CALLERS of
these methods — that tells us where the cipher is applied.

### B.2 — Port the cipher to Python

Once we have the cipher from Ghidra, port it to
`ceres-j/tools/decrypt-stream.py` with the following interface:

```python
def decrypt(cipher: bytes, initial_state: int) -> bytes:
    ...
```

Test it against a Ceres-J-captured retail recvmsg byte stream. If the
output has readable opcodes (`0x83`, `0x13`, etc.) at expected
positions, the cipher is confirmed. Then we can run it against the
retail captures and see EXACTLY what retail sends in the
"post-handshake pre-burst" window.

### B.3 — Diff retail C→S vs Ceres-J C→S

Using the decoded retail packets, compare against Ceres-J's captures
(where we already know the wire bytes — it's plaintext). The diff
will show us the specific packet (or packets) retail's server sends
that ours doesn't, which is exactly the stimulus we've been guessing at.

**Branch condition:** if Track A produces a definitive answer before
Track B reaches B.3, we can skip Track B entirely.

---

## Track C: Probe matrix (Priority 3, cheap experiments)

Low-confidence but low-cost experiments that can run in parallel.
Each one is a test + revert cycle with the existing experimental
packet classes (`NetHostWorldName`, `SyncResponse`, `WorldInfoSrv`)
re-wired one at a time.

### C.1 — WorldInfoSrv inside `0x1f` wrapper

Modify `WorldInfoSrv.java` to inherit from a new
`PacketBuilderUDP13031f` that adds a `0x1f` byte after the `0x03`
reliable wrapper. Test with the existing test-rebuild-capture cycle.

Success signal: client sends C→S packets (any quantity > 3).

### C.2 — `UDPServerData` duplicated fields

Modify `UDPServerData.java` to populate bytes 10-17 with the same
`(serverIP, udpPort)` twice — once at bytes 10-13, once at bytes
14-17 — matching what `FUN_0055aa30` case 0x05 expects at `puVar7 + 10`
and `puVar7 + 0xe`. Since our UDPServerData already uses the 0x83 0x05
opcode, if it's already being routed to the NetMgr queue via TCP, the
field duplication may be all that's needed.

This is a 5-line edit. High value per effort.

### C.3 — Send `0x19 0x0c` "world change triggered" after `0x19 0x04`

If `0x19 0x04` writes to staging (`+0x288 = 1`, etc.) when
`+0x144 == 0`, then a subsequent `0x19 0x0c` copies staging into
`+0x2cc/+0x2d0`. Send both in sequence in WorldEntryEvent. Even if
the direct write path (when `+0x144 != 0`) doesn't work, the staging
path might.

### C.4 — Log all incoming TCP/UDP bytes server-side

Add a `logInbound()` hook to Ceres-J's TCP/UDP listeners that hex-dumps
every packet the client sends. Cross-reference against the client's
strace sendmsg. This gives us the OTHER side of the strace — what
Ceres-J actually received. Diagnostic-only, doesn't change behavior.

---

## Track D: Nothing-to-lose experiments (Priority 4, only if bored)

### D.1 — Confirm the BSP-load path

The Init.Log contains `WorldClient: World changes successfull!` which
strongly implies `FUN_00558950`'s "Load World" branch ran. But state
ends at 2 (the 15s timeout case), not 5 or 6. Possible explanations:

1. **State briefly visits 5/6, then gets reset back to 2** by some
   subsequent write. Unlikely — we have a full write-site map and
   nothing on the normal path writes `+0x2ac = 2` except
   `FUN_0055b6f0` case 1.
2. **BSP load happens via a different code path** that logs the same
   message but doesn't touch `+0x2ac`. Possible — the client has
   legacy code paths for initial world load vs. mid-session world
   change.
3. **The log message lies** — i.e. `World changes successfull!` is
   always logged optimistically before the actual change completes,
   and the "change" never really finished.

Diagnose by adding a server-side sentinel: send a deliberately
malformed packet right before ZoningEnd and see if its error message
appears in the client log. If it does, the client is processing our
packets past the BSP-load phase; if not, processing stopped earlier.

### D.2 — Audit `PatchServer` vs `InfoServer` confusion

`PatchServer.java` binds port 8020 and sends `80 01 0x73`
(password[4]='s'). `InfoServer.java` binds port 7000 and sends
`80 01 0x66` (password[1]='f'). The client connects only to 7000.
Either PatchServer is dead code or it's misconfigured. Not causing
the current bug but it's confusing. Low-priority cleanup.

---

## Decision tree / success criteria

```
Track A.1 (thunk resolution) ────┐
                                 ├──> If resolved: Track A.2 (trace case 0x05 routing)
                                 │         │
                                 │         ├─ UDP route  ──> Track C.2 (duplicated fields) + re-test
                                 │         ├─ TCP route  ──> Adjust UDPServerData format + re-test
                                 │         └─ Synthesized ─> Trigger the synthesis via alternate stimulus
                                 │
                                 └──> If unclear: run Track C.1/C.2/C.3 in parallel, observe

Track B.1 (ObfuscateStreamBuf decomp) ────┐
                                          ├──> B.2 (Python port) ──> B.3 (retail diff)
                                          │                              │
                                          │                              └──> Compare to Ceres-J captures
                                          │                                   Identify missing packet(s)
                                          └──> If the cipher turns out to be stateful
                                               across packets, we're back to needing
                                               a debugger to dump plaintext at runtime.
```

**Success criteria** (in order of ambition):

1. **Minimum viable:** the client's C→S packet count after handshake
   rises above 3 (currently only the three UDP handshakes). This means
   the state machine advanced past state 2 and is actively
   communicating.
2. **Partial:** the client logs `Synced :%i` — state machine reached
   state 4 (in-world).
3. **Full:** the character spawns into plaza_p1 and is controllable.

---

## First concrete action

Start with **Track A.1** because it's the highest-leverage single
change and requires no test-rebuild cycle (pure Ghidra script edit +
headless re-run). Expected turnaround: ~15 minutes of edit time + ~3
minutes of Ghidra execution.

After Track A.1 completes, the contents of a regenerated
`docs/call_graph.txt` will dictate the next move:

- If the graph shows a clean TCP → NetMgr route: edit `UDPServerData.java`
  (Track C.2 payload fix) and rebuild.
- If the graph shows a UDP dispatcher with first-byte filtering: write
  a new packet class wrapped per the dispatcher's format.
- If the graph is still opaque after recursive thunk resolution: move
  to Track B (cipher) as the parallel path.

---

## What this plan does NOT cover (intentionally)

- **Retail patchset differences across client builds.** We're assuming
  the 2.5.766 client has one protocol. If retail has changed it
  multiple times and the captures span different versions, that's a
  separate investigation.
- **Anti-tamper / signature checks.** The client may have
  integrity-check code that rejects our plaintext packets on opcode
  principles we don't see. Not addressed here; if Track A reveals a
  first-byte filter that's more restrictive than we thought, we'll
  know.
- **Rewriting Ceres-J to match the retail wire cipher.** Even if we
  solve the cipher in Track B, we probably don't need to ENCRYPT our
  outgoing bytes — the client accepts plaintext as long as the first
  byte is in `{0x01, 0x03, 0x04, 0x08, 0x13}`. We only need decryption
  to READ retail's bytes for diagnosis.
