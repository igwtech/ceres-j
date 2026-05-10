# `UDP C->S 0x55` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x55`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **6**
- Captures with this packet: **5/17**
- Size (bytes): min **15**, avg **15**, max **15**
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 2
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 1
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 1
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 1
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 1

Samples (first 32 bytes inner data):

```
#1: 55393000009254381c18429bc52f67
```
```
#2: 55393000009254381c18429bc52f67
```
```
#3: 55393000009254381c18429bc52f67
```

<!-- /catalog-evidence -->

## Structure

UDP C→S raw 0x55 — **unknown channel**. Fixed 15-byte body.
Verified 2026-05-10 against all 6 retail samples from 5/17
captures. ALL samples are byte-identical:
`55 39 30 00 00 92 54 38 1c 18 42 9b c5 2f 67`.

```
[0]      0x55                    sub-opcode (constant)
[1..2]   39 30                   = LE16 0x3039 = 12345 (looks like
                                  a "magic" or version number?)
[3..4]   00 00                   CONSTANT
[5..8]   92 54 38 1c             LE32 = 0x1c385492 — bit-stable
                                  per-session
[9..12]  18 42 9b c5             LE32 — likely a float (= LE32
                                  0xc59b4218 = -4980.51)
[13..14] 2f 67                   trailing 2 bytes
```

The byte-stable pattern across 6 different captures (different
sessions, different chars) is striking — this suggests these
are NOT session-derived bytes but some kind of CONSTANT signal.
The high byte 0x67 of `0x6730` doesn't form a clean numeric
value either.

This same signature also appears within `udp_c2s_00`'s 15B
form (`00 55 39 30 00 00 92 54 38 1c 18 42 9b c5 2f` — note
trailing byte differs: 0x2f vs 0x67), suggesting raw 0x55 is
the same packet emitted directly without the 0x00 unreliable
wrapper.

## Variants

Single 15-byte form. All retail samples byte-identical.

## Observed contexts

Concentrated lightly across PvP / zoning captures:
- `RETAIL_RETAIL_VEHICLE_DRONE` × 2
- `RETAIL_RETAIL_LONG_PARTY_A/B` × 1 each
- `RETAIL_ZONING_AND_ITEMS_LONG` × 1
- `RETAIL_CREATION_LEVELING_LONG` × 1

Only 6 emissions across 5 captures — extremely rare. Without
context markers it's hard to pin the trigger.

## Open questions

- The full byte payload is identical across 6 captures from 5
  different sessions. This rules out session-derived data
  (e.g. char_uid, port) and points to either:
  1. A hardcoded "client-info" / "client-version" probe.
  2. A debug or telemetry signature emitted on rare events.
  3. A magic-number "I support feature X" capability flag.
- Sub-tag 0x55 is not in `OPCODE_STRUCTURE.md` and has no
  reliable-channel counterpart (no `0x03/0x55` in retail).
- The trailing byte differs between raw 0x55 (=0x67) and the
  0x00→0x55 unreliable form (=0x2f). Possibly a checksum
  varying with the wrapper context.

## Server-side handler

**NOT IMPLEMENTED** in Ceres-J. No `case 0x55:` in
`GamePacketReaderUDP`. Bytes silently consumed.

For full retail parity:
1. Recognise 0x55 in the dispatcher (no-op log first).
2. Investigate further if any client-side regression appears
   when this packet's response is missing.

This is a **low-priority parity gap** given the rarity (6
samples) and lack of a known purpose.

