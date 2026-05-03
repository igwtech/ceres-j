# NC2 Protocol — Opcode Structure & Conventions

_Authoritative reference for how the protocol's opcode space is
organized. Auto-supporting tables under
`tools/opcode_analysis.py` and `tools/subtag_analysis.py`._

The opcode space is **not random** — it was designed by humans
who used recognizable patterns to segment subsystems, encode
direction, and pair requests with responses. This document
captures those rules so we can parse partial packets, distinguish
real headers from fragmented payloads, and predict gaps.

## High-level frame hierarchy

Every NC2 packet, whether on TCP or UDP, is one of these layers:

```
Wire bytes (encrypted on UDP, plaintext on TCP)
└─ Outer opcode (1B UDP / 2B TCP)
   ├─ "Endpoint" opcode (carries payload directly)
   └─ "Wrapper" opcode (carries a sub-packet stream or a
      reassembly stream)
        └─ Sub-opcode (1B more)
            ├─ "Endpoint" sub-opcode (carries payload)
            └─ "Wrapper" sub-opcode (e.g. 0x07 multipart, 0x1f
               GamePackets)
                └─ Tag byte (1B more)
                    └─ Sub-tag byte (1B more, optional)
```

The protocol is **6 layers deep at most**:
`outer → sub → multipart-disc → tag → sub-tag → payload`.

Each layer's opcode space is segmented by human convention.

---

## 1. TCP opcode space (16-bit big-endian)

Every TCP opcode is 2 bytes. **The high byte names the
subsystem; the low byte names the operation within it.**

### Subsystem map (high byte)

| High byte | Subsystem | Examples |
|---|---|---|
| `0x80` | 3-way connection handshake | `0x8000`/`0x8001`/`0x8003` |
| `0x83` | Game session state (server-driven) | `0x830c` Location, `0x830d` GameinfoReady, `0x8385` CharList |
| `0x84` | Account / character (client-driven) | `0x8480` Auth, `0x8482` GetCharList/Create |
| `0x87` | Gamedata / config sync | `0x8737`/`0x873a`, `0x873c` |
| `0xa0` | Session-ready handshake (post-auth) | `0xa001`/`0xa002`/`0xa003` |

### Direction encoding

The high byte alone tells direction in most cases:

| High byte | Typical direction | Why |
|---|---|---|
| `0x80` | mixed | 3-way handshake — both sides participate |
| `0x83` | almost always **S→C** | server-driven state push |
| `0x84` | always **C→S** | client-driven account/char ops |
| `0x87` | mixed (request/response pairs) | 0x8737 C→S, 0x873a S→C |
| `0xa0` | mixed (session-ready ack pair) | 0xa001 S→C, 0xa003 C→S |

### Request/response pairing rule

Client requests in `0x84xx` are answered by server packets in
`0x83xx`. The low byte differs:

| C→S Request | S→C Reply | Pattern |
|---|---|---|
| `0x8480` Auth | `0x8381` AuthAck | `0x84xx` request → `0x83xx` ack with low byte `0xxx` flipped to `0x80+xx` |
| `0x8482` GetCharList | `0x8385` CharList | (same family) |
| `0x8482` CharCreate | `0x8386` CreateAck | low byte `0x82` request → `0x86` reply |
| `0x8480` (phase 1) | `0x8383` InfoServer reply | `0x84xx` → `0x83xx` general rule |
| `0x8737` GetGamedata | `0x873a` Gamedata | same high-byte family, low byte +3 |
| `0x873c` GetUDPConnection | (no reply, no-op) | — |
| `0x8000` HandshakeB | `0x8001`+`0x8003` | handshake-internal |
| `0xa003` SessionReady-C | `0xa001` SessionReady-S | low byte `0x03` ↔ `0x01` |

### Predicted unobserved TCP opcodes

Based on the structure, here are likely opcodes we haven't yet
captured (for future gap analysis):

| Predicted | Likely role | When to expect |
|---|---|---|
| `0x8484` | character delete (mirrors 0x8482 create) | character-delete capture |
| `0x8387` | char-delete ack (mirrors 0x8386 create-ack) | character-delete capture |
| `0x83xx` (other low bytes 0x06-0x0b, 0x0e-0x16) | additional session state events | scenarios we haven't captured |
| `0x84xx` (other low bytes) | other client-driven account ops (clan, friends list?) | social UI captures |

