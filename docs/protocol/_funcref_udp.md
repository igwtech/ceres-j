# UDP Transport & Gamedata-Wrapper — FUNCTION REFERENCE

_Consolidated reference for the NC2 **UDP transport layer**, the **0x13 gamedata
sub-wrappers**, and the **0x03 reliable OP-byte space**. This doc covers the
**wrappers / "types"**, not the deep `0x03/0x1f` act_tags (covered separately —
see [`SUBTAGS.md`](SUBTAGS.md) and `c2s_03_1f_envelope_canonical` in memory) and
not TCP (separate ref)._

**Honesty contract:** every row is tagged with a confidence in the *function*.
`verified` = byte-decoded against retail captures or client disasm. `partial` =
shape known, function inferred. `unknown` = genuinely not yet determined — not
guessed. Source links point at the per-packet stub (`packets/udp_*.md`) and/or
the durable memory note.

Sources consolidated: `OPCODE_STRUCTURE.md`, `RE_reliable_window.md`,
`RE_state_sync.md`, `INDEX.md`, `SUBTAGS.md`, the 78 `packets/udp_*.md` stubs,
and memory notes (`udp_transport_layer`, `udp_13_wrapper_corrected`,
`raw_channel_three_envelopes`, `ceres_nak_storm_is_liveness_not_reliable`,
`reliable_ack_08_decoded`, `multipart_framing`, `udp_0d_33_32_decoded`,
`udp_2f_25_player_broadcasts`, `s2c_2d_npcdata_variants`,
`s2c_28_1b_variant_distribution`, `udp_3c_state_poll`, `udp_new_opcodes_2026_05_29`,
`udp_f2_challenge_01_ack`, `correction_1f_is_event_class`).

---

## Protocol stack (the 3 layers this doc spans)

```
UDP wire datagram
└─ LFSR+CFB per-packet cipher  (cipher_cracked.md / WireEncrypt.java)
   └─ TRANSPORT opcode = decrypted plaintext[0]   ← §1
      ├─ 0x01/02/03/04/08/0b/0c/f2  : connection & keepalive endpoints
      └─ ≥0x0F  : APPLICATION payload
            (client biases opcode by -0x0F before app dispatch; so wire 0x13
             → app handler "0x04"; the 0x13 gamedata path is the default branch.)
         ├─ 0x13 gamedata wrapper  → sub-wrapper stream            ← §2
         │     [0x13][ctr LE2][ctr+key LE2] ( [subLen LE2][wrapper][...] )+
         │        wrapper ∈ {0x01 retransmit-req, 0x02 simplified-reliable,
         │                   0x03 reliable-data, 0x07 multipart, 0x08 primer,
         │                   0x09 server-ack}
         │        reliable sub: [0x03][seq LE2][OP][body]           ← §3
         ├─ raw direct  [op][body]            (unreliable, channel-dual)  ← §2.2
         └─ 0x02 rewrap [02][seq LE2][op][00][body]  (state-push)         ← §2.2
```

**Client dispatch ground-truth** (Ghidra, `ceres_nak_storm_is_liveness_not_reliable`):
game-path dispatcher `FUN_00560660` switches on `plaintext[0]`:
`1`=Connect, `2`=ConnectAccept, `3`=reliable-data, `4`=Alive-Rep/ack,
`8`=Disconnect, `default (>0x0e)` → `op -= 0x0f` → app handler at `connmgr+0x1060`
(the 0x13 gamedata path). **The client never reads the 0x13 OUTER counter** for
ack/dedup/ordering — outer ctr is cosmetic to the client.

---

## §1 — UDP transport opcodes (decrypted `plaintext[0]`, BEFORE the 0x13 app layer)

These sit at the same depth as 0x13 but are NOT wrapped. They are
connection/keepalive endpoints, not gamedata.

