# Flow: Character creation

**Status:** verified  
**Backing capture:** `RETAIL_CREATION_LEVELING_LONG_20260502_160841`
— markers `AT_CHARSELECT_BEFORE_CREATE` (t=24.23s),
`CREATING` (t=72.88s),
`CREATED_BEFORE_LOGIN_NEWCHAR` (t=109.29s).

## Scenario

Player at the character-select screen clicks "Create New
Character", goes through the character-builder UI (class, faction,
appearance, name, stat points), submits, and the new character
appears in the char list.

## TCP opcode reuse — `0x8482`

Character creation **reuses the `0x8482` opcode** that's used for
`GetCharList`. The server distinguishes by the request body:

| Body length | Purpose | Body sample |
|---:|---|---|
| 30 B | GetCharList (read) | `8482 [token LE2] 0100 0100 001e 00 [account-id 4B] [scope LE4]` |
| 39 B | Char-create preview | `8482 [token LE2] 0100 0509 0012 00 [account-id 4B] [partial char data]` |
| 110 B | Char-create commit | `8482 [token LE2] 0100 0751 0012 00 [account-id 4B] [full char data]` |

Bytes 6-7 of the body are the discriminator: `01 00` for read-only
`GetCharList`, `05 09` for preview/probe, `07 51` for commit.

The server replies with **`0x8386`** (a new TCP opcode not seen in
prior captures): 7 bytes `8386 01 00 00 00 [status]`. Status `0x3d`
on preview, `0x00` on commit-success.

## Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant G as GameServer (TCP :12000)

    Note over C,G: Already at character-select with empty slot.
    Note over C,G: User clicks "Create New Character".

    C->>G: 0x8482 39B (preview, body bytes 6-7 = "05 09")
    G->>C: 0x8386 7B "8386 01 00 00 00 3d" (preview ack)

    Note over C,G: User fills class/faction/appearance/name/stats.
    Note over C,G: User clicks final "Create".

    C->>G: 0x8482 110B (commit, body bytes 6-7 = "07 51")
    Note right of C: Body carries the full character-config blob.
    G->>C: 0x8386 7B "8386 01 00 00 00 00" (commit ack)
    C->>G: 0x8482 30B (refresh charlist)
    G->>C: 0x8385 CharList 220B (now includes the new character)

    Note over C,G: Char-select UI refreshes; new slot is populated.
```

## Annotated walkthrough — t=24.23s..91.24s

| t (s) | Δ s | Dir | Packet | Sz | Body |
|---:|---:|---|---|---:|---|
| **24.23** | — | — | — | — | **▸ AT_CHARSELECT_BEFORE_CREATE marker** |
| 40.04 | 15.8 | C→S | `0x8482` | 39 | preview body, `0509` discriminator |
| 40.24 | 0.2 | S→C | `0x8386` | 7 | preview ack `…3d` |
| **72.88** | — | — | — | — | **▸ CREATING marker** (user clicked Create) |
| 90.73 | 17.85 | C→S | `0x8482` | 110 | **commit body, `0751` discriminator** |
| 90.93 | 0.2 | S→C | `0x8386` | 7 | commit ack `…00` |
| 90.97 | 0.04 | C→S | `0x8482` | 30 | regular GetCharList refresh |
| 91.24 | 0.27 | S→C | `0x8385` | 220 | CharList now lists 5 characters |
| **109.29** | — | — | — | — | **▸ CREATED_BEFORE_LOGIN_NEWCHAR marker** |

After this, the user logs in to the new character with the
standard [`login.md`](login.md) phase 3.

## Body format (preliminary)

The 110B commit body is the only place the full character-config
blob lives. Differential analysis between two captures of
character creation (different name/class/faction) would isolate:

- name string offset (variable-length, null-terminated)
- class enum byte
- faction enum byte
- gender / appearance bytes
- stat allocation array (likely 4 bytes for INT/DEX/STR/CON or
  similar)
- starting subskill array (referenced in the 39B preview already)

The capture only has ONE creation, so byte-level decoding requires
another retail capture with deliberate variation.

## Open questions

- **Is the 39B preview required?** It fires when entering the
  creation UI. Possibly carries the player's stat-allocation
  attempt for server-side validation; if the server rejects (e.g.
  invalid stat distribution), it would presumably reply with a
  different `0x8386` status byte. We've only seen one path.
- **`0x8386` status byte semantics.** `0x3d` (preview) vs `0x00`
  (commit-success). Other values (rejection, name-taken) not yet
  observed.
- **The `0x8482` discriminator field.** Is it a sequence /
  request-id, or a real op-code? `01 00` (read), `05 09` (preview),
  `07 51` (commit) — pattern unclear. Could be size-derived
  (32+7=39, 102+8=110) or a true protocol op.

## Backing evidence

Timeline:
[`_data/timelines/nc2_strace_RETAIL_CREATION_LEVELING_LONG_20260502_160841.md`](../_data/timelines/nc2_strace_RETAIL_CREATION_LEVELING_LONG_20260502_160841.md)
lines 32-41.