---

## 2. UDP opcode space — outer

UDP outer opcodes are **single bytes**. The space is segmented
by 16-byte ranges:

### Range map

| Range | Subsystem | Confidence | Examples |
|---|---|---|---|
| `0x00` – `0x0f` | Connection / sync / wrappers | high | 0x01/0x04 handshake, 0x03 reliable, 0x07 multipart, 0x08 disconnect, 0x0b ping |
| `0x10` – `0x1f` | Entity & state stream | high | 0x1b 19B entity broadcast, 0x1f GamePackets |
| `0x20` – `0x2f` | World events | high | 0x20 Movement, 0x2a RequestPos, 0x2d trade-request, 0x32 NPC |
| `0x30` – `0x3f` | Player metadata / inventory | high | 0x32 NPC dialogue (raw), 0x3c inventory metadata |
| `0x40` – `0x6f` | Sparse — runtime / config | low | 0x44, 0x45, 0x55, 0x58, 0x6a (single-occurrence) |
| `0x70` – `0xff` | Cipher-handshake / rare echoes | very low | one-off opcodes seen during cipher reseed |

### Wrapper vs endpoint opcodes

| Opcode | Type | Notes |
|---|---|---|
| `0x03` | **wrapper** | reliable channel — always followed by sub-opcode |
| `0x07` | **wrapper** | multipart fragmentation — only valid as `0x03/0x07` |
| `0x13` | **wrapper** | gamedata batching (counter LE2 + key LE2 + sub stream) — present in legacy clients but in retail this layer is implicit (sub-packets sent directly under `0x03/...`) |
| `0x1b` | **endpoint** | 19B fixed-shape entity broadcast |
| `0x20` | **endpoint** | Movement |
| `0x2a` | **endpoint** | RequestPos |
| `0x32` | **endpoint** | NPC dialogue (raw, unreliable) |
| all others | varies | see per-packet docs |

### Channel duality — same opcode, two reliability levels

For most semantic opcodes, both a raw form and a `0x03`-wrapped
reliable form exist, **with the same logical meaning**. The
choice is reliability:

| Op | Raw form | Reliable form | Same logical content? |
|---|---|---|---|
| `0x1b` | entity broadcast (high volume, S→C) | `0x03/0x1b` PosUpdate (rarer, lower volume) | **yes** |
| `0x1f` | foreign-entity HP burst (rare) | `0x03/0x1f` GamePackets (high volume) | **yes** |
| `0x20` | Movement (most common) | (no reliable variant) | — |
| `0x27` | RequestWorldInfo | `0x03/0x27` (same name) | **yes** |
| `0x32` | NPC dialogue text/options | `0x03/0x32` engagement begin/end | **yes** |
| `0x33`, `0x09`, `0x0d` | rare raw observations | reliable variants exist | partial — same family |

This rule lets us **predict the meaning of unobserved sub-opcodes
in `0x03/0xNN`** when we know the raw `0xNN`, and vice versa.

### The "single-sub gamedata wrapper" hypothesis

A handful of opcodes (`0x0d`, `0x0f`, `0x11`, `0x1d`) appear in
S→C with a fixed 10-byte body of shape:

```
[opcode] 00 03 [LE2 seq] 1f 01 00 [tag] [tag2]
```

This is structurally identical to a `0x13` gamedata wrapper
carrying a single `0x1f` sub-packet. The opcode byte appears to
select which **type** of single-sub frame this is — analogous
to `0x1b` always being "single-sub entity update" with 19B body.

Hypothesis: `0x0d`, `0x0f`, `0x11`, `0x1d` are alternative
gamedata wrappers each tagged with a specific event class. They
appear sporadically and sample diversity is low (1-4 obs each),
so the exact event class for each isn't yet determined.

### Predicted unobserved UDP raw outers

| Predicted | Likely role |
|---|---|
| `0x05`, `0x06` (raw) | observed once each as single-byte echoes — likely cipher state markers |
| `0x09` (raw) | unobserved as endpoint; `0x03/0x09` exists |
| `0x21` – `0x2f` (gaps) | additional world events — quest acceptance, weather, faction broadcast |
| `0x37` – `0x3f` (gaps) | additional player metadata — implant slot updates, soullight delta |

---

## 3. UDP `0x03/?` reliable sub-opcode space

The `0x03` wrapper has the form:

