# Protocol Findings — 2026-05-03 (party-capture pass)

**Captures used:**
- `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137.pcap` — Dra Moni (Party A)
- `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343.pcap` — Norman Gates (Party B), 26 markers

This pass cleared the **single largest set of remaining gaps**:
chat (5 channels), trade (full commit), PvP combat (with target ID),
group/buddy lifecycle, drug usage, implant install. We also
identified two new TCP opcodes.

## Chat — DECODED (5 channels)

### `0x03/0x1f` tag `0x1b` — LOCAL chat (proximity)

C→S body shape (variable-length):

```
01 00 1b [message ASCII null-terminated]
```

Sample: `1f 01 00 1b 68 65 79 00` = "hey" on local channel

### `0x03/0x1f` tag `0x3b` — CROSS-CHANNEL chat (whisper / team / clan / buddy)

C→S body shape:

```
[02 00 3b] [channel 1B] [target_uid LE4] [message ASCII null-terminated]
```

Channel byte routes:

| Channel | Marker | Meaning |
|---|---|---|
| `0x00` | BUDDY_CHAT | Buddy-list direct chat |
| `0x02` | CLAN_CHAT | Clan channel |
| `0x03` | TEAM_CHAT | Team / group |
| `0x04` | DIRECT_CHAT / WHISPER | One-on-one |

Verified bodies:

```
1f 02 00 3b 04 d2 86 01 00 hey               (whisper to Dra Moni 0x000186d2)
1f 02 00 3b 03 00 00 00 00 Hello team        (team — no recipient UID required)
1f 02 00 3b 00 00 00 00 00 Hey  buddy        (buddy)
1f 02 00 3b 02 00 00 00 00 hey clan          (clan)
```

### TCP `0x8317` — server chat reflection

When a client sends chat, the server reflects it to recipients
over **TCP** (the kept-alive game connection):

```
83 17 [sender_uid LE4] 01 00 [name_len 1B] [channel 1B] 00 [sender_name ASCII] [message ASCII]
```

Sample: `8317 78860100 01 00 0c 03 00 "Norman Gates" "Hello team"`
- sender_uid = `0x00018678`
- name_len = `0x0c` (12 chars)
- channel = `0x03` (team)
- sender_name = "Norman Gates"
- message = "Hello team"

Both `name_len` and `channel` field are byte fields with the same
channel enum as UDP `0x3b` tag.

This is the **first time we've seen game-event traffic on TCP**.
TCP is normally used for login + zoning + Location; chat being on
TCP is a bandwidth-saving design (reliable + ordered without UDP
ack overhead).

## PvP combat — `0x03/0x1f` tag `0x01/<sub>` family

PvP fire / heal / hit are all routed through tag `0x01` with a
sub-byte selecting the action. **All bodies are 19 bytes fixed.**

### Tag `0x01/0x0e` — weapon fire/aim (NEW format)

```
01 00 01 0e [07 02 00 00 00] [target_id LE2] [aim direction floats?]
```

Sample (FIRE_PVP):
```
01 00 01 0e 07 02 00 00 00 61 14 f7 d9 b8 0a 5e 72 67 45
                                  ^^^^^                  target_id LE2 = 0x1461
```

The bytes `f7 d9 b8 0a 5e 72 67 45` (8B) are likely a packed
position vector or aim direction — verifying the player is
actually pointing at the target, not just claiming to be.

### Tag `0x01/0x34` — heal action

Same 19B shape as 0x0e but the action sub-byte is `0x34`:

```
01 00 01 34 08 02 00 00 00 14 0a 39 b9 0a d9 94 69 45
                                  ^^^^^                  target_id (self?)
```

### Tag `0x01/0x23` — kill confirmation (from earlier capture)

Previously documented in `FINDINGS_2026-05-03.md`. Same shape.

### Tag `0x2c/0x01` and `0x2c/0x09` — hit/damage events

S→C bodies:
```
01 00 2c 09 02 00 00 00              (hit registered, generic)
01 00 2c 01 e5 24 68 45 02 00 00 00 14   (damage applied, with damage value)
```

The `0x2c` tag is the **server-side damage event**. It fires once
per hit confirmation, separate from the player's fire packet.
This matches the natural design: client emits "I shot at X"
(`0x01/0x0e`), server validates + emits "X took N damage"
(`0x2c/0x01`).

## Trade — DECODED (full commit flow)

### Trade open: tag `0x17/0x02`

C→S (TRADE_OPEN marker):
```
01 00 17 02 00 00 00     (7B)
```

