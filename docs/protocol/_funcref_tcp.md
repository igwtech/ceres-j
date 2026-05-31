# NC2 Protocol — TCP Opcode Function Reference

_Consolidated, navigable reference for the Neocron 2 TCP opcode space._
**Synthesized** from existing protocol docs + user memory — not re-derived
from captures. Every row links to the detailed per-packet stub
(`packets/tcp_*.md`) or the source doc/memory note where the bytes live.
Where a fact is genuinely unknown after consolidating, it says **unknown**.

## Conventions (from `OPCODE_STRUCTURE.md` §1)

- Every TCP opcode is **2 bytes, big-endian**: **high byte = subsystem,
  low byte = operation** within it.
- On-wire framing (stripped by `netlib.dll` NETMGR/SRVNETMGR before the
  protocol handler sees it): `fe [payloadLen LE16] [subsystem] [op] [body…]`.
  Handlers receive the *unframed* `[subsystem][op][body…]`
  (`RE_tcp_confirm.md` §1).
- Direction is mostly implied by the high byte (see subsystem map below).
- The in-game TCP S→C dispatcher (`FUN_0055aa30`) only accepts byte-0
  `0x83` or `0x87`; everything else logs `Unknown Message Type`. The `0x80`
  and `0xa0` subsystems are handled on a **separate session/UI TCP socket**,
  not the WorldClient gameplay handler (`RE_tcp_confirm.md` §1.2–§1.3).

### Subsystem map (high byte)

| High byte | Subsystem | Typical direction | Notes |
|---|---|---|---|
| `0x80` | 3-way connection handshake | mixed | single-byte magic constants `f`/`x`/`h` |
| `0x83` | Game session state | almost always **S→C** | server-driven push; also answers `0x84xx` requests |
| `0x84` | Account / character | always **C→S** | client-driven account/char ops |
| `0x87` | Gamedata / config sync | request/response pairs | `0x8737` C→S, `0x873a` S→C |
| `0xa0` | Session-ready handshake (post-auth) | mixed | `a001`/`a002` S→C, `a003` C→S |
| `0x82`, `0x85`, `0x86`, `0x88`–`0x9f` | predicted unused / sparse | — | none observed in 17-pcap corpus |

### Request ↔ response pairing rule (`OPCODE_STRUCTURE.md` §1)

Client `0x84xx` requests are answered by server `0x83xx` replies; the low
byte is remapped (request low byte → reply low byte). Same-family pairs
also exist within `0x80xx`, `0x87xx`, `0xa0xx`.

| C→S request | S→C reply | Pattern |
|---|---|---|
| `0x8480` Auth | `0x8381` AuthAck | `84/80` → `83/81` |
| `0x8482` GetCharList (sub 01/06) | `0x8385` CharList | `84/82` → `83/85` |
| `0x8482` (InfoServer phase) | `0x8383` ServerList | `84/82` → `83/83` |
| `0x8482` (sub 03/05/07 char-op) | `0x8386` CharOpAck | `84/82` → `83/86` |
| `0x8301` ResumeAuth | `0x8305`+`0x830d`+`0x830c` | triggers UDP-handover burst |
| `0x8737` GetGamedata | `0x873a` Gamedata (+ world-entry burst) | `87/37` → `87/3a` (low byte +3) |
| `0x873c` GetUDPConnection | **no reply** (server no-op) | — |
| `0x8000` HandshakeB | `0x8001` HandshakeA + `0x8003` HandshakeC | handshake-internal |
| `0xa003` SessionReady-C (ReadyProbe) | `0xa001` SessionReady-S | `a0/03` ↔ `a0/01` |

---

## Subsystem `0x80` — 3-way connection handshake

| opcode | dir | function (one line) | pair | source |
|---|---|---|---|---|
| `0x8000` | C→S | HandshakeB / HandshakeStart — client's first packet on a fresh TCP conn; constant `80 00 78` (`x`) | → `0x8001`+`0x8003` | [`tcp_c2s_8000.md`](packets/tcp_c2s_8000.md) |
| `0x8001` | S→C | HandshakeA — server greeting; constant `80 01 66` (`f`) | reply to conn-accept | [`tcp_s2c_8001.md`](packets/tcp_s2c_8001.md) |
| `0x8003` | S→C | HandshakeC — third handshake step; constant `80 03 68` (`h`) | follows HandshakeB | [`tcp_s2c_8003.md`](packets/tcp_s2c_8003.md) |