```
0x03 [seq LE2] [sub-opcode 1B] [body...]
```

Sub-opcodes follow the SAME range map as raw outers:

| Range | Subsystem | Verified members |
|---|---|---|
| `0x00` – `0x0f` | connection-level reliable | `0x00` (script-list upload), `0x07` Multipart, `0x08` ZoningEnd, `0x09` (small ack), `0x0d` TimeSync |
| `0x10` – `0x1f` | reliable state stream | `0x1b` PosUpdate, `0x1f` GamePackets |
| `0x20` – `0x2f` | reliable world events | `0x22` zoning, `0x23` InfoResponse, `0x24` ?, `0x25` PlayerInfo, `0x26` RemoveWorldItem, `0x27` RequestWorldInfo, `0x28` WorldInfo, `0x2b` CityCom, `0x2c` StartPos, `0x2d` NPCData, `0x2e` Weather, `0x2f` UpdateModel |
| `0x30` – `0x3f` | reliable player metadata | `0x30` ShortPlayerInfo, `0x31` RequestShortPlayer, `0x32` (NPC dialog reliable), `0x33` ? |

### Predicted unobserved reliable sub-opcodes

| Predicted | Likely role |
|---|---|
| `0x29`, `0x2a` | additional world events — possibly damage events, drone-ping |
| `0x34` – `0x3e` | player-metadata events not yet captured (implants, faction sympathy delta, soullight, mission-log update) |

---

## 4. UDP `0x03/0x07/disc` multipart space

Multipart logical packets use a 1-byte discriminator after the
11-byte fragment header:

| disc | Logical type | Confidence | Where seen |
|---|---|---|---|
| `0x01` | CharInfo (login-time) | verified | every veteran-character login |
| `0x02` | CharsysInfo | verified | every veteran-character login |
| `0x03` | ? | partial | CREATION_LEVELING_LONG only |
| `0x04` | ? | partial | CREATION_LEVELING_LONG only |
| `0x38` | ? | partial | PLAZA_TO_PEPPER_CROSS_DISTRICT only |

Why a single byte? The `disc` field is an enum dispatching to a
specific client-side handler. The Ghidra-decompiled `FUN_0055c270`
shows branches for 0x01 and 0x02 only; higher disc values trigger
unidentified handlers.

### Predicted unobserved multipart discs

The fact that `0x03`, `0x04`, `0x38` all appear strongly suggests
the disc space is sparsely populated (not contiguous). Likely
candidates for future captures:

| Predicted | Likely role |
|---|---|
| `0x05`-`0x10` | character-state-blob updates we haven't captured |
| `0x20`-`0x40` | larger one-shot world-state blobs (apartment item list, CityCom DCB blob) |

---

## 5. `0x03/0x1f` GamePackets — tag space

`0x03/0x1f` is **the protocol's general-purpose state-event
channel** — by far the highest-traffic reliable sub-opcode (104K
observations across 13 captures). Its body is layered:

```
01 00 [tag] [tag2]? [data]
```

### Tag distribution (top 10 by frequency)

| Tag | Count | Dir | Size | Top tag2 | Role |
|---|---:|---|---|---|---|
| `0x3d` | 88,247 | C→S 99.9% | 7-11B | `0x11` (88,132) | **In-flight ack burst** — fires during fall + zone-load filler |
| `0x25` | 14,083 | S→C 98.5% | 4-202B | `0x23`, `0x13`, `0x22`, `0x14`, `0x1f` | **State-update transactions** (cash, pool burst, heartbeat) |
| `0x4c` | 600 | C→S 99% | 7-20B | `0x0f` (446), `0xff` (149) | **Combat-readiness heartbeat** (10s cadence) |
| `0x01` | 265 | C→S 98% | 19B fixed | `0x23`, `0x9e`, `0x22`, `0x0e`, `0xb2` | **NEW** — combat-related; correlates with COMBAT marker |
| `0x1a` | 189 | mixed | 4-22B | `0x01` (84), `0x00` (10) | **NEW** — mission/dialog state (heavy MISSION_COMPLETE correlation) |
| `0x2c` | 148 | mixed | 8-13B | `0x02`, `0x09`, `0x01` | ? |
| `0x1e` | 135 | C→S | 11B fixed | `0x03`, `0x04`, `0x05` | NPC vendor sell event |
| `0x17` | 81 | C→S | 7B fixed | `0x00` (34) | **Use object** — verified |
| `0x26` | 24 | S→C | 10-821B | `0x00`, `0x6f`, `0xc3` | **NEW** — vendor/loot listing (variable-size payload) |
| `0x2a` | 10 | S→C | 58-64B | `0x11`, `0x12`, `0x14`, `0x13` | **NEW** — mission-grant payload |

