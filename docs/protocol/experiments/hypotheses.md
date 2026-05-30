# Hypothesis Ledger

The canonical list of unknown protocol regions and our hypotheses
about them. Add a row when you start work; do NOT remove rows
when refuted (the refutation is the result).

See [`README.md`](README.md) for the workflow.

## Active

| ID | Packet | Offset | Width | Hypothesis | Experiment | Status |
|---|---|---|---|---|---|---|
| HYP-001 | UDP S→C `0x03/0x2d` | 0..54 | 55B | Catalog claims fixed 55B record. Sub-action byte at offset 0 selects layout. | Spawn one mob, log every 2d emission, diff while mob walks/attacks/dies. Find which bytes change with which observable game-state. | open |
| HYP-002 | UDP S→C `0x1b` | 5..end | var | After 5B header, trailing bytes are position+orientation+velocity. Expected float32 LE per axis. | Capture stationary player, then walking, then jumping; diff packets for each. Bytes that move only when player moves = position; bytes that move only when player turns = orientation. | open |
| HYP-003 | UDP S→C `0x03/0x33` | 0..1 | 2B `u16le` | Either an enum (small distinct values across captures) or a counter (monotonic within session). | Sort all `0x33` packets in 5 captures by timestamp, print the LE16 sequence. Monotonic → counter. Tiny value set → enum. | open |
| HYP-004 | UDP S→C `0x03/0x2e` | 10..12 | 3B | After two LE32 weather timers, trailing 3 bytes — possibly a third partial timer, or weather-zone byte + LE16. | Capture two captures with different weather (sunny vs raining if controllable), diff bytes 10..12. | open |
| HYP-005 | UDP S→C `0x03/0x1f` | 3..end | var | `act_tag` byte at offset 3 selects sub-shape. Enumerate the unseen tags. | Walk all `0x03/0x1f` in RETRY3 + 16 other captures, group by byte[3], print one hex sample per group. Catalog every unique tag seen. | open |
| HYP-006 | UDP C→S `0x20` | 3..end | var | After 3B header, `type` byte selects the rest. Known types: 0x00 (no axes), 0x03 (full position update). | Catalog all `type` values seen across the corpus + their distinct lengths. | open |
| HYP-007 | UDP S→C `0x03/0x07` multipart disc 0x0a..0x0f, 0x38 | — | — | These are CharInfo follow-ups for non-self players (party members, peers in zone). | Capture in a populated zone (Plaza P1 daytime), force a CharInfo refresh on a peer, look for non-disc-1 chains. | open |
| HYP-008 | UDP `0x55`, `0xc4`, `0xc6`, `0x89`, `0xcf`, `0xef` | — | — | Single-sample exotics — NOT junk per user feedback 2026-05-24. Each is a valid opcode with a function we haven't triggered. | Bisect by replaying captures where each appears, label the wire moment with what was happening on screen. | open |
| HYP-009 | UDP S→C `0x03/0x2d` sub-action 0x12+ | — | — | The sub-action byte selects different NPC update shapes. We've decoded 0x00..0x11; everything ≥0x12 is unknown. | Walk all 2d packets, group by sub-action, count + hex-dump one sample of each unknown sub-action. | open |
| HYP-010 | TCP S→C `0x8383` / `0x8388` / `0x8386` / `0x8318` | — | — | Player-info pushes; bodies look like (LE32 player_id, ASCII name, status bytes). | Login two characters from different accounts, capture both. Cross-reference name + id pairs in TCP payload. | open |

## Refuted

(none yet — populate as experiments come back negative)

## Decoded (promoted to `tools/protocol_re/decoders.py`)

| ID | Packet | Decoded fields | Sample count |
|---|---|---|---|
| DEC-001 | TCP S→C `0x83/0x0c` Location | zone_id, reserved_zero, spawn_idx, world_path | 75+ across 17 captures |
| DEC-002 | TCP S→C `0x83/0x0d` LoadingBegin | fixed 4B | 54 across 16 captures |
| DEC-003 | TCP S→C `0x83/0x8f` keepalive | fixed 7B | 1,392 across 17 captures |
| DEC-004 | TCP S→C `0xa0/0x01` SessionReady-S | 2B and 10B variants pinned | 34 across 11 captures |
| DEC-005 | TCP S→C `0xa0/0x02` InteractionAck | 2B and 10B variants pinned | 224 across 10 captures |
| DEC-006 | UDP C→S `0x03/0x08` ReliableAck | ack_seq_minus_1 LE16 | 54 across 11 captures |
| DEC-007 | UDP C→S `0x03/0x27` RequestWorldInfo | entity_id LE32 | 2,942 across 17 captures |
| DEC-008 | UDP S→C `0x03/0x26` RemoveWorldItem | removed_entity_id LE32 | 253 across 10 captures |
| DEC-009 | UDP `0x0b` CPing | ping_payload LE32 | 4,939 S→C + 4,868 C→S |
| DEC-010 | UDP `0x20` Movement (header only) | opcode, mov_local_id, mov_type | 229k samples |
| DEC-011 | UDP S→C `0x1b` movement bcast (header) | flags, map_id_or_le16, local_id | 143k samples |

## Process — adding a hypothesis row

1. Spot an UNKNOWN region in the annotator output.
2. Form a falsifiable hypothesis (NOT "this might be a timer" —
   "this is a monotonic-increasing LE32 timer in milliseconds
   since server boot").
3. Add a row to the **Active** table with:
   - **ID**: next sequential `HYP-NNN`.
   - **Packet**: full opcode/sub-tag.
   - **Offset/Width**: byte range your hypothesis targets.
   - **Hypothesis**: one sentence, falsifiable.
   - **Experiment**: one sentence, concrete capture/diff plan.
   - **Status**: `open` → `running` → `confirmed` | `refuted`.

## Process — confirming a hypothesis (promoting to decoder)

1. Run the experiment; collect ≥2 distinct retail captures.
2. Diff matches the hypothesis prediction in both.
3. Write a decoder function in
   `tools/protocol_re/decoders.py`, register it.
4. Re-run the annotator; verify `% named` goes up by the
   expected amount.
5. Move the row from **Active** to **Decoded**.
6. Update the relevant per-packet doc in `docs/protocol/packets/`.