Note — keepalive flavor: memory [`tcp_auth_handshake_opcodes.md`] records
`0x80/0x01` `0x66` (S→C) / `0x80/0x00` `0x78` (both) firing every ~24s as a
**plain TCP keepalive** during a live session, distinct from the login
handshake. Same byte values; `0x8002` HandshakeB-from-server is predicted
but **not separately captured** (the `80 00 78` form covers C→S).

---

## Subsystem `0x83` — game session state (S→C)

| opcode | dir | function (one line) | pair | source |
|---|---|---|---|---|
| `0x8303` | S→C | Connection-rejected / char-error ASCII frame (e.g. "Char does not exist on this server") — dispatcher `case 0x03` → fatal | error reply to `0x8301`/`0x8480` | `RE_tcp_confirm.md` §1.1–§1.2; mem [`tcp_8388_party_management`] lists it DECODED |
| `0x8305` | S→C | UDPServerData — 28B UDP-handoff descriptor (account_id, char_id, server_ip, udp_port, flags `0x00890000`, 8B XOR session_id `127−sid[i]`) | part of world-entry burst | [`tcp_s2c_8305.md`](packets/tcp_s2c_8305.md) |
| `0x8317` | S→C | Chat / system message broadcast — `[sender_uid LE32][name_len][channel][sub_channel][name+msg ASCII]`; channels 3=team 4=local 5=zone/drop `0xff`=server-broadcast (`ffffffff` sender, `0c ff 00` for NCPD) | — | [`tcp_s2c_8317.md`](packets/tcp_s2c_8317.md); mem [`tcp_8317_gm_broadcast_chat`] |
| `0x8318` | S→C | Team/party member event — 7B `83 18 01 [target_uid LE32]`; **likely** member-online/contact-update (party-only, 2 samples) | — | [`tcp_s2c_8318.md`](packets/tcp_s2c_8318.md) |
| `0x830c` | S→C | Location — tells client which zone BSP to load: `[location LE32][apt_flag LE32][4B zero/pad][zone_path ASCIIZ]` (e.g. `plaza/plaza_p3\0`); offset-0 byte = door_id shared key w/ UDP Zoning1; spawn_flag `0x10`=login else `0x00` | reply in zone-change burst | [`tcp_s2c_830c.md`](packets/tcp_s2c_830c.md); mem [`tcp_830c_bsp_path_decoded`] |
| `0x830d` | S→C | GameinfoReady / zone-transition-begin — 4B constant `83 0d 00 00`; "gameinfo ready, begin UDP" + loading-screen trigger | precedes `0x830c` in zone burst | [`tcp_s2c_830d.md`](packets/tcp_s2c_830d.md); `RE_tcp_confirm.md` §2 |
| `0x8381` | S→C | AuthAck — 31B `[account_id LE32][0 LE32][len=18][18B session_data][trailer]`; session_data **echoes the Auth password ciphertext** (proof of byte-exact decode) | reply to `0x8480` | [`tcp_s2c_8381.md`](packets/tcp_s2c_8381.md); mem [`retail_3stage_login_arch`] |
| `0x8383` | S→C | InfoServer ServerList / ServerInfo — 26B count-prefixed server record: `server_ip`, `udp_port=12000`, `chars_per_account=4`, `online_users LE16`, `name "titan\0"` | reply to `0x8482` on InfoServer phase | [`tcp_s2c_8383.md`](packets/tcp_s2c_8383.md); mem [`tcp_auth_handshake_opcodes`] |
| `0x8385` | S→C | CharList — 209–225B; `[session_byte u16][char_count=4][struct_size=0x29][4× 41B char_struct]`; empty slot = `ff ff ff ff…` CHARDUMMY | reply to `0x8480`/`0x8482` | [`tcp_s2c_8385.md`](packets/tcp_s2c_8385.md) |
| `0x8386` | S→C | CharOpAck — 7B success `83 86 01 00 00 00 [status]` (0x00 create / 0x05 delete / 0x3d name-check) OR variable error `06 00 [len LE16][ASCII]` | reply to `0x8482` sub 03/05/07 | [`tcp_s2c_8386.md`](packets/tcp_s2c_8386.md) |
| `0x8388` | S→C | TeamEvent — `[target_uid LE32][event_type LE32][payload_size LE32][payload]`; events 0x41 invite/0x42 ack/0x43 join(9B+role)/0x44 clear/0x48 disband-heartbeat | — | [`tcp_s2c_8388.md`](packets/tcp_s2c_8388.md); mem [`tcp_8388_party_management`] |
| `0x838f` | S→C | TCP keepalive — 7B constant `83 8f 00 00 00 00 00`, ~10s cadence; **no-op in client** (`FUN_0055aa30 case -0x71: break;`), NOT an interaction commit | — | [`tcp_s2c_838f.md`](packets/tcp_s2c_838f.md); `RE_tcp_confirm.md` §3.5 |