### Tag `0x25` — state-update sub-tag map

`0x25` is the most populated tag and routes by a sub-tag byte
(byte 3):

| sub-tag | Verified role |
|---|---|
| `0x04` | Skill-point spend (C→S) / cash update (S→C) — see [`level_up.md`](flows/level_up.md), [`vendor_buy.md`](flows/vendor_buy.md) |
| `0x13` | Transaction wrapper: `01 00 25 13 [txn LE2] [type] [data]` — used for cash and other transactional events |
| `0x14` | ? |
| `0x1f` | Pool-status burst (HP/PSI/STA, 14B) — same shape as raw `0x1f` outer; used by death event |
| `0x22` | World-info ack |
| `0x23` | Tick heartbeat — body `01 00 25 23 NN` where NN counts monotonically |
| `0x32` | Heartbeat variant |

### Predicted unobserved sub-tags inside `0x25`

| Predicted | Likely role |
|---|---|
| `0x05`-`0x12` | additional pool / stat update sub-tags |
| `0x15`-`0x1e` | reserved / unused |
| `0x20`, `0x21` | predict other "info" sub-tags |
| `0x24`-`0x31` | additional state events |

---

## 6. Inventory channel — `0x00` 12-byte ops

Inventory uses the raw `0x00` outer with a 12-byte fixed shape:

```
00 3c 01 00 [op 1B] [item/slot LE4] [trailer 3B]
```

The op byte (`offset 4`) is the action enum.

### Op byte map

| op | Verified action | Direction |
|---|---|---|
| `0x00` | loot-into-inventory (corpse pickup) | C→S |
| `0x01` | drag-from slot | both |
| `0x02` | drag-to slot | both |
| `0x03` | swap / move | both |
| `0x04` | use / consume | both |
| `0x05` | drop on ground / recycle | C→S |
| `0x06` | drop variant | C→S |
| `0x07` | take from container | both |
| `0x08` | put into container | both |
| `0x09` | equip / unequip | both |

The op space is dense `0x00` – `0x09` with no observed gaps,
suggesting the design intent was a tight enum. Higher op values
(>0x09) probably exist for stack-split, lock-item, etc.

### Predicted unobserved inventory ops

| Predicted | Likely role |
|---|---|
| `0x0a` | stack split (drag N of M) |
| `0x0b` | sort / auto-arrange |
| `0x0c` | container open |
| `0x0d` | container close |

---

## 7. Direction encoding rule (UDP)

UDP packets don't carry an explicit direction bit, but the
opcode space is **partitioned by direction within each subsystem**:

- Some opcodes are **C→S only** (e.g. `0x20` Movement is mostly
  C→S; the few S→C `0x20` are foreign-player position broadcasts
  with a different first-data byte).
- Some are **S→C only** (e.g. `0x1b` entity broadcast, `0x04`
  handshake reply).
- Most are **bidirectional** with the body shape distinguishing
  request vs reply (e.g. `0x0b` CPing — C→S 5B, S→C 9B).

The bidirectional pattern uses **size as the direction
discriminator**: the request is shorter (just the trigger bytes)
and the reply is longer (carries the response data).

---

## 8. Sub-tag co-occurrence with markers

Sub-tag analysis reveals which protocol elements correspond to
which game events. Cross-referencing the corpus:

| Marker | Uniquely fires sub-tag |
|---|---|
| `LEVELUP_SUBSKILL_R-C` | `0x25/0x04` skill spend |
| `OUTSIDE_AREAM5_KILLED_CRAWLER` | `0x03/0x26` RemoveWorldItem |
| `OUTSIDE_AREAM5_LOOT` | `0x1f bd 03 55` raw → `0x1f bd 03 56` reply |
| `BEFORE_JUMP` / `AFTER_JUMP` | `0x3d/0x11` in-flight burst |
| `CHAIR_SIT` | `0x20` Movement type=0x80 (seated bit) |
| `MEDBED_USE` | `0x17` use-object |
| `OUTSIDE_AREAM5_TALK_NPCLORCAN` | `0x2a` mission-grant 58-64B |
| `OUTSIDE_AREAM5_MISSION_COMPLETE` | `0x1a/0x01` (5 obs) — newly identified |
| `NPC_VENDOR_OPEN`/`SELL` | `0x26` listing + `0x1e` sell-action |
| `POISONED_DIED` | `0x1f` (raw outer 14B all-zero pool) + `0x03/0x23` GenRep trigger |