| op | dir | function (one line) | wire shape | source / detail |
|---|---|---|---|---|
| `0x01` | C→S | **UDPConnectRequest** — opens the UDP session; body is the login session_id XOR'd 0x7f/byte; retried until accepted (9 hits at session start) | `01 [session_id^0x7f :8B][00]` | `udp_transport_layer`; `retail_udp_session_id_xor`; stub [`udp_c2s_01`](packets/udp_c2s_01.md) |
| `0x02` | S→C | **ConnectAccept** — server's transport-layer accept of the 0x01 connect (client dispatch case 2) | shape unconfirmed (transport-level) | client disasm `ceres_nak_storm…`; transport S→C `0x02` distinct from §2.2 app-rewrap |
| `0x03` | C→S | **UDPAlive (client keepalive)** — transport liveness ping; client fast-pings if no matching S→C arrives. Body constant across a session | `03 [b0 a2 00 00 01 e3 c9]` (8B; trailing `e3 c9` = session token) | `udp_transport_layer`; client gate `FUN_00560660` resets liveness timer on any matching-station packet |
| `0x04` | S→C | **UDPAlive-Rep / AliveResponse** — paired reply to C→S 0x03; **server MUST emit** or the client marks the UDP link dead. Payload bytes are NOT validated by the client (only station ip:port match + `stationIdx>0` resets the timer) | `04 [01 00 37 44 e3 c9]` (7B) | `udp_transport_layer`; `ceres_nak_storm…` (Q1); stub [`udp_s2c_04`](packets/udp_s2c_04.md) |
| `0x08` | C→S | **UDPDisconnect** — graceful UDP teardown; bare opcode, fires at session end (±retries). Server closes the socket cleanly | `08` (1B) | `udp_transport_layer`; client dispatch case 8; stub [`udp_c2s_08`](packets/udp_c2s_08.md) |
| `0x0b` | both | **CPing/SPing latency probe** — round-trip ping; size is the direction discriminator (C→S shorter, S→C longer). High volume (~5k/session) | C→S 5B / S→C 9B; raw form has 9B & 13B variants | stubs [`udp_c2s_0b`](packets/udp_c2s_0b.md) / [`udp_s2c_0b`](packets/udp_s2c_0b.md); `raw_0x0b_latency_probe`; `OPCODE_STRUCTURE.md §7` |
| `0x0c` | C→S | **TimeSync (client clock report)** — variable body (min 5B, avg 29B); sample inner `0c ff c8 02 05 …`. Correlates with world-enter / sewer-enter | `0c [varies]` | stub [`udp_c2s_0c`](packets/udp_c2s_0c.md) |
| `0x0d` | S→C | **TimeSync (server, raw outer)** — rare raw outer; structurally a single-sub gamedata frame `0d 00 03 [seq] 1f 01 00 [tag][tag2]` per the §1 "single-sub wrapper" hypothesis. (Distinct from reliable `0x03/0x0d`, §3.) | 10B single-sub shape | `OPCODE_STRUCTURE.md §2 hypothesis`; stub [`udp_s2c_0d`](packets/udp_s2c_0d.md) |
| `0x0f` | S→C | **unknown** — single-sub-wrapper candidate (`0f 00 03 [seq] 1f 01 00 …`); event class undetermined (11 obs). **Not** a Type-15 packet — see note below | 10B single-sub shape | `OPCODE_STRUCTURE.md §2`; stub [`udp_s2c_0f`](packets/udp_s2c_0f.md) |
| `0x11` | S→C | **unknown** — single-sub-wrapper candidate, event class undetermined (2 obs) | 10B single-sub shape | `OPCODE_STRUCTURE.md §2`; stub [`udp_s2c_11`](packets/udp_s2c_11.md) |
| `0x1d` | S→C | **unknown** — single-sub-wrapper candidate, event class undetermined (1 obs) | 10B single-sub shape | `OPCODE_STRUCTURE.md §2`; stub [`udp_s2c_1d`](packets/udp_s2c_1d.md) |
| `0x13` | both | **Gamedata wrapper** — the application multiplexer; carries 1+ length-prefixed sub-wrappers. See §2 | `13 [ctr LE2][ctr+key LE2] (subs…)` | `udp_13_wrapper_corrected`; `OPCODE_STRUCTURE.md §2` |
| `0xf2` | S→C | **periodic challenge / cipher-reseed probe** (function UNCONFIRMED) — 11B, ~2 per session @ ~120s; body looks encrypted/hashed; no paired response captured. Likely session-key rotation or anti-cheat | `f2 01 [10B opaque]` | `udp_f2_challenge_01_ack`; stub [`udp_s2c_f5`](packets/udp_s2c_f5.md)/[`udp_s2c_f6`](packets/udp_s2c_f6.md) (sibling 0xf5/0xf6) |
| `0x05` `0x06` | both | **unknown** — one-off single-byte echoes; suspected cipher-state markers during reseed (2-6 obs) | 1B | `OPCODE_STRUCTURE.md §2 predicted`; stubs `udp_*_05`/`udp_*_06` |
| `0x44` `0x45` `0x55` `0xc4` `0xef` `0xf5` `0xf6` `0x3a` `0x3e` | varies | **unknown** — sparse single-occurrence outers (1-6 obs each); upper-range cipher echoes / rare runtime events | varies | `OPCODE_STRUCTURE.md §2 (0x40-0xff very-low confidence)`; per-packet stubs |

