# NC2 zoning + interaction protocol (verified 2026-05-02)

Captured from a 14-minute retail session (`ZONING_AND_ITEMS_LONG`)
covering login, walk-zone transitions across 3 sectors, GoGo, chair,
medibed, CityCom terminal, GenRep teleport, NPC dialog, vendor, door,
elevator. 24 markers + 44,221 packets analysed.

## Executive summary

* **Walk-zone transitions and GenRep teleports use the same TCP path:**
  `0x83 0x0d 0x00 0x00` (GameinfoReady) immediately followed by `0x83 0x0c`
  (Location) carrying the new zone's BSP path. **No UDP ForcedZoning,
  no multipart CharInfo redelivery, no splash screen.**
* **The client keeps its state across transitions** — coordinates,
  CharInfo, inventory all retained. Only the BSP swap happens locally.
* **Entities are spawned lazily** via `0x03/0x28` as the player moves
  and broadcasts position in the new zone.
* **`0x03/0x2b` is the CityCom DCB RPC channel** — the previous
  hypothesis "movement-authority" was wrong. Found by decoding the
  packet bodies during marker `TERMINAL_CITYCOM`.

## Verified packet flow per scenario

### Walk between sectors (e.g. Pepper Park 3 → Pepper Park 2)

```
C->S        0x20            position broadcast (every ~50ms during walk)
            ...
[server detects zone-edge crossing from accumulated position broadcasts]
            ...
S->C  TCP   0x83 0x0d  4B   GameinfoReady — `83 0d 00 00`
S->C  TCP   0x83 0x0c  31B  Location — see format below
[~3s of silence while client loads BSP locally]
S->C        0x03/0x28        nearby NPCs/players spawn, drip-fed as client
                             broadcasts position in the new sector
```

### GenRep teleport (e.g. → apartment)

```
C->S  UDP   0x03/0x1f        client invokes GenRep destination
            ...
S->C  TCP   0x83 0x0d        same as walk — GameinfoReady
S->C  TCP   0x83 0x0c        Location with apartment BSP path
[client loads BSP]
```

The wire layer is identical to the walk-zone path; only the destination
zone_id and bsp_path differ.

## `0x83 0x0c` Location packet format (31 bytes)

```
0x00  0x83 0x0c             opcode (2B)
0x02  zone_id LE32          for outdoor zones: small sequential int (5/6/7
                            for pepper_p1/p2/p3); for apartments: a unique
                            per-player apartment instance ID
0x06  00 00 00 00           reserved padding (4B, always zero)
0x0a  00 00 00 00           reserved padding (4B, always zero)
0x0e  <bsp_path>            null-terminated ASCII path under BSPs/, e.g.
                            "pepper/pepper_p2", "apps/plaza_app_1"
```

Examples observed:
| zone_id (LE) | bsp_path | Trigger |
|---|---|---|
| `07 00 00 00` | `pepper/pepper_p3` | login spawn |
| `06 00 00 00` | `pepper/pepper_p2` | walked into PP2 |
| `05 00 00 00` | `pepper/pepper_p1` | walked into PP1 |
| `55 74 4c 05` | `apps/plaza_app_1` | GenRep → apartment |

## `0x03/0x2b` CityCom DCB RPC channel

The 21-second burst at t=525..546 (during marker `TERMINAL_CITYCOM`)
turned out to be **not movement-authority** but the CityCom Database
Content Browser RPC channel. Sample request strings observed:

* `DCBSetup`, `DCBGetLastWeeklyIssue`, `DCBClanAdminAccess`,
  `DCBSetGuideContent`
* `Welcome to Neocron`, `Travelling in Neocron`, `Take on Missions...`,
  `Found a Clan and Accept Complex Missions`
* `Clanstatistics`, `Runnerstatistics`, `Outpoststatistics`
* `NCN Guide Navigator`, `g_0103.txt`

### Request format (C→S)

```
opcode  1B    0x17 (data) | 0x18 (?) | 0x19 (?) | 0x1f (query)
seq     2B    request id LE16, increments per session
hash   12B    per-session signature (varies per connection)
magic   9B    `0e 16 ff 55 7e 75 09 2f 08` — FIXED across all observed
              C->S 0x03/0x2b packets
[args]  ...   length-prefixed argument list:
              - 0x02 + size LE16 + ASCII string (numbers as strings)
              - 0x03 + size LE16 + ASCII string (longer strings)
              - 0x06 + size LE16 + ASCII string (id strings)
              - 0x08 + size LE16 + ASCII string (method names)
```

### Response format (S→C)

```
opcode  1B    0x17 | 0x1a
seq     2B    echoes request seq
data    ...   args (same length-string encoding as requests) plus 16
              bytes of trailing zeros for the larger payloads
```

This is generic enough to be used by any server-side database that the
client wants to browse — terminals, weekly news, clan admin, etc.

## Other newly catalogued packet types (not deep-decoded)

| Type | Count | Marker context | Hypothesis |
|---|---|---|---|
| `S->C 0x03/0x32` | 17 | only fires t=873-945 (NPC_TALK*, NPC_VENDOR_*) | NPC dialog response (8B fixed body) |
| `S->C 0x03/0x2f` | 16 | GENREP_OPEN_PICK | model/inventory update |
| `S->C 0x03/0x26` | 1 | t=224 (zone walk) | one-shot zone-state |
| `S->C 0x03/0x09` | 4 | door/elevator | scripted-object ack |
| `C->S 0x03/0x22` | 8 | various | client info-request |
| `S->C 0x03/0x23` | 23 | post-Auth + post-zone | server info-response |
| `S->C 0xa002` | 15 | between active periods | session-state ping (= server `a0 02`) |
| `C->S 0xa003` | 3 | login-only | session-state ping (= client `a0 03`) |
| `S->C 0xXX` 11B singles | 8 distinct | login + zone change + GenRep | UDP cipher session (re-)init responses, one per new UDP session |

## Implementation guidance for Ceres-J

To replicate retail's splash-free zone walking:

1. **Zone-boundary detector**: track each player's last `C->S 0x20`
   position broadcast and check whether it has crossed into another
   zone's bounding region. Zone bounds need a server-side table
   (from world_defs or hardcoded boundaries).
2. **Send TCP transition**: when the detector trips, emit
   `Packet830D` (GameinfoReady) and `Location` over the player's
   *existing* TCP connection — these classes already exist in
   `server.gameserver.packets.server_tcp`.
3. **Skip ForcedZoning** on walk-transitions. Don't redeliver CharInfo.
4. **Update server-side zone tracking** (`pc.setZone(newId)`) so
   subsequent NPC/player spawns are scoped correctly.
5. **Keep UDP ForcedZoning** as the admin-`/warp` and `/teleport`
   path; verify on retail whether even those use the TCP route.

## Capture provenance

* pcap: `nc2_strace_RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613.pcap`
* markers: same stem `.markers`
* Account: `msn4wolf` on retail server `157.90.195.74`
* Zone progression: pepper_p3 → pepper_p2 → pepper_p1 → plaza_app_1 (GR)
* Tools: `tools/packet_inventory.py` (NEW) for marker-correlated catalog