This is the existing `0x17` use-object packet with a `0x02`
sub-byte indicating "open trade window" instead of "use door /
chair / etc." (which uses `0x17/0x00` or `0x17/0x?`). The
trade-target identity is implied by the player's current
look-at target.

### Trade confirm: tag `0x25/0x12` + tag `0x37/0x02`

Both client and server emit the SAME pair when the trade is
confirmed:

```
C->S  02 00 25 12 00 00 00 00     (8B; tag 0x25 sub 0x12 = "ready")
C->S  02 00 37 02 00 00 00 01     (8B; tag 0x37 sub 0x02 with state byte)

S->C  01 00 25 12 00 00 00 00     (echo)
S->C  01 00 37 02 00 00 00 01     (echo)
```

Tag `0x37` is **NEW** — appears to be the trade-window state
machine. The trailing `01` byte is likely the lock/confirm flag.

### Trade cash commit: `0x03/0x23 0x12` + `0x3c/0x02`

When trade closes with cash exchanged:

| Packet | Body | Meaning |
|---|---|---|
| `S→C 0x03/0x23` 8B | `12 00 07 00 00 00 00 00` | NEW `0x23` variant — trade-complete event |
| `S→C 0x3c` 12B | `3c 02 00 09 96 6c 00 00 50 2b 00 00` | Cash transfer record (two LE32 amounts: 0x6c96 = 27798, 0x2b50 = 11088) |
| `S→C TCP 0xa002` | (2B) | Session-level trade ack |

The `0x3c/0x02` 12B is a NEW variant of the inventory-metadata
channel — same outer as `0x3c 01 ...` (inventory listing) but
sub-byte `0x02` tags it as a **money transfer** with two amounts.

## Group / buddy / team — DECODED

### Buddy add: tag `0x33` (request) + tag `0x32` (response)

C→S (ADD_BUDDY marker, sending name to server):
```
02 00 33 02 [name ASCII null-terminated]
```

Sample: `02 00 33 02 44 72 61 20 4d 6f 6e 69 00` = "Dra Moni\0"

S→C reply with the resolved UID:
```
02 00 32 02 [target_uid LE4]
```

Sample: `02 00 32 02 d2 86 01 00` = UID `0x000186d2` (Dra Moni)

So the server **resolves a name string to a UID** for buddy/group
operations. Names are case-sensitive ASCII.

### Team invite + accept

INVITE_TEAM marker fires on `0x25/0x13` transaction wrapper plus
TCP `0x838f` interaction marker. ACCEPT_INVITE_TEAM emits:

```
S->C  02 00 25 13 05 87 0e 02
```

Tag `0x25/0x13/05/87` is the team-state delta. The `87 0e 02` tail
encodes the team-member info (likely target_uid + role byte).

### Team leave

LEAVE_TEAM emits primarily `0x00 ?` 12B inventory channel
packets (`00 3c 01 00 03 7a 1b 00 00 40 48 00`) — likely the
team-roster being modified using the same channel as inventory.
Plus `0x3c 02 00 09 ...` (similar to trade — money/state record).

## Implant install (poke) — partial

POKE_START fires multiple times in this capture (player tried,
realized they needed disinfectant gel, bought gel, then completed):

POKE_AFTER_GEL marker (t=486s) shows:
- S→C tag `0x25/0x15` — NEW sub-tag of `0x25` (count=2 in the
  ±5s window). Body: `02 00 25 15 06 92 00 00 00 00`
- C→S tag `0x20/0x3f` — unusual. Body:
  `01 00 20 3f 00 2f 55 85 c4 9a f9 7f c4 1d 82 3e 44 e0 f2 01 c1 a5 64 2d`

The `0x20/0x3f` C→S packet looks like a position update with
extra fixed-point data — possibly the player being **animation-locked
during the implant install** and the server reading their position
+ orientation precisely.

`0x25/0x15` is likely the implant-installed delta event (similar
shape to skill-spend `0x25/0x04`).

## Drug usage — already in `0x25/0x13` transaction wrapper

TAKE_DRUG_PARTY_B emits:
```
S->C  01 00 25 13 3a 12 0e 02
```

Decoded:
- `25 13` = transaction wrapper
- `3a 12` = drug-effect sub-tag (0x3a) + drug ID (0x12)
- `0e 02` = effect duration / strength?

So drug-effect events use the same `0x25/0x13` transactional
wrapper as cash, but with their own inner sub-tag.

## New TCP opcodes

The PARTY_A capture exposed two new TCP opcodes:

### TCP S→C `0x8388` (18-23B variable, 24 obs in 2 captures)

Body sample: `83 88 d2 86 01 00 41 00 00 00 04 00 00 00 d2 86 01 00`

Looks like a player-info / session-control packet. Bytes 2-5 are
a player_uid LE4 (`d2 86 01 00`), then status fields.

### TCP S→C `0x8318` (7B, 2 obs)

Body: `83 18 01 a5 84 01 00`
- bytes 2-5: another LE4 ID
- byte 6: status

Both opcodes follow the **0x83xx subsystem** (game session
state, server-driven) consistent with the structure rules in
`OPCODE_STRUCTURE.md`.

## Updated coverage

The party captures pushed the catalog to **15 captures, 100
unique packet types, 411,296 packets**. The protocol decode
coverage on the long capture went from 75.2% FULL → estimated
**~83% FULL** with these new tag identifications.

The biggest remaining gaps are:
- `0x03/0x2d NPCData` 54B internal byte format (fully understood
  at the wrapper level, partial inside)
- `0x1b` 19B foreign-entity broadcast (similar — wrapper
  understood, position floats partial)
- `0x03/0x07/0x03` and `0x03/0x07/0x04` multipart discriminators
  (1 obs each, content unknown)

## Updated tag map for `0x03/0x1f`

| Tag | Sub | Role | Verified by |
|---|---|---|---|
| `0x01` | `0x0e` | **Weapon fire/aim** (target_id + aim floats) | FIRE_PVP marker |
| `0x01` | `0x23` | **Combat targeting / kill confirm** | LOOT marker (CREATION_LEVELING_LONG) |
| `0x01` | `0x34` | **Heal action** | HEAL_PVP marker |
| `0x17` | `0x00` | Use object (interact key) | MEDBED_USE etc. |
| `0x17` | `0x02` | Use object → trade window | TRADE_OPEN marker |
| `0x1a` | * | Dialogue navigation | NPC_TALK markers |
| `0x1b` | * | **LOCAL chat** | LOCAL_CHAT marker |
| `0x1e` | * | Inventory item action | EQUIPING markers |
| `0x25` | `0x04` | Skill spend / cash | LEVELUP, kill rewards |
| `0x25` | `0x12` | **Trade confirm step** | TRADE_CONFIRM |
| `0x25` | `0x13` | Transaction wrapper (cash, drugs, etc.) | many |
| `0x25` | `0x15` | **Implant install ack** | POKE_AFTER_GEL |
| `0x25` | `0x1f` | Pool-status burst (HP/PSI/STA) | death, fall damage |
| `0x25` | `0x23` | Tick heartbeat | every capture |
| `0x26` | * | Vendor / loot listing | NPC_VENDOR_OPEN |
| `0x2a` | * | Mission grant (ASCII mission ID) | mission markers |
| `0x2c` | `0x01` | **PvP/PvE damage applied** (with damage value) | FIRE_PVP |
| `0x2c` | `0x09` | **Hit registered** (generic) | FIRE_PVP, HEAL_PVP |
| `0x32` | `0x02` | **Buddy/group resolve UID reply** | ADD_BUDDY |
| `0x33` | `0x02` | **Buddy/group add request** (name string) | ADD_BUDDY |
| `0x37` | `0x02` | **Trade state action** | TRADE_CONFIRM |
| `0x3b` | * | **Cross-channel chat** (whisper/team/clan/buddy) | DIRECT/TEAM/CLAN/BUDDY_CHAT |
| `0x3d` | `0x11` | In-flight ack burst | falls / loading |
| `0x4c` | `0x0f` | Combat-readiness heartbeat | combat captures |

## Implications for Ceres-J

We now have enough decoded protocol to implement:

- **Full chat system** (5 channels)
- **Trade window** (open → confirm → cash commit)
- **PvP combat** (fire / hit / heal / damage application)
- **Group / buddy / team lifecycle**

The remaining gaps for full protocol implementation are:
- Mob behavior internals (`0x03/0x2d` 54B body bytes 5-53)
- Entity-broadcast position floats (`0x1b` 19B bytes 6-13)
- The Lua `SendScriptMsg` string→tag dispatch (run
  `tools/ghidra/FindSendScriptMsg.java`)

## Memory updates

The new tag findings should be reflected in:
- `OPCODE_STRUCTURE.md` — add `0x3b`, `0x37`, `0x33`, `0x32` tags
- `flows/` — add new flow docs for chat, trade-commit, PvP, group
- Memory: update `project_opcode_structure.md` with the new tag map