Dispatcher-only `0x83` ops (seen in `FUN_0055aa30`/`FUN_0055a5e0`, not all
captured as standalone packets — `RE_tcp_confirm.md` §1.1–§1.2):

| opcode | dir | function (one line) | source |
|---|---|---|---|
| `0x8305` | S→C | also the **NetHost "Client accepted %i" session-join** case (reads char/world ids into client state) | `RE_tcp_confirm.md` §1.1–§1.2 |
| `0x8317` | S→C | generic per-entity action/state apply → UI event `0xfa8` (rarer server-pushed state path; distinct from the Chat use of `0x8317`) | `RE_tcp_confirm.md` §1.2, §5 |
| `0x8318` | S→C | DCB-style query reply → UI event `0xfac` (dispatcher case; distinct from the captured 7B team-event use) | `RE_tcp_confirm.md` §1.2 |
| `0x8388` | S→C | TCP→UDP bridge case: wraps body into a `0x15`-typed reliable UDP msg → WWORLDMGR enqueue (dispatcher case; distinct from the captured TeamEvent use) | `RE_tcp_confirm.md` §1.2 |

> **Honesty note:** `0x8317`/`0x8318`/`0x8388` each appear BOTH as a
> captured packet with a decoded body AND as a differently-described
> dispatcher `case`. The captured semantics (Chat / TeamEvent) and the
> decompiler `case` semantics (generic-apply / DCB-reply / TCP→UDP bridge)
> are reconciled as: the same op byte routes by body content; the
> high-traffic captured form is the dominant real use. Exact disambiguation
> rule is **unknown** — flagged for a future capture.

---

## Subsystem `0x84` — account / character (C→S)

| opcode | dir | function (one line) | pair | source |
|---|---|---|---|---|
| `0x8301` | C→S | ResumeAuth (stage-3 char-select + session resume on fresh GameServer:12000 conn) — 53B `[session_tag 4B][reserved 4B][salt 8B (K=salt[0])][char_spot LE32][pw_len][user_len][user CStr][pw cipher]`; triggers UDP handover | → `0x8305`/`0x830d`/`0x830c` | [`tcp_c2s_8301.md`](packets/tcp_c2s_8301.md); mem [`retail_login_3stage_complete`] |
| `0x8480` | C→S | Auth — 65B, fully reverse-engineered: `[K][salt 7B][build_ver LE16][hash.ini SHA1 4+4B][MAC XOR bVar7][user_len][pw_len][user CStr][pw cipher]`; password cipher `FUN_005f4490`, deterministic from MSVCRT rand | → `0x8381` | [`tcp_c2s_8480.md`](packets/tcp_c2s_8480.md); mem [`retail_password_cipher_cracked`], [`retail_integrity_bytes_cracked`] |
| `0x8482` | C→S | GetCharList / char-management — 28B preamble + sub-action byte[6]: 01 query / 06 re-query ("ready" marker) / 05 create (ASCII name) / 07 enter-world (stat array + subskill pairs) | → `0x8383`/`0x8385`/`0x8386` | [`tcp_c2s_8482.md`](packets/tcp_c2s_8482.md) |

