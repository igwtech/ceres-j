# Send-order fix progress (2026-04-12)

## Fix applied

**File:** `GetGamedata.java` → `GetGamedataAnswer.execute()`

**Change:** Send `UDPServerData` (0x83 0x05) BEFORE `Location` (0x83 0x0c)
in the GetGamedata response. Previously UDPServerData was only sent from
GetUDPConnection, which fires AFTER GetGamedata — guaranteeing the wrong
order.

## Why this works

The client's TCP handler routes 0x83 0x05 into the NetMgr queue where
`FUN_0055aa30` case `'\x05'` processes it. This "Client accepted" handler
populates `+0x2d4/+0x2d8` (worldserver IP/port for the session-create call
in `FUN_00559520`). If `Location` (0x83 0x0c) arrives first, it triggers a
world-change that starts the state machine before those fields are set.

## Results

| Metric | Before | After |
|---|---|---|
| C→S packets | 3 (handshakes only) | **6** (2 × 3 handshakes) |
| Server handshake cycles | 1 | **2** (including zone-handoff rebind) |
| Init.Log "Joining session" | 1 (fails) | **2** (first succeeds, second fails) |

### Init.Log after fix

```
WorldClient: Connecting to NetHost . . . succeed!
WorldClient: Joining session . . .           ← NEW: case 0x05 fired, state 1→2→...
WorldClient: Delete world for world change   ← Location triggers world-change
WorldClient: World changes successfull!
WorldClient: Joining session . . .           ← Second attempt (after world change)
WorldServer: Connecting to WorldServer failed ← 15s timeout on second session
```

### Server log after fix

Two complete UDP handshake cycles:
1. Port 50167 → full WorldEntryEvent burst
2. Port 60840 → zone handoff → full WorldEntryEvent burst

## Remaining blocker

The SECOND "Joining session" creates a NEW UDP socket. The server
receives the second handshake and responds. But the client's error log
shows `Receive 0 Buffer × 14` on the new socket — it never receives the
server's response.

Possible causes:
1. The second session socket listens on a different port than where the
   server sends its response.
2. The second session expects a different response format (maybe just
   UDPAlive, not a full WorldEntryEvent).
3. The state machine resets +0x2d4/+0x2d8 during the world-change flow,
   and the second session-create uses wrong target again.
4. The WorldEntryEvent burst sent on the SECOND handshake interferes
   with the state machine (duplicate data for a session that's already
   past BSP load).

## Next investigation

1. Check if the second handshake's 3 packets (from port 60840) reach
   the correct server port and get responses back to the right client
   port.
2. Consider suppressing the second WorldEntryEvent — the client may only
   need a UDPAlive response to the second handshake, not the full burst.
3. Consider NOT rebinding the client on the second handshake — keep the
   original socket mapping and just respond with UDPAlive.
