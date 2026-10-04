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

**Caveat:** the game install is currently missing (`~/Neocron2` is empty), so
none of the fixes below has been checked in the real client yet. They are
verified against retail bytes, unit tests and a live nc2-bot session.

## Fixed on `track/transport-liveness`

| Commit | Fix | Retail evidence | Verify in the real client |
|---|---|---|---|
| `f457cae` | UDPAlive `0x04` only retried while the client hasn't answered with transport `0x03`; CPing reply back inside `0x13` | 25 pcaps / 251 `0x04`: same-key `0x04` only as a retry of an unanswered one (HANNIBAL); 884 retail ping replies all inside `0x13` | `run_pass.sh ceres`: client's own reliable seq must not restart at 1; C→S raw `0x01` ≈ retail (0–14/min, was 160–600/min) |
| `d096722` | NPC LE16 positions = `floor(world)+0x8000` (Z−1); `.dat` NPC id = `0xFF+npc_id`; orientation string = `.dat` angle text | exact match of 13/13 static pepper_p1 NPCs, industry_p1 npc 4/16, plaza_p1 npc 11 | `.dat` NPCs visible at their posts in pepper_p1 / spider_cave_1 (were ~31,000 units away) |
| `b4fe12b` | plaza_p1 spawns its 82 world-`.dat` NPCs, not the 8 TinNS-era `npc_spawns` rows | retail plaza_p1 entities 266/299/325 = `.dat` npc 11/44/70 | plaza_p1 NPC set matches retail |
| `36ca0a4` | mob movement `0x20` layout (unwired) | fire_bc_v1 13-byte form | — (needs the mob spawner) |
| `6c94cec` | AuthAck (gameserver + infoserver) echoes the client's password ciphertext as session_data | retail pcap Auth/AuthAck pairs: session_data == Auth ciphertext | stage-1 GetCharList 30 B (was 12 B); client's transport `0x03` carries the account id |

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
4. **`0x28` state block / raw `0x1b` tail.** Ceres writes zeros in the
   17-byte `0x28` block before the strings and in raw `0x1b` [12..18];
   retail has values (e.g. `00 2d01 000000 0c0b 0000 0905 …`; `0x1b` [13..14]
   tracks `0x28` block bytes 1..2). Semantics unknown.

### P2 — login / session

5. **Init burst is pulled in retail.** HANNIBAL (from t=0): server sends no
   `0x13` until the client answers `0x04` with transport `0x03` and sends raw
   `0x0b` + raw `0x2a` RequestInitBurst + raw `0x0c`; the first reliable it
   transmits is CharInfo as seq 25 — seqs 1–24 were allocated earlier and are
   only delivered through the client's NAK → `0x02`. Ceres streams the world
   straight after the handshake; the client then never sends `0x2a`.
6. **Wrong id echoed by the client — likely fixed by `6c94cec`.** The
   client's transport `0x03` (`[03][id LE4][iface][port LE2]`) carried the
   account id to retail (`bd7e0100`) but a random value to Ceres
   (`d8572cda`, `c837ea3a`), and its first GetCharList (`0x84 0x82`) was 12
   bytes to Ceres vs 30 to retail. Ceres's AuthAcks were not echoing the
   password ciphertext (gameserver sent zeros, infoserver sent length 0) —
   now they do. Confirm in the client.
7. **Invented `0x03/0x19/0x04` WorldInfoSrv.** Never in 17 retail captures
   (727K packets) nor the 44 harness traces; added for the #172 zone cross.
   Remove once crosses can be checked in the client. Likewise the S→C `0x08`
   "window primer" after `Zoning2` (retail never sends S→C `0x08`).

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
| `0x03/0x25/0x01` | 68/73 B | Ceres 67/70 B (one byte short) |
| `0x03/0x07` fragments | 1015 B | multipart fragment size |
| C→S `0x03/0x1f/0x1e`, `/0x17`, `/0x27`, `/0x32`, raw `0x32`, raw `0x1f/0x15`, raw `0x07` | | item use, object use, dialog close, subway… — client features not exercised against Ceres in the corpus |

Not gaps: raw `0x1f/0x55` ↔ `0x56` is a mob poll (`1f cf03 55` → `…56
00000000`, fire_bc_v1) already implemented as `InteractionPoll`/`Ack`; it will
appear once mobs exist.

## Restoring live verification

1. Reinstall the client somewhere writable (the launcher's CDN updater,
   `cdn.neocron-game.com/apps/nc2retail/files`) and point
   `NC2_CLIENT_PATH` / the launcher `installDir` at it. Until then the server
   runs from the May-03 defs copy:
   `NC2_CLIENT_PATH=$PWD/../tools/tmp/home/javier/Neocron2 docker compose up -d`.
2. Copy the x64 harness binaries from `tools/frida_re/launcher-addon/`
   (`dinput8.dll`, `frida-gadget.asi`) into the game dir and write the same
   JSON as `frida-gadget.config` (frida derives the config name from the
   gadget's — `.config.json` is never read).
3. `run_pass.sh ceres <id>` + `run_pass.sh retail <id>`, then
   `trace_diff.py` / `corpus_gap.py`.