> **Naming note:** `0x8301` lives in the `0x83` opcode space but is **C→S**
> (the one exception to "0x83 = server-driven"). It is the stage-3 resume
> request; its replies come back as `0x83xx`/`0x83/0x05` world-entry packets.

---

## Subsystem `0x87` — gamedata / config sync

| opcode | dir | function (one line) | pair | source |
|---|---|---|---|---|
| `0x8737` | C→S | GetGamedata — 6B constant `87 37 00 00 00 00`; requests world-entry data, triggers `873a`+`8305`+`830d`+`830c` burst (UDPServerData MUST precede Location) | → `0x873a` | [`tcp_c2s_8737.md`](packets/tcp_c2s_8737.md) |
| `0x873a` | S→C | Gamedata — 2B constant `87 3a`; ack to GetGamedata, first of world-entry burst | reply to `0x8737` | [`tcp_s2c_873a.md`](packets/tcp_s2c_873a.md) |
| `0x873c` | C→S | GetUDPConnection — 6B constant `87 3c 00 00 00 00`; "UDP ready?" probe. **Server response must be a no-op** — sending a 2nd UDPServerData causes a 25s disconnect | **no reply** | [`tcp_c2s_873c.md`](packets/tcp_c2s_873c.md); mem [`project_zone_handoff_fixed`] |

> **Legacy / dead path:** the stage-2 lobby `0x87/0x37 → 0x87/0x3a`
> sub-exchange is **dead in current (May 2026) retail** — the live server
> TCP-ACKs `0x87/0x37` but no longer replies `0x87/0x3a`; the modern login
> path uses `0xa0/0x03 → 0xa0/0x01` instead (mem [`retail_3stage_login_arch`],
> [`retail_login_3stage_complete`]). The `0x87` subsystem high byte also =
> `NETMSG_GAMEMASTERTOOL` and is the only other byte-0 the in-game
> dispatcher accepts besides `0x83` (`RE_tcp_confirm.md` §1.2).

---

## Subsystem `0xa0` — session-ready handshake (post-auth)

| opcode | dir | function (one line) | pair | source |
|---|---|---|---|---|
| `0xa001` | S→C | SessionReady-S — 2B `a0 01` (legacy) OR 10B `a0 01 + 15 00 00 00 00 00 80 3f` (LE32=21 + float 1.0); **required** between AuthAck and CharList or modern client hangs on "updating data" | reply to `0xa003` | [`tcp_s2c_a001.md`](packets/tcp_s2c_a001.md) |
| `0xa002` | S→C | InteractionAck — 2B constant `a0 02`; session-level transaction/lock-release ack on a separate TCP socket. **NOT** a per-action animation trigger (not dispatched by WorldClient handler) | acks some C→S interactions | [`tcp_s2c_a002.md`](packets/tcp_s2c_a002.md); `RE_tcp_confirm.md` §1.3, §7.4 |
| `0xa003` | C→S | SessionReady-C / ReadyProbe — 2B `a0 03`; synchronous ready-probe, client waits ~170ms for `0xa001` before issuing GetCharList; retries until answered | → `0xa001` | [`tcp_c2s_a003.md`](packets/tcp_c2s_a003.md) |

---

## Per-action confirmation transport (which packet commits an action)

From `RE_tcp_confirm.md` §0 — confirmation transport is **per-action**, not
all-TCP. Only zone/world-change is TCP-confirmed:

| Action | Confirm transport | Confirm packet |
|---|---|---|
| Zone / portal / world-change | **TCP** | `0x83/0x0d` (begin) → `0x83/0x0c` (Location) |
| Sit / stand / use-object / equip | **UDP** `0x03/0x1f` (NOT TCP) | per-entity `0x1f` echo (`0x17`/`0x21`/`0x22`/`0x4c`) |
| Generic server-pushed state (rare) | TCP | `0x83/0x17` → UI event `0xfa8` |
| Session/transaction lock-release | TCP | `0xa0/0x01` (login) / `0xa0/0x02` (events) — not per-action |

---