---

## 9. Implications for parsing partial packets

The structural rules let us recover from common parsing
ambiguities:

### Distinguishing UDP fragment from new packet

Every legitimate UDP datagram starts with a known wrapper byte
(`0x01`, `0x03`, `0x04`, `0x07`, `0x08`, `0x0b`, `0x0c`, `0x13`,
`0x1b`, `0x1f`, `0x20`, `0x2a`, `0x2d`, `0x32`, `0x3c`, …).
**If the first decrypted byte is none of these, the cipher state
is wrong** — likely a missed per-packet seed.

### Distinguishing reliable from unreliable

`0x03` is the ONLY reliable wrapper. Every reliable packet
begins `0x03 [seq LE2] [sub-opcode]`. If you see `0x03` outside
this position (e.g. as a sub-opcode), it's NOT a reliable
wrapper — it's a payload byte.

### Distinguishing multipart from single

Multipart fragments are ONLY emitted as `0x03/0x07`. If a
client implementation tries to send raw `0x07` outside this
wrapper, the server won't reassemble. Confirmed: the one
observed raw `0x07` (1012B in C→S NORMAN) is a single-fragment
payload that bypassed the standard reliable layer — likely the
script-list upload's raw form.

### Distinguishing endpoint from wrapper

Wrappers are: `0x03`, `0x07`, `0x13`, `0x1f` (when carrying
sub-tags), `0x25` (when sub-tagged within `0x1f`), `0x2b`
(CityCom DCB carrying RPC method), `0x32` (NPC carrying
options).

Everything else carries data directly.

---

## 10. Predicted opcode-space summary

Combining all the rules above, here's the predicted
opcode-space coverage:

| Layer | Range | Observed | Predicted-unobserved | Coverage |
|---|---|---|---|---|
| TCP high byte | `0x80`-`0xa0` | 5 (0x80, 0x83, 0x84, 0x87, 0xa0) | `0x82`, `0x85`, `0x86`, `0x88`-`0x9f` likely unused | tight |
| TCP low byte | `0x00`-`0xff` | sparse, ~15 used | many gaps for unobserved scenarios | sparse |
| UDP outer | `0x00`-`0xff` | dense `0x00`-`0x3f`, sparse upper | `0x40`+ likely sparse with cipher echoes | dense lower |
| `0x03/?` sub | `0x00`-`0x33` | dense | `0x29`, `0x2a`, `0x34`+ candidates | dense |
| Multipart disc | `0x01`-`0x38` | sparse (5 values) | enum with gaps | sparse |
| `0x03/0x1f` tag | `0x00`-`0x4c` | 29 distinct | many gaps for unobserved events | medium |
| Inventory op | `0x00`-`0x09` | dense (10 ops) | `0x0a`+ for stack-split, sort, etc. | dense |

## 11. How to use this document

When you find an unrecognized packet:

1. **Identify the layer** — outer? sub? tag?
2. **Look up the range** — what subsystem does the opcode
   number fall in?
3. **Predict the role** — based on the rules above + sample
   bytes
4. **Verify against captures** — search timelines for the
   opcode; correlate with markers

When designing a Ceres-J server-side handler:

1. **Honor the conventions** — if you're adding a new event,
   pick an opcode in the right range
2. **Reuse opcode numbers across reliability levels** — same
   number raw and reliable
3. **Don't pick `0x40+` for new events** — that range is
   reserved for future / cipher echoes

---

## 12. Generation tooling

This document is hand-curated but supported by:

- `tools/opcode_analysis.py` — generates raw-vs-reliable side-by-side table from the catalog
- `tools/subtag_analysis.py` — walks every `0x03/0x1f` body across captures to build the tag distribution
- `tools/catalog_extract.py --update-evidence` — refreshes per-packet evidence blocks

To validate predictions: capture the missing scenario, run the
pipeline, then check whether the new opcodes land in the
predicted range.
