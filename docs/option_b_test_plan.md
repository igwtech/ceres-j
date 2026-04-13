# Option B test plan — NetHostWorldName experiment

## What changed

**New file**: `src/main/java/server/gameserver/packets/server_udp/NetHostWorldName.java`

A plaintext raw-UDP packet class that emits the retail NetHost "world
name" message layout:

```
offset  size  value
0x00    1     0x83           NetHost msg marker
0x01    1     0x0c           sub-opcode "world name"
0x02    4     int32 = 0      posX → WorldClient +0x150
0x06    4     int32 = 0      posY → WorldClient +0x154
0x0a    4     int32 = 0      posZ → WorldClient +0x158
0x0e    N+1   cstring        worldname + null terminator
```

The world name comes from `Zone.getWorldname()` — the same string the
existing TCP `Location` packet already sends.

**Modified**: `src/main/java/server/gameserver/internalEvents/WorldEntryEvent.java`

Inserts a single `safeSend(pl, NetHostWorldName::new, ...)` call right
after the pre-stream `UDPAlive` and before `UpdateModel`. This delivers
the message at the start of the world-entry burst, before any packets
the client's state machine waits on.

## Why this might work

Ghidra scan (`docs/state_2ac_callsites.txt`, `docs/state_string_refs.txt`,
`docs/call_graph.txt`) proved:

- The active state machine is `FUN_0055b6f0`, called from master tick
  `FUN_0055ceb0` when `+0x2a8 ∈ {2,3,4}`.
- `FUN_0055b6f0` case 2 is the 15-second timeout we're hitting
  ("WorldServer: Connecting to WorldServer failed!").
- The **only** writer that advances `+0x2ac` past state 2 on a normal
  login path is `FUN_0055aa30` case `'\f'` (sub-opcode 0x0c, "world name
  received"), which sets `+0x2ac = 5` and `+0x2c4 = 0x0101`.
- Once `+0x2c4` is set, the next `FUN_0055b6f0` tick runs its top guard
  `if (+0x2c5 && +0x2c4)` which calls `FUN_00558950` — the world-change
  handler that eventually loads the BSP and drives state toward 4
  (in-world).

## Why this might NOT work

Two uncertainties:

1. **Dispatch routing**: retail uses a single UDP port (verified — only
   port 5002 in the retail strace captures). But it's unknown whether
   the client's top-level UDP dispatcher routes first-byte `0x83`
   packets to the NetMgr receive queue, or only handles the documented
   headers `{0x01, 0x03, 0x04, 0x08, 0x13}` and silently drops `0x83`.

2. **Dependent flags**: even if case `'\f'` fires, the state flow from
   5 → 6 → 4 requires `FUN_00558950` to be called multiple times as
   flags `+0x145`, `+0x146`, `+0x164` progress. It's unclear whether a
   single world-name message is enough to unblock the whole chain, or
   whether retail also sends a follow-up `0x19 0x07` "command ID" game
   UDP message that we don't yet emit.

## How to test

```bash
cd /home/javier/Documents/Projects/Neocron/ceres-j

# 1. Rebuild the ceres container with the new packet
docker compose build ceres && docker compose up -d ceres

# 2. Restart the tcpdump sidecar (if used)
docker compose restart tcpdump || true

# 3. Point neocron.ini at 127.0.0.1:7000 (you already have this)

# 4. Run the strace capture (retail script, but pointed at local)
./tools/debug-client-strace.sh
#    → log in, wait for the timeout / success, exit
#    → output lands at /tmp/nc2_strace.log

# 5. Rename so we don't clobber the existing captures
mv /tmp/nc2_strace.log /tmp/nc2_strace_CERESJ_OPTB.log
```

## What to observe

### Success signals (in order of ambition)

1. **Client error log changes**. Instead of
   `WORLDCLIENT : Connection to worldserver failed` after exactly 15s,
   a different error appears, OR a `Synced :%i` log line is printed.
2. **Fresh C→S packets**. The new ceres capture should show more than
   just 3 handshakes in the `sendmsg` direction. Run
   `python3 tools/compare-packet-sizes.py /tmp/nc2_strace_CERESJ_OPTB.log
   /tmp/nc2_strace_CERESJ.log` to compare. If the new run has diverse
   C→S sizes (40B, 16B, 28B, 84B etc., matching retail) the client's
   state machine is alive.
3. **Full success**: character spawns into plaza_p1 and is controllable.

### Partial / negative signals

- Same 15-second timeout, no new C→S packets → the `0x83 0x0c` raw UDP
  packet was dropped by the client. Pivot to wrapping it inside a
  `0x13` gamedata envelope (see "Next move on failure").
- Timeout changes from 15s to ~60s → state advanced to 5 but case 5
  just idles with retries. That's progress — it means `0x83` WAS
  routed, but the 5 → 6 transition needs another packet. Next move:
  also emit an `0x19 0x07` "command ID" gamedata sub-packet.

## Next move on failure (Option B-2)

If the raw `0x83` is dropped, try wrapping it inside a `0x13` gamedata
sub-packet. Change `NetHostWorldName` to extend
`PacketBuilderUDP1303` (the `0x13 → 0x03` wrapper), then write
`0x83 0x0c ...` as the inner payload. This puts the message inside the
normal gamedata flow, and the client's reliable-delivery dispatcher may
route sub-type `0x83` to the NetMgr queue.

## Next move on complete failure (Option C)

If neither raw nor wrapped works, we're out of experimental fixes and
need to fully map the dispatch tree. Option C:

1. Extend `tools/ghidra/FindCallers.java` to resolve thunks recursively
   — find the real callers of `FUN_00559920`, `FUN_0055aa30`, and the
   top-level UDP recv handler.
2. Trace from a known entry point (e.g. the winsock `recvfrom` wrapper)
   down to the case-0x0c handler to see exactly what wire layout the
   client expects.
3. Implement whatever the tree shows.

## Rollback

If the experiment fails and we want a clean rollback before commit:

```bash
git -C /home/javier/Documents/Projects/Neocron/ceres-j checkout -- \
    src/main/java/server/gameserver/internalEvents/WorldEntryEvent.java
rm src/main/java/server/gameserver/packets/server_udp/NetHostWorldName.java
docker run --rm -v "$PWD":/build -v "$HOME/.m2":/root/.m2 \
    -w /build maven:3.9-eclipse-temurin-21 mvn -B test
```
