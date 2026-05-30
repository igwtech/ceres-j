# Protocol RE — Methodology & Hypothesis Ledger

This directory is the source of truth for **what we don't yet
understand about the NC2 wire protocol, and what we plan to do
about it**.

## Why this exists

The auto-generated catalog (`docs/protocol/INDEX.md`) measures
*observability* — how often each packet type appears in the corpus.
It does NOT measure understanding. A packet labeled "verified" in
the catalog can still have most of its bytes opaque to us, and we
have been over-reporting decode confidence for months because we
conflated "we wrote a doc for it" with "we know every byte".

The strict byte-level annotator
(`tools/protocol_re/pcap_byte_annotator.py`) gives the real
measurement: **% of session bytes we can name with a registered
field decoder**. Baseline on `RETRY3 2026-05-24`: **7.2%**.
Everything else is `UNKNOWN[len]`.

This is the ground we have to make up.

## Methodology — five rules, no exceptions

### Rule 1 — Strict annotation, no silent skipping

Every byte gets a name or `UNKNOWN[len]`. The annotator enforces
this automatically. If you add a decoder that returns no span for
part of the packet, the annotator marks it UNKNOWN. You cannot
"silently skip" bytes by writing a vague comment.

### Rule 2 — No "junk / padding / anti-debug" labels without proof

NC2 has no known anti-cheat or anti-debug. Every byte the client
emits or accepts is meaningful to **some** code path. A field
being constant across all captures is NOT proof that it's padding
— it's proof that we haven't seen the case that mutates it yet.

Proof of "padding" =
  - Server-side experiment: mutate the field, observe the client
    behaves identically across N≥10 distinct samples, AND
  - Field is absent from any retail emitter we can identify in
    Ghidra/disassembly.

Until then, "constant" = `magic_const_<value>` field. Still named.
Still in the record. Just flagged as invariant in our corpus.

### Rule 3 — Minimum 2 distinct samples to claim a field decode

A pattern that holds in one capture is a **hypothesis**. Add a
row to the ledger, do not write a decoder. A decoder is for
verified facts only.

When the second capture confirms, promote hypothesis → decoder.
When the second capture refutes, mark the hypothesis row
**refuted** and write the refutation in the same row.

### Rule 4 — Differential captures over speculation

Hypothesis: "byte X at offset Y of packet Z is the current weapon
type."

Bad experiment: "let me grep the code for weapon-related stuff."

Good experiment:
  1. Capture session-A, holding pistol, do action X.
  2. Capture session-B, holding rifle, do action X identically.
  3. Diff: if byte X differs between A and B, AND stays stable
     within each session, the hypothesis is supported. Otherwise
     refuted.

### Rule 5 — Hypothesis ledger is the single source of truth

`hypotheses.md` in this directory is the canonical list. Every
unknown gets one row when we start working on it. Format:

```
| ID | Packet | Offset | Width | Hypothesis | Experiment | Status |
```

## Workflow

```
   Observe an UNKNOWN region in the annotator report
                       │
                       ▼
   Form a hypothesis (1 row in hypotheses.md)
                       │
                       ▼
   Design a differential capture (1 file in experiments/)
                       │
                       ▼
   Run capture, diff bytes
                       │
              ┌────────┴────────┐
              ▼                 ▼
        Refuted              Confirmed
              │                 │
              ▼                 ▼
       Update ledger     Write decoder
       (refuted)         (verified, ≥2 samples)
                                │
                                ▼
                  Promote ledger row to "decoded"
                                │
                                ▼
                  Re-run annotator — % named goes up
```

## Tools

- **`tools/protocol_re/pcap_byte_annotator.py`** — the strict
  annotator. Use it to measure progress.
- **`tools/protocol_re/decoders.py`** — decoder registry. Add a
  function here when promoting a hypothesis to verified.
- **`tools/pcap-decode.py`** — quick interactive decryption +
  hex dump for one-off inspection.
- **`tools/parse-burst.py`** — sub-packet framing helper, reused
  by the annotator.
- **`tools/capture-retail.sh`** — wrap a labeled capture session
  (writes a `.markers` file with timestamped action labels).
- **`tools/full_session_diff.py`** — pcap-A vs pcap-B byte-level
  diff (still being modernised — adapt for differential experiments).

## Top targets (post-baseline)

Sorted by bytes-still-unknown in RETRY3 (single 17-minute session):

| Rank | Opcode | Unknown bytes | Why prioritised |
|---|---|---:|---|
| 1 | UDP S→C `0x03/0x2d` (NPC tick) | 167K | NPCs & mobs not rendering on Ceres-J. 55B record claimed but field-level layout absent. |
| 2 | UDP C→S `0x20` (Movement) | 29K | Movement coords known (float32 LE per axis) but type-byte sub-shapes not fully decoded. |
| 3 | UDP S→C `0x1b` (movement broadcast) | 27K | Multiplayer ghosting / "double avatar" likely lives here. |
| 4 | UDP S→C `0x03/0x1f` (state-ack mux) | 12K | The act_tag enumeration is partial; most gameplay flows through this. |
| 5 | UDP S→C `0x03/0x07` multipart | 1.9K | CharInfo sections 1/3/5/6/7 still partial. |

Tackle #1 first (biggest ROI, single-channel, server-side
reproducible: just spawn one mob and watch).

## See also

- `docs/protocol/INDEX.md` — auto-generated catalog (volume, not
  understanding)
- `docs/protocol/OPCODE_STRUCTURE.md` — opcode-space rules
- `docs/protocol/RE_state_sync.md`,
  `docs/protocol/RE_tcp_confirm.md` — Ghidra-derived spec
  fragments
- `hypotheses.md` (next to this file) — current ledger