## Login flow ordering (3-stage TCP — `retail_3stage_login_arch`)

1. **Stage 1 InfoServer :7000** — Handshake → `0x8480` Auth (K=0x29) →
   `0x8381` AuthAck → `0x8482` AuthB (server_slot=1) → `0x8383` ServerList.
2. **Stage 2 GameLobby :12000** (new TCP) — Handshake → `0x8480` Auth
   (K=0x1c) → `0x8381` AuthAck → `0xa003`→`0xa001` ready-probe → `0x8482`
   GetCharList (byte6=0x06 const) → `0x8385` CharList.
3. **Stage 3 GameSession :12000** (another new TCP) — Handshake → `0x8301`
   ResumeAuth (char spot LE32) → `0x8305` UDPServerData → `0x830d`
   GameinfoReady → `0x830c` Location → UDP world stream begins.

`AuthAck.session_data` == the Auth password ciphertext (server re-encodes
and echoes; ciphertext must be byte-exact for retail to accept).

---

## Coverage summary

**Documented TCP opcodes: 19** (all distinct opcodes in the 17-pcap corpus +
dispatcher-only cases).

| Status | Opcodes |
|---|---|
| **Fully decoded / verified** (16) | `0x8000`, `0x8001`, `0x8003`, `0x8301`, `0x8305`, `0x830c`, `0x830d`, `0x8381`, `0x8383`, `0x8385`, `0x8480`, `0x8482`, `0x8737`, `0x873a`, `0xa001`, `0xa003` |
| **Partial / body-decoded, semantics open** (5) | `0x8317` (sub_channel byte unknown), `0x8318` (trigger semantics), `0x8386` (full status-byte enum), `0x8388` (0x42 vs 0x44 distinction, role byte), `0x873c` (4 trailing zero bytes) |
| **Dispatcher-only / op-byte overload unresolved** | `0x8303` (error frame, decoded), `0x8317`/`0x8318`/`0x8388` (captured-use vs decompiler-case disambiguation **unknown**), `0xa002` (exact C→S trigger set **unknown**) |
| **Predicted-unobserved** (`OPCODE_STRUCTURE.md` §1) | `0x8002` (server HandshakeB), `0x8484` (char-delete?), `0x8387` (char-delete ack?), other `0x83xx`/`0x84xx` low bytes for unseen scenarios; `0x82`/`0x85`/`0x86`/`0x88`–`0x9f` high bytes likely unused |

**Genuinely unknown after consolidation:**
- `0x8305` flags field — retail shows `0x00130000` AND `0x00890000`; meaning unpinned.
- `0x8381` trailer byte `0x00` vs `0x2d` — no time/account correlation found.
- `0x8385` `session_byte` u16 — 17 distinct values, semantic unknown (client accepts any).
- `0x8317` `sub_channel` (`0x00`/`0x10`/`0x11`) and `0x8383` `online_users`/`unknown_flag` units.
- `0x83/0x88` 16B retail-only variant `fe 85 01 00 48 00 00 00 04 00 00 00 fe 85 01 00` — sample-of-1, distinct from the TeamEvent body (mem [`tcp_8317_gm_broadcast_chat`]).
- The op-byte overload of `0x8317`/`0x8318`/`0x8388` (captured form vs decompiler case).

---

## Sources consolidated

- `docs/protocol/OPCODE_STRUCTURE.md` §1 (subsystem map, pairing rule, predictions)
- `docs/protocol/RE_tcp_confirm.md` (dispatcher cases, per-action confirm transport)
- `docs/protocol/INDEX.md` (corpus opcode census)
- `docs/protocol/packets/tcp_*.md` (per-packet stubs, linked inline)
- Memory: `tcp_auth_handshake_opcodes`, `tcp_830c_bsp_path_decoded`,
  `tcp_8317_gm_broadcast_chat`, `tcp_8388_party_management`,
  `tcp_confirm_per_action`, `retail_3stage_login_arch`,
  `retail_login_3stage_complete`, `retail_password_cipher_cracked`,
  `retail_integrity_bytes_cracked`, `project_zone_handoff_fixed`,
  `retail_cross_burst_protocol`