> **Application-layer 0x01 ≠ transport 0x01.** Inside the 0x13 wrapper, sub-wrapper
> `0x01` is the **reliable retransmit-request** (`01 [seq LE2]`, see §2). Raw C→S
> `0x01` (`01 [seq LE2]`, 3B, the dominant idle "NAK") is the same retransmit-request
> seen unwrapped. The **transport** `0x01` (UDPConnectRequest, 10B XOR'd) is a
> different beast at a different depth. (`udp_f2_challenge_01_ack`,
> `reliable_ack_08_decoded`.)

> **"Corrupted Message Type:15" is NOT an opcode.** There is no `case 0x0F` in the
> app dispatcher; a body whose byte0 is 0x0F falls into `default:` and logs
> `@WWORLDMGR : Corrupted Message Type:15, Size:N` — it means an N-byte **misframe**,
> not a real Type-15 packet. (`RE_state_sync.md §"Type-15"`.)

---

## §2 — 0x13 gamedata sub-wrappers

### 2.1 Outer 0x13 header (corrected layout)

```
0x00  1   0x13
0x01  2   ctr   LE16   — this side's outgoing counter (monotonic per side)
0x03  2   ctr+sessionkey LE16 — "ack"/MUID field = ctr + key  (retail K=0xfbdc)
0x05  …   one or more sub-packets:
            0x00  2  body_len LE16
            0x02  N  body   ( [wrapper][...] )
```

362/362 retail datagrams parse cleanly under this; 60% carry **multiple** subs
(max 6) — movement bursts coalesce position deltas + acks into one datagram.
The OLD doc's "[counter BE4][1B sub-len]" was wrong. (`udp_13_wrapper_corrected`.)

The first sub-byte (`wrapper`) selects the channel:

| wrapper | dir | function (one line) | window effect | source / detail |
|---|---|---|---|---|
| `0x01` | C→S | **Reliable retransmit-request** — client asks server to resend a missing seq. Time-driven (6s timer, retry<6), NOT gap-driven | n/a (request) | `RE_reliable_window`; `ceres_nak_storm…` (Q2); `reliable_ack_08_decoded`; stub [`udp_c2s_01`](packets/udp_c2s_01.md) |
| `0x02` | S→C (mostly) | **Simplified-reliable / retransmit-reply** — recovered delivery. Client routes it to `ProcessGuaranteedMsg` with `isResponse=1`; **it does NOT advance the receive window** — accepted but expected-seq pointer doesn't move. Shares the 0x03 seq namespace | does **not** advance | `RE_reliable_window` (rule 1); `ceres_nak_storm…` (seq-collision); stub [`udp_s2c_02`](packets/udp_s2c_02.md) |
| `0x03` | both | **Reliable data** — the windowed channel; `isResponse=0`; **advances the receive window**. Carries `[03][seq LE2][OP][body]`. All ordered STATE must ride here, never 0x02 | advances +1/accepted | `RE_reliable_window`; §3 below |
| `0x07` | both | **Multipart reassembly stream** — only valid as `0x03/0x07`; fragments a logical packet too big for one datagram. chain_key=0x00 + 6-byte per-fragment header `[0x00][disc][total_size LE4]`; disc 0x01=CharInfo, 0x02=CharsysInfo, 0x03/0x04/0x38 partial | rides 0x03 window | `multipart_framing`; `OPCODE_STRUCTURE.md §4`; stubs [`udp_s2c_03_07_01`](packets/udp_s2c_03_07_01.md) / `_02` / `_38` |
| `0x08` | both | **ReliableAck (C→S) / ZoningEnd window-primer (S→C)** — C→S: explicit ack, inner=`(seq-1) LE16`. S→C: `08 [mapId LE2] 00` is the **post-reset window primer** re-basing the client's window after a zone-cross reset (NOT used in the login burst — login leads with CharInfo 0x2c) | does NOT advance window | `reliable_ack_08_decoded`; `RE_reliable_window` (rule 3); stub [`udp_c2s_03_08`](packets/udp_c2s_03_08.md) |
| `0x09` | S→C | **ServerReliableAck** — symmetric server-side ack of a client reliable; inner=`(seq-1) LE16`. Retail sends ~3×/session (high-importance only). ⚠ firing it per-received-reliable crashes the client (ack-of-ack storm) | n/a (ack) | `reliable_ack_08_decoded`; stub [`udp_s2c_03_09`](packets/udp_s2c_03_09.md) |

**Reliable window rules** (live-Frida ground truth, `RE_reliable_window`):
1. `0x02` does NOT advance the window — only `0x03` does. Retail login = **zero** 0x02.
2. Reliable seq must **start at 1**; the client **drops seq 0** (`seq & 0x7ff == 0` = sentinel).
3. The login burst must lead with real windowed data (`0x2c` CharInfo), not a `0x08` control op.

### 2.2 The other two envelopes (channel-duality)

Same inner opcodes ride three envelope formats (`raw_channel_three_envelopes`):

| envelope | dir | function | wire shape | source |
|---|---|---|---|---|
| **raw direct** | mostly S→C | **unreliable broadcast** — high-volume, drop-OK (next tick overwrites). Carries pos/world broadcasts. Raw `0xNN` ≡ reliable `0x03/0xNN` (same body schema). ~38% of broadcast traffic | `[op][body]` (no envelope) | `raw_channel_three_envelopes`; `OPCODE_STRUCTURE.md §2 duality` |
| **0x02 rewrap** | S→C | **state-push** — used by retail for ONLY 3 opcodes: `0x2e` Weather, `0x23` Config, `0x2f` SelfStateHeartbeat. ⚠ Ceres-J over-uses it on 13+ opcodes (`ceresj_02_envelope_overuse`) — retail clients may treat 0x02 as drop-OK | `02 [seq LE2][op][00][body]` | `raw_channel_three_envelopes`; `ceresj_02_envelope_overuse` |

> Dominant raw broadcasts (vehicle pcap): raw `0x1b` 58k, raw `0x20` 19k,
> raw `0x0b` 1.3k, raw `0x32` 1.5k. Raw opcodes carry size variants matching their
> `0x03/*` siblings (delta-compression).

---

## §3 — The 0x03 reliable OP byte space (`[0x03][seq LE2][OP][body]`)

Parser note: in a reliable sub the **OP is at body[3]** (body[1..2] = inner_seq
LE16), NOT body[1] (`udp_new_opcodes_2026_05_29`). Ranges mirror the raw-outer map
(`OPCODE_STRUCTURE.md §3`). `0x03/0x1f` is the dominant op (~104k obs) — its
act_tags are documented separately in `SUBTAGS.md`.

### 0x00–0x0f — connection-level reliable

| OP | dir | function | wire / size | source / stub |
|---|---|---|---|---|
| `0x00` | C→S | **Script-list upload** — client tells server which Lua scripts it has (ASCII paths embedded; raw-0x07 single-frag form seen) | varies | stub [`udp_c2s_03_00`](packets/udp_c2s_03_00.md); `c2s_raw_channel_lua_55` |
| `0x07` | both | **Multipart** — see §2 (0x07 wrapper) | fragment stream | `multipart_framing` |
| `0x08` | C→S/S→C | **ReliableAck / ZoningEnd primer** — see §2 | 6B / `08 [mapId LE2] 00` | `reliable_ack_08_decoded` |
| `0x09` | S→C | **ServerReliableAck** — see §2 | 6B | stub [`udp_s2c_03_09`](packets/udp_s2c_03_09.md) |
| `0x0d` | S→C | **TimeSync (paired-txn request)** — 16B, two monotonic u32 timestamps + 2 session-constant LE16 ids; ALWAYS paired 1:1 with `0x33`. Fires ~5-10s | `0d [t_a LE32][t_b LE32][id LE16][id LE16]` | `udp_0d_33_32_decoded`; stub [`udp_s2c_03_0d`](packets/udp_s2c_03_0d.md) |

### 0x10–0x1f — reliable state stream

| OP | dir | function | wire / size | source / stub |
|---|---|---|---|---|
| `0x1b` | S→C | **PlayerMovement / PosUpdate** — reliable position broadcast; 6 size variants (15B base, 26B, 50B full, 23B/19B/22B) = delta-compression. Raw `0x1b` is the unreliable high-volume sibling | 15-50B | `s2c_28_1b_variant_distribution`; stub [`udp_s2c_03_1b`](packets/udp_s2c_03_1b.md) |
| `0x1f` | both | **GamePackets — the general event channel** (highest-traffic reliable op). Envelope `[1f][mapId LE2][sub-tag][body]` (the `01 00` is mapId, not a fixed prefix). act_tags documented separately | 4-821B | `correction_1f_is_event_class`; `SUBTAGS.md`; stubs [`udp_c2s_03_1f`](packets/udp_c2s_03_1f.md)/[`udp_s2c_03_1f`](packets/udp_s2c_03_1f.md) |

### 0x20–0x2f — reliable world events

| OP | dir | function | wire / size | source / stub |
|---|---|---|---|---|
| `0x22` | C→S | **Zoning / CharInfo-request** — C→S `0x03/0x22/0x0d` is Zoning1 (16B body, byte-pinned); also labeled CharInfo in the stub | 16B (Zoning1) | `zoning1_body_pinned`; stub [`udp_c2s_03_22`](packets/udp_c2s_03_22.md) |
| `0x23` | S→C | **InfoResponse / Config** — config/info push; also rides the 0x02-rewrap state-push channel | varies | stub [`udp_s2c_03_23`](packets/udp_s2c_03_23.md); `raw_channel_three_envelopes` |
| `0x24` | C→S | **session-init marker** — 6B `01 00 …`, rare | 6B | stub [`udp_c2s_03_24`](packets/udp_c2s_03_24.md); `c2s_2d_24_27_decoded` |
| `0x25` | S→C | **PlayerInfo / nearby-players broadcast** — each packet a different nearby char's snapshot (ASCII name + TLV stats). Distinct from `0x30` ShortPlayer and from the `0x1f/0x25` state-ack sub-tag | 65-90B | `udp_2f_25_player_broadcasts`; stub [`udp_s2c_03_25`](packets/udp_s2c_03_25.md) |
| `0x26` | S→C | **RemoveWorldItem / despawn / loot-listing** — entity despawn (8B) or vendor/loot listing (variable to 821B) | 8-821B | `OPCODE_STRUCTURE.md §8`; stub [`udp_s2c_03_26`](packets/udp_s2c_03_26.md) |
| `0x27` | C→S | **RequestWorldInfo** — client asks server for world/entity info (reliable form of raw 0x27). Distinct from the `0x1f/0x27` CloseDialog act_tag and the raw `0x27` object-visibility notify | varies | stub [`udp_c2s_03_27`](packets/udp_c2s_03_27.md); `c2s_2d_24_27_decoded` |
| `0x28` | S→C | **WorldInfo / NPC spawn** — 14 size variants. 17B (`27 00` marker) = cheap proximity/visibility update; 45-50B (`00 01` marker) = full spawn with embedded ASCII script_name; 79-93B = extended scripted NPC | 17-93B | `s2c_28_1b_variant_distribution`; `re_state_sync_authoritative`; stub [`udp_s2c_03_28`](packets/udp_s2c_03_28.md) |
| `0x2b` | both | **CityCom (DCB RPC)** — apartment/terminal RPC channel carrying ASCII method names ("DCBSetup", "VehicleListing", "VehicleControl") | varies | `c2s_2b_citycom_dcb`; stubs [`udp_c2s_03_2b`](packets/udp_c2s_03_2b.md)/[`udp_s2c_03_2b`](packets/udp_s2c_03_2b.md) |
| `0x2c` | S→C | **CharInfo / StartPos** — login character blob (single-packet form ≤~900B; >~900B splits into `0x03/0x07` multipart). Carries spawn pos + pool/skill sections. **Leads the login reliable burst** | up to ~900B | `nc2_tutorial_shard_finding`; `charinfo_field_positions`; stub [`udp_s2c_03_2c`](packets/udp_s2c_03_2c.md) |
| `0x2d` | both | **NpcData** — 5 size variants: 58B full NPC data (dominant), 40B vehicle-position re-broadcast (mirror of C→S 45B), 9B/13B/15B minimal state/attribute/equip flips. C→S 45B = client-authoritative vehicle position | 9-58B | `s2c_2d_npcdata_variants`; `c2s_2d_24_27_decoded`; stubs [`udp_s2c_03_2d`](packets/udp_s2c_03_2d.md)/[`udp_c2s_2d`](packets/udp_c2s_2d.md) |
| `0x2e` | S→C | **Weather / server tick** — 13B; doubled-u32 world clock with **+3 delta** per broadcast tick; `01 [type] 00 [flags LE2][tick LE32][tick LE32]`. Rides 0x02-rewrap | 13B | `udp_new_opcodes_2026_05_29`; stub [`udp_s2c_03_2e`](packets/udp_s2c_03_2e.md) |
| `0x2f` | S→C | **SelfStateHeartbeat / UpdateModel** — periodic OWN-character canonical state (identical bodies repeating, own ASCII name + TLV stats). Rides 0x02-rewrap. Stub titles it "UpdateModel" | 72-90B | `udp_2f_25_player_broadcasts`; `raw_channel_three_envelopes`; stub [`udp_s2c_03_2f`](packets/udp_s2c_03_2f.md) |

### 0x30–0x3f — reliable player metadata

| OP | dir | function | wire / size | source / stub |
|---|---|---|---|---|
| `0x30` | S→C | **ShortPlayerInfo** — compact player record (kmax-derived); the reply to `0x31` | varies | `s2c_30_shortplayer_kmax`; stub [`udp_s2c_03_30`](packets/udp_s2c_03_30.md) |
| `0x31` | C→S | **RequestShortPlayer** — client asks server for ShortPlayer info for entity N; `[player_id LE16][player_id LE16][00 00]` | 6B | `udp_new_opcodes_2026_05_29`; stub [`udp_c2s_03_31`](packets/udp_c2s_03_31.md) |
| `0x32` | both | **NPC dialogue / vehicle-transport state** — S→C 12B `[id LE32][state 3B][flag u8]` = generic vehicle/entity state (NOT subway-specific; 50× more in vehicle than subway). Raw 0x32 = NPC dialogue text/options | 12B (S→C state) | `udp_0d_33_32_decoded`; stub [`udp_s2c_03_32`](packets/udp_s2c_03_32.md) |
| `0x33` | S→C | **ChatList / ack-of-0x0d** — 6B `33 ff 00`; fires 50-200ms after each `0x0d`, paired 1:1. Stub titles it "ChatList" (catalog name); empirically the 0x0d ack | 6B | `udp_0d_33_32_decoded`; stub [`udp_s2c_03_33`](packets/udp_s2c_03_33.md) |
| `0x3c` | both | **State-poll handshake** — paired S→C/C→S every 30-40s. S→C `3c 04 00 [slot:1][server_u32 LE4][token LE4]`; C→S reply `3c 04 00 [slot][slot-fixed value LE4][uptime f32 LE4]`. Likely anti-cheat / skill verification (slot→value table pinned) | 12B both | `udp_3c_state_poll`; stubs [`udp_c2s_3c`](packets/udp_c2s_3c.md)/[`udp_s2c_3c`](packets/udp_s2c_3c.md) |
| `0x00`(C→S 0x03/0x00) | C→S | script-list upload (see 0x00–0x0f) | — | — |

> **Genuinely unknown reliable ops:** `0x29`, `0x2a` (predicted damage/drone-ping,
> `OPCODE_STRUCTURE.md §3`); `0x34`–`0x3b`, `0x3d`–`0x3f` (player-metadata not yet
> captured: implants, faction sympathy, soullight delta). No bytes invented for these.

---

## §4 — Cross-reference & open items

- **act_tags** (`0x03/0x1f` sub-tag space: 0x01 weapon-fire, 0x17 use-obj, 0x1a
  dialog, 0x1e item, 0x1f equip-holster, 0x25 state-ack, 0x26 vendor/loot, 0x27
  close-dialog, 0x2a mission-grant, 0x3d frame-poll, 0x4c keepalive …) → see
  [`SUBTAGS.md`](SUBTAGS.md), [`OPCODE_STRUCTURE.md §5`](OPCODE_STRUCTURE.md), and
  memory `correction_1f_is_event_class` / `c2s_03_1f_envelope_canonical`.
- **Inventory `0x00` 12-byte ops** (op enum 0x00–0x09) → [`OPCODE_STRUCTURE.md §6`](OPCODE_STRUCTURE.md).
- **TCP opcodes** (0x80/0x83/0x84/0x87/0xa0) → separate TCP reference / `OPCODE_STRUCTURE.md §1`.
- **Open / unknown (honest list):**
  - `0xf2` function (challenge vs reseed) — no paired response captured.
  - Transport `0x02` ConnectAccept wire shape — known by client dispatch case, not byte-decoded.
  - Single-sub-wrapper outers `0x0f`/`0x11`/`0x1d` — event class undetermined.
  - Reliable ops `0x29`/`0x2a`/`0x34`+ — predicted ranges, uncaptured.
  - Multipart discs `0x03`/`0x04`/`0x38` — logical type partial.
  - Sparse upper outers (`0x44`/`0x45`/`0x55`/`0xc4`/`0xef`/`0xf5`/`0xf6`/`0x3a`/`0x3e`) — unknown, 1-6 obs each.
```
