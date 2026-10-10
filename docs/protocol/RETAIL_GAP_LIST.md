# Retail vs Ceres-J — measured gap list

_Measured 2026-10-04. Branch with the fixes: `track/transport-liveness`
(not merged, not pushed)._

This is the working list of everything the emulator still does differently
from the retail server, ranked by impact, with the retail evidence for each.
Every claim traces to a retail capture (harness `trace.jsonl` = the client's
own decrypted wire, or a raw pcap decoded with `tools/pcap-decode.py`) — see
`docs/METODOLOGIA_INGENIERIA_REVERSA.md`.

## How it was measured

- `tools/frida_re/scripts/corpus_gap.py --runs tools/frida_re/runs` —
  label histograms (opcode / sub-tag / size) over **44 retail** and **126
  Ceres** harness traces, split by the server each run logged into.
- `tools/frida_re/scripts/bot_capture.py OUT` — the CURRENT build's output to
  a client that behaves like the real one at world entry (nc2-bot
  `drive_world_entry`), in the same trace format; feed it back with
  `corpus_gap.py --ceres-dir OUT/.. --current-only`.
- `tools/pcap-decode.py` over the 25 retail pcaps in `strace/` for
  transport-layer rules (they start at t=0, the harness traces don't).

The client was reinstalled on 2026-10-04 (build 862). Fixes marked
"verified" were checked in the real client with the Frida harness; the run
id is given (`tools/frida_re/runs/<id>/`).

## Fixed on `track/transport-liveness`

| Commit | Fix | Retail evidence | Verify in the real client |
|---|---|---|---|
| `f457cae` | UDPAlive `0x04` only retried while the client hasn't answered with transport `0x03`; CPing reply back inside `0x13` | 25 pcaps / 251 `0x04`: same-key `0x04` only as a retry of an unanswered one (HANNIBAL); 884 retail ping replies all inside `0x13` | **verified**: 0 NAKs / retransmits per minute (was a storm); ping replies framed like retail |
| `d096722` | NPC LE16 positions = `floor(world)+0x8000` (Z−1); `.dat` NPC id = `0xFF+npc_id`; orientation string = `.dat` angle text | exact match of 13/13 static pepper_p1 NPCs, industry_p1 npc 4/16, plaza_p1 npc 11 | **verified** in pepper_p1: wire coordinates byte-identical to retail |
| `b4fe12b` | plaza_p1 spawns its 82 world-`.dat` NPCs, not the 8 TinNS-era `npc_spawns` rows | retail plaza_p1 entities 266/299/325 = `.dat` npc 11/44/70 | not yet checked in the client |
| `36ca0a4` | mob movement `0x20` layout (unwired) | fire_bc_v1 13-byte form | — (needs the mob spawner) |
| `6c94cec` | AuthAck (gameserver + infoserver) echoes the client's password ciphertext as session_data | retail pcap Auth/AuthAck pairs: session_data == Auth ciphertext | login works; did **not** fix the transport `0x03` id (gap 6) |
| `4bcdf93` | CharList records are 0x49 = 73 bytes (old 41 + 32 new) | retail_b862_1 CharList header `8385 0600 0400 4900` | **verified** (verify_cl73_2): char-select lists the 4 characters with their details; the pass enters the world. With 41-byte records, build 862 showed 4 empty slots and every create overwrote slot 1. Creating into a free slot is not re-tested (msn3wolf's 4 slots are full) |
| `53af634` | no self `0x03/0x1b` position and no `0x03/0x19/0x04` WorldInfoSrv in the world-entry burst | retail never sends `0x19`; its login `0x03/0x1b` are other entities only (pepper_p1 ids 130–148/173/174) | **verified** (verify_nocorrupt_2): "@WWORLDMGR : Corrupted Message Type:15, Size:21" and "LSTPLAYER : Update Message corrupted Size:12 32" gone from the client error log |
| `5c3d91a` | CharInfo S1/S13 byte 1 = character kind (charkinds.def id = `MISC_PROFESSION`) | retail Krafteo (Spy Engineer) sends 36 in 5/5 runs, a Tank Driver 20; client parser stores it at charsys+0x54 | **verified** (verify_kind_1): client CHARSYS +0x54 = 10 (Krafteo's profession); "Character Type not found 0" gone |
| `d11ad98` | login world entry waits for the client's raw `0x2a` init request (3 s fallback) | retail_b862_1: `0x2a` at +59 ms, first reliable at +260 ms; Ceres runs never saw `0x2a` and the client kept map id 0 | **verified** (verify_pull_1): `0x2a` at +57 ms, client uses map id 1 in `0x20`/`0x1f` |

## Open gaps

### P1 — world simulation

1. **NPC refresh model is push, retail is pull.** Retail (fire_retail_v1,
   pepper_p1): every NPC gets a raw `0x1b` ~1×/s (unreliable); the client
   answers an unknown entity with C→S `0x03/0x27` RequestWorldInfo and the
   server replies `0x03/0x28` (2 requests / 2 replies per NPC, ~0.2 s after
   first sight); static NPCs get **no** `0x2d`. Ceres
   (`ZoneStateHeartbeat`): one NPC per 500 ms round-robin with raw `0x1b` +
   **reliable** `0x2d` + `0x28` → 4 reliables/s forever, and each NPC's `0x1b`
   only every N/2 s (41 s with plaza_p1's 82 NPCs). The client has never sent
   `0x27` to Ceres (0 of 126 traces). Change only with the client available:
   if the request path doesn't fire, NPCs disappear.
2. **No mob spawner.** Retail mobs are not in the zone `.dat`: industry_p1
   `BIGMUT`/`MGMUT`/`SSCORPA` arrive as eids 973–1018 with random facings.
   `defs/npcgroupspawn.def` gives group composition (group 1 = SSCORPA
   410–416/440–442, 20 = mutants incl. MGMUT 611) but no spawn points — find
   where those come from (spawner entities in the `.dat` such as
   `SPIDERNEST2`? server-only data?). spider_cave_1's `.dat` holds only nests
   and anti-droners, so Ceres currently spawns no spiders at all.
3. **Mob AI messages.** The 58-byte `0x03/0x2d` is per-mob AI state:
   `[2d][eid LE16][00 00][type][payload]`, types `0x6f`/`0x71`/`0x75` (floats
   inside, e.g. −1456.0/−798.0 = a mob position); tails contain uninitialised
   64-bit server pointers (`…f67f0000`), so not every byte is meaningful.
   City NPCs only ever get the 9-byte type `0x06`. Ceres sends only the
   9-byte form. Mob movement = raw `0x20` (layout pinned, see `36ca0a4`).
4. **`0x28` state block = SCRIPTEDPLAYER HP/attrs/flags, Ceres sends
   zeros.** The `0x28` body from [7] is the client's SCRIPTEDPLAYER stream
   (`FUN_00699fd0`): body[20..23] LE32 "HP-ish" (grows per entity over
   time), body[24..33] 10 skill/attr bytes (class/level scaled — npc.def
   lvl 10 → `00 06 05 03 04 04 02 00 00 00`, all zero for killed mobs),
   body[34..35] flags. Ceres-J writes zeros, i.e. what retail sends for a
   dead mob. Exact formula unknown (see `udp_s2c_03_28.md`) — test whether
   the client treats zero attrs as dead once it is back. Raw `0x1b`
   [13..14] tracks the same LE32; [12..18] are zero in Ceres.

### P2 — login / session

5. **Init burst — mostly fixed by `d11ad98`.** Retail sends no `0x13`
   until the client has answered `0x04` and sent raw `0x2a` init request +
   `0x0c`. Ceres now waits for that `0x2a` too (3 s fallback). Still
   different: retail allocates reliable seqs before the burst (its first
   delivered reliable is seq 12–25, the rest come through NAK → `0x02`),
   and Ceres answers the client's three `0x01` with up to six same-key
   `0x04` (retail: one, retried only if unanswered).
6. **Random id in the client's transport `0x03` — open, low impact.**
   `[03][id LE4][iface][port LE2]` carries the account id to retail
   (`bd7e0100`) but a random value to Ceres. Ruled out in the real client
   (2026-10-10): the `0x83/0x05` field after the port (`00008300` → zeros,
   exp_flags0b), the account id's size (account id 98046, exp_acct98046),
   and TCP batching (UDPServerData sent 200 ms after SessionReady,
   exp_delay8305). The value sits in client memory just before a
   high-entropy block (exp_echo1), so it looks client-generated. It stays
   random even in runs where the client's `0x2a` carries account id 2.
   Ceres doesn't use it.
7. **Client self id 0 vs 1 — fixed by `d11ad98`.** The client assigns
   itself map id 1 only after sending its `0x2a` init request, which it
   never did while Ceres pushed the world unasked (verify_pull_1:
   `20 0100` and `1f 0100` like retail).
8. **S→C `0x08` "window primer" after `Zoning2`.** Retail never sends S→C
   `0x08`. (The invented `0x19/0x04` WorldInfoSrv that used to sit here is
   gone, see `53af634`.)

### P2b — world-entry burst content

9. **Raw `0x1b` interest management.** Retail sends the ~1/s raw `0x1b` only
   for NPCs near the player: 12 entities in pepper_p1 vs 24 from Ceres
   (everything in the zone).
10. **Static world objects.** Retail's login burst carries `0x03/0x1b` for
    ids 130–148 and 173/174 in pepper_p1, static world objects that Ceres
    doesn't send at all. Identify them in the zone `.dat` (doors, terminals,
    …) before emitting anything.
11. **`0x03/0x25/0x01` LongPlayerInfo layout.** Retail self (65 B,
    retail_b862_1): `25 [map LE2] [charId LE4] 00` + a fixed 22-byte
    appearance-slot array that matches the `0x2f` slot sizes
    (`1b00 26 0000 00 00 d6 21 0b 04 00 2201 3c00 ffff 0504 6004`) + 8 zeros +
    the model block `0f [head LE2] [tex×3] 00×6 [torso LE2] [leg LE2]` +
    `[len] name\0` + `00 00`. Ceres writes TinNS-era constants
    (`08 09 6c 02 40 40 c4 3c 00 [faction] 00 ffffffff ffff [hair] [beard]
    ffffffff`) in place of the slot array and appends `04 [cash LE4]`.
    Needs the per-character appearance slots (same data gap as `0x2f`).

### P3 — remaining retail-only traffic (context-specific)

| Label (S→C unless noted) | Retail | Notes |
|---|---|---|
| raw `0x0b`, 13-byte form | 33 | every 20th ping reply (~60 s; restarts on re-key): `…[2774 LE2][B LE2]`, B grows slowly across days (5 → 143). Meaning unknown — don't invent. |
| raw `0x3c` + C→S raw `0x3c` | 39 / 41 | anti-cheat skill-verify handshake (`udp_3c_state_poll` memory) |
| `0x03/0x26` | 33 | 8 B `26 02000000` |
| raw `0x1f/0x50` | 20 | 16 B |
| raw `0x1f/0x25` | 66 | 25 B, unreliable form of the `0x25` family |
| `0x03/0x1f/0x26`, `0x03/0x1f/0x3e` | 7 / 4 | |
| `0x03/0x09`, `0x03/0x25/0x02` | 2 / 2 | |
| raw `0x32`, `0x03/0x32` | 2 / 1 | subway |
| raw `0x01` (server-side NAK) | 4 | |
| `0x03/0x2f` login form | 73–79 B | framing matches (value size is per slot, see `udp_s2c_03_2f.md`); Ceres hardcodes appearance slots 1/5/8 (`07`/`8a`/`01`) and omits 0/3/4/6/7 — needs the per-character appearance data |
| `0x03/0x25/0x01` | 65/68/73 B | different layout, see gap 11 |
| `0x03/0x07` fragments | 1015 B | multipart fragment size |
| C→S `0x03/0x1f/0x1e`, `/0x17`, `/0x27`, `/0x32`, raw `0x32`, raw `0x1f/0x15`, raw `0x07` | | item use, object use, dialog close, subway… — client features not exercised against Ceres in the corpus |

Not gaps: raw `0x1f/0x55` ↔ `0x56` is a mob poll (`1f cf03 55` → `…56
00000000`, fire_bc_v1) already implemented as `InteractionPoll`/`Ack`; it will
appear once mobs exist.

## Live verification

```bash
cd tools/frida_re/scripts
PROTON_BIN=~/.local/share/Steam/compatibilitytools.d/GE-Proton10-34/proton \
  /home/javier/Documents/Projects/Neocron/bin/python3 run_pass.py ceres <id>
```

- `login_pass.py ceres <id> [--scan-uid UID]` is the short check: login,
  wait in-world, one screenshot, optional CHARSYS scan.
- Use the project-root venv (it has `frida` 17.9.11). Pin GE-Proton10-34:
  `launch_nc2.sh` otherwise picks the newest by mtime.
- The pass installs the gadget into the game dir and removes it at teardown
  (`0ce0dcf`), so normal launches aren't stalled by the ASI loader. Use
  `FRIDA_RE_KEEP_GADGET=1` to keep it.
- **Test character mismatch:** Ceres Krafteo is a PE Assassin (profession
  10, models 700/800/900), retail Krafteo a Spy Engineer (36, 720/820/920).
  CharInfo / `0x25` / `0x2f` A/B diffs differ for that reason alone.
- About half the passes crash inside DXVK d3d9 when a screenshot follows a
  device reset (char select → world). Skip screenshots before world entry
  and retry a crashed pass.
- The game dir's ReShade `d3d9.dll` and add-ons are 32-bit, so they don't
  load in the 64-bit client (no effect on the harness).
- After a client update, re-capture `run_pass.py retail` and diff the TCP
  login first. Inbound TCP lands in `trace.jsonl` as `udp_recv` with
  `decrypt=FAIL` and the raw `wire_hex`.
