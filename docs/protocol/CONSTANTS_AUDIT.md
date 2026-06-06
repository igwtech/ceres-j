# Constants Audit — re-classifying every documented "CONSTANT" field

_2026-06-06. Triage of all `CONSTANT`/padding/reserved field claims across
`packets/` (101 docs, 249 candidate lines). Driven by the first-principles
critique: **a wire constant is only legitimate if it's a tag/opcode/discriminator,
compiler padding, a reserved/version field, or a fixed serialization-format
constant. Everything else flagged "constant" is an undersampled variable** — the
17-pcap corpus is biased (few characters, one client build, mostly Plaza/Pepper),
so any field keyed off account / char / zone / state looks frozen._

See [[feedback_constants_are_suspect]]. NC2 is a struct-dump protocol (C/C++
structs `memcpy`'d under the `0x13` wrapper), so real dead bytes (padding,
reserved) genuinely exist — but each must be *justified*, not assumed.

## Classification key

- **S — Structural.** Opcode / sub-opcode / sub-tag / variant discriminator /
  length-prefix / framing flag. Exempt by definition. Safe to emit as a literal.
- **A — Safe-to-freeze.** Compiler alignment padding, reserved/unused fields,
  version/magic handshake bytes, fixed record-stride/size fields. Real dead or
  fixed bytes. Keep frozen, but tag with the *reason* (and ideally the Ghidra
  struct offset) so the justification is auditable.
- **B — Unverified-invariant (SUSPECT).** Looks constant only because the corpus
  never varied it. **Ceres-J must echo the client's value / copy from the source
  record — NEVER hardcode.** Hardcoding here is an invent-bytes violation and a
  latent bug (see the `0x03/0x28` precedent below).
- **V — Confirmed variable.** Already observed to vary (or proven a bug). Fix now.

---

## V — Confirmed variable (fix now / already fixed)

| Field | Evidence it varies | Status |
|---|---|---|
| `S→C 0x03/0x28[7..10]` | per-NPC handle (`6a519a37`,`93efed78`,…); Ceres hardcoded `8958887` for every NPC | **BUG, fixed** — the canonical precedent for this whole audit |
| `S→C 0x03/0x3c[1]` | "CONSTANT 0x01 (42/43); **0x02 in DRSTONE**" | state/variant byte — must derive |
| `C→S 0x03/0x3c[1]` | "CONSTANT 0x01 (44/45); **0x02 in** …" | same — must derive |
| `S→C 0x8385[2..3]` session_byte | already corrected: 17 distinct values, was over-generalized to `fe 02` | model fix already applied — use as the template |

---

## B — Unverified-invariant (SUSPECT), ranked by risk

Priority = (likelihood it's really variable) × (blast radius if Ceres freezes it).

| # | Field | Pinned as | Why suspect | Ceres-J action |
|---|---|---|---|---|
| ~~1~~ | `S→C 0x8385[4..5]` char_count | `0x0004` | **RESOLVED 2026-06-06 → A.** msn2/3/4wolf all send `04 00`; it's the slot-array size (max 4), real variation is slot occupancy via CHARDUMMY (4/3/2 populated). Freezing `0x0004` is correct. | derive slot count, but value is invariant 4 |
| ~~2~~ | `S→C 0x8385[25..29]` | `01 01 01 01 01` | **RESOLVED 2026-06-06 → A (low residual).** invariant across 9 chars / 3 accounts / varied class+location → real default-on template. Residual: locked/banned char untested. | safe to freeze |
| ~~3~~ | `S→C 0x8385[37..40]` | LE32 `0x00000000` | **RESOLVED 2026-06-06.** `00 00 00 00` for all 9 chars regardless of char → **soullight hypothesis killed** (not carried here). Stays invariant-unknown. | leave as-is; not soullight |
| ~~3b~~ | `S→C 0x8385[30..33]` profession | LE32 `0` | **RESOLVED 2026-06-06 → live field (not a constant).** Ceres-J chars emit it live: Asd=10, John Connor=20 (class 5 → cls 2.1), Krafteo*=10 — proven via bot decode. The 9 retail chars all read 0 only because those chars have profession 0 (or retail zeroes profession at the lobby). Field position = profession, confirmed. | — (live field) |
| 4 | `S→C 0x8305[16..19]` flags | LE32 `0x00890000` | flags/version field — single build, single corpus | treat as version/flags const **only** if Ghidra confirms it's never read; else echo |
| 5 | `S→C 0x03/0x2c[2]` | `01` (in `2c 01 01`) | byte `[1]` is the variant discriminator (0x01 PosUpdate / 0x02 CharInfo); `[2]` pinned from the corpus's dominant variant | re-check across both variants |
| 6 | `S→C 0x03/0x25[7..13]` | `08 09 6c 02 40 40 c4` | 7 nonzero bytes pinned as a block — reads like packed char-state/stat data, not padding | decode; do not freeze |
| 7 | `S→C 0x03/0x25[18..21]`,`[28..31]` | `ff ff ff ff` | doc says "placeholder for inv" — classic never-triggered-the-populated-state signature | populate from inventory state |
| 8 | `S→C 0x03/0x25[32]` | `0x0f` | lone nonzero enum pinned constant | decode |
| 9 | `S→C 0x03/0x33[0..1]` | `ff 00` | guessed "all-channels bitmap / count=0" — never saw a non-empty channel set | decode against a multi-channel capture |
| 10 | `S→C 0x03/0x2e[1]` | `0x01` (63/63) | single-corpus variant/flag; 63 samples all one capture-type | re-sample across scenarios |
| 11 | `S→C 0x1f[1..2]` / `C→S 0x03/0x1f[1..2]` & `[4..5]` | `0x0001` LE16 | "envelope prefix" — likely a count or version that's always 1 in routine traffic | echo, don't assume 1 |
| 12 | `S→C 0x03/0x1b[40..46]` | `16 03 05 87 03` trailer + `[10] 0xff` | nonzero structured trailer pinned constant (the humanoid-broadcast tail) | decode; suspected timestamp/seq |
| 13 | `S→C 0x8318[2]` | `0x01` (event-type/status?) | status enum from **2** samples | decode |
| 14 | `C→S 0x03/0x1f[3]` | `0x55` ('U') | doc itself flags "suspicious-constant" | trace in client |
| 15 | `C→S 0x2d[5]` | `0x3f` ('?') | ASCII-looking constant across 339 obs of one session-type | decode |
| 16 | `C→S 0x0c` trailer | `d5 0a 20 00` | structured nonzero trailer pinned constant | decode |
| 17 | `C→S 0x8482[19..22]` | `1f 00 00 00` | "marker" — likely `0x1f` sub-tag + pad, but pinned whole | split tag vs pad |
| 18 | `C→S 0x55[3..4]` | `00 00` | inside the anti-cheat session signature; doc: "NOT session-derived… some CONSTANT signal" | leave opaque; echo |
| 19 | `S→C 0x8385[35..36]` class_high/low | `MISC_CLASS/2`, `MISC_CLASS%2` | **(actually derived, OK)** — listed only to note it's a *computed* field, not a constant | already correct |

---

## A — Safe-to-freeze (padding / reserved / version-magic / record-size)

Justified dead-or-fixed bytes. Keep, but each should carry its reason tag.

**Compiler/struct padding & reserved (zero) fields:**
`C→S 0x873c[2..5]`, `C→S 0x8737[2..5]`, `S→C 0x838f[2..6]`, `S→C 0x830d[2..3]`,
`S→C 0x830c[10..13]`, `S→C 0x03/0x28[5..6]`, `S→C 0x03/0x30[2..3]`,
`S→C 0x8381[6..9]`, `C→S 0x8301[2..5]`,`[6..9]` (the ex-"client_port"),
`C→S 0x32[3..5]`, `C→S 0x2d[2..4]`, `C→S 0x07[1..2]`,`[11]`,
`S→C 0x03/0x2c[19..28]` (10B), `S→C 0x03/0x1b[18..27]` (10B),`[36..39]` (4B),
`S→C 0x1b[3..4]`,`[15..16]`, `C→S 0x03/0x00[N+1..239]` (zero tail),
`S→C 0x20[24..28]` (5B trailer), `C→S 0x03/0x31` (trailing 2B),
`C→S 0x03/0x08` & `S→C 0x03/0x09` (`[ack_seq][00 00]` handshake-era pad),
`S→C 0x8385[34]` reserved, `S→C 0x03/0x25` reserved `[15]`,`[17]`,`[38..43]`.

**Version / magic handshake constants (real, hardcoded in client):**
`S→C 0x8001[2]=0x66 ('f')`, `S→C 0x8003[2]=0x68 ('h')`, `C→S 0x8000[2]=0x78 ('x')`
— the f/x/h protocol-version handshake triplet.

**Fixed record-size / framing:**
`S→C 0x8385[6..7]` struct_size `0x0029` (=41, the char-struct stride — legit;
derive from `sizeof`), `S→C 0x03/0x07` chain_key `0x00` (multipart framing),
`S→C 0x03/0x2d` trailing `0x06` & `S→C 0x2d[5]` `0x0a` (block-length markers).

> Note: a few lone unknown `0x00` bytes (`0x03/0x2e[3]`, `0x1f[3]`, `0x2d[1]`,`[4]`,
> `0x1d[1]`, `0x27[3..4]`) are filed under A as presumed inter-field pad, but they
> are **weakly** justified — promote to B if a Ghidra struct shows a named field
> there.

---

## S — Structural (exempt)

All `[0]`/`[0..1]` opcode and sub-opcode bytes, sub-tags (`0x0f[8]`, `0x07[8]`,
`0x0d[8]`, `C→S 0x05[2]=0x1f`), and variant-discriminator markers
(`0x03/0x1b[5]` 0x20/0x09, `0x03/0x2c[1]` 0x01/0x02). These are how the wire is
parsed — emitting them as literals is correct.

---

## Tooling — `tools/constants_audit.py` (LANDED 2026-06-06)

The recommendation below is now implemented and CI-enforced:

- **Source of truth:** [`_data/constants.json`](_data/constants.json) — one entry
  per constant field, keyed by `(packet_doc, normalized line)`, tagged with a
  `kind`: `discriminator | padding | reserved | magic | recordsize | verified |
  UNVERIFIED`. Current census: 142 classified, **31 UNVERIFIED**.
- `python3 tools/constants_audit.py --scan` — merge newly-added constant lines
  into the manifest (auto-kind for new ones; never clobbers a manual kind).
- `--inject` — (re)writes a kind-tagged `## Constants (catalog-audit)` table into
  each packet doc between `<!-- constants-audit -->` markers (the inline tags).
- `--check` — CI gate (`.github/workflows/constants-audit.yml`). **Fails** if any
  constant line in a packet doc is unclassified or carries an invalid kind;
  **warns** when an `UNVERIFIED` field's value appears hardcoded in its Ceres-J
  `server_tcp` emitter (e.g. it currently flags `CharList.java` freezing the
  `0x8385[25..29]` `01 01 01 01 01` block — an open suspect, not yet a literal we
  trust). This is the regression guard against the `0x03/0x28` handle-bug class.

### Original recommendation (now satisfied)

1. Add a machine-readable `kind:` tag to every constant line in the per-packet
   docs. Then CI can fail if any field is emitted by Ceres-J as a literal while
   tagged `UNVERIFIED`.
2. For every **B** row, the Ceres-J emitter must source the value from the
   character/account/session record, never a literal — same fix already applied
   to `0x8385[2..3]` session_byte and `0x03/0x28[7..10]` NPC handle.
3. The corpus bias is the root cause. Highest-leverage new captures:
   a **2-or-3-char account** (kills #1, #2 — **DONE**, see below), a **char with
   non-default soullight/faction** (probed #3 — soullight ruled out), and a
   **populated-inventory** capture (kills #6–#8, still open).

---

## Captures generated — multi-account CharList sweep (2026-06-06)

Resolves scenario #1. Retail `0x8385` CharList pulled for 3 accounts (same
password, all on `titan`) via `nc2-bot/scripts/capture_charlist_accounts.py`
(stage-1 InfoServer → stage-2 GameLobby; CharList is TCP, pre-UDP, so the retail
UDP block is irrelevant). Real pcaps under `nc2-bot/captures/`:

| pcap | account | acct_id | char_count[4..5] | populated / CHARDUMMY | session_state[2..3] |
|---|---|---|---|---|---|
| `retail_charlist_msn2wolf_20260606_095258.pcap` | msn2wolf | 41648 | `04 00` | 4 / 0 | `0x000a` |
| `retail_charlist_msn3wolf_20260606_095234.pcap` | msn3wolf | 97981 | `04 00` | 3 / 1 | `0x02e3` |
| `retail_charlist_msn4wolf_20260606_095258.pcap` | msn4wolf | 98074 | `04 00` | 2 / 2 | `0x000a` |

### Controlled confirmation on Ceres-J (2026-06-06)

Retail only proves `[4..5]` is *invariant* at 4; to prove it is the **slot-array
length** the field controls, we varied it server-side. `CharList.java` was wired
to emit `CharsPerAccount` (it previously hardcoded `4` and ignored the config — a
latent constant-bug) and a fresh 0-char account `testbot` (auto-created) was read
back via `nc2-bot connect_ceresj.py` at each setting:

| CharsPerAccount | `char_count[4..5]` emitted | empty (CHARDUMMY) slots parsed |
|---|---|---|
| 4 | 4 | 4 |
| 3 | 3 | 3 |
| 2 | 2 | 2 |
| 1 | 1 | 1 |

Each packet parsed cleanly (struct_size `0x29`, exactly N well-formed CHARDUMMY
blocks) — so `[4..5]` **is** the slot-array length, and empty slots are
CHARDUMMY-filled up to that count. **Definitively confirms #1 → A:** a true
structural constant whose retail value is fixed at 4 (max slots/account). Ceres-J
now respects `CharsPerAccount` (default 4 = retail-faithful), **hard-capped at 4**.

### Client hard cap = 4 slots (Frida real-client A/B, 2026-06-06)

Tested whether the client supports a 5th slot by emitting `char_count=5` and
driving the real client (Frida-in-Wine harness, `msn3wolf` vs Ceres):

| char_count | real client result |
|---|---|
| 5 | **CRASH** — client died right after credentials, never reached char-select, no in-world UDP |
| 4 (control, same harness/account) | full login → char-select (4 slot panels) → in-world, complete pass |

The only variable between the two runs was `char_count` 5 vs 4. **The client does
NOT support 5 slots — `char_count > 4` crashes it during login** (fixed 4-element
parse buffer; matches `Account int[4]`, the 4-panel char-select UI, and retail
always sending 4). `[4..5]` is therefore not merely invariant-at-4 but **hard-
bounded at 4** — exceeding it is fatal, so Ceres-J clamps and must never emit > 4.

Across all 9 characters (varied class/location): `[25..29]` = `01 01 01 01 01`,
`[37..40]` = `00 00 00 00`, `profession[30..33]` = `0` — all invariant.
`session_state[2..3]` varies (confirmed).

**`profession[30..33]` resolved (2026-06-06):** a live field, not a constant —
Ceres-J chars decode with profession 10/20 (and class 5 → `cls 2.1`), so the
position is genuinely profession. The retail 0s are because those test chars
have profession 0 (or retail zeroes it at lobby).

> **Divergence candidate (flag) — strengthened 2026-06-06 via fresh retail char
> "Gutso":** retail sends `profession=0` in **10/10** observed chars, now
> including a **freshly-created, fully-customized** char (class 2/0). Ceres-J
> emits live `getMisc(PROFESSION)` (10/20). So Ceres-J over-emits profession at
> the lobby; faithful behavior is `0` for every char we can observe. Only escape:
> a retail char with an in-game-developed profession might send nonzero — needs
> such a capture to be 100% sure; until then treat Ceres's nonzero emission as
> an **unverified divergence (likely emit 0)**.
>
> **`faction` resolved-negative:** it is NOT in the 41-byte CharList struct
> (Gutso's faction pick didn't move any byte), so it is delivered elsewhere, not
> at char-select. It is NOT the `[37..40]`/`[25..29]` UNVERIFIED bytes.
>
> **`[25..29]` / `[37..40]` strengthened:** unchanged on a fresh fully-customized
> char → not appearance/render-flags/faction. Template-constant / reserved is now
> the leading read; residual = the client-side parser (Ghidra).

**Appearance fields confirmed (Gutso):** the 8 model/texture LE16s
(`0x8385` per-char `[4..19]`) carry the FACE/JACKET/PANTS · MODEL/TEXTURE +
HAIRCUT + FACIAL HAIR creation picks (client-side asset indices). See
[`packets/tcp_s2c_8385.md`](packets/tcp_s2c_8385.md). **Takeaway:** the first-principles
critique was right to demand verification, and verification cut both ways — it
*exonerated* char_count (a genuine structural constant) while confirming the
method. A constant invariant across account/char/zone variation is far stronger
than one pinned from a single corpus; the remaining B-rows still lack that test.
