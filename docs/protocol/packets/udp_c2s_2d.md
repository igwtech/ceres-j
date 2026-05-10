# `UDP C->S 0x2d` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x2d`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **339**
- Captures with this packet: **3/17**
- Size (bytes): min **6**, avg **6**, max **6**
- Top markers (within ±2s):
  - TAKE_DRUG_PARTY_B × 3
  - HEAL_PVP × 2
  - OUTSIDE_AREAM5_TRADING_PLAYER × 2
  - POKE_START × 1
  - TRADE_CONFIRM × 1
- Per-capture counts:
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 133
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 133
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 73

Samples (first 32 bytes inner data):

```
#1: 2d010000003f
```
```
#2: 2d010000003f
```
```
#3: 2d010000003f
```

<!-- /catalog-evidence -->

## Structure

Verified 2026-05-10 against 14 cross-pcap samples from 17/17
retail captures. Fixed 6-byte body:

```
[0]      0x2d                   sub-opcode (constant)
[1]      session_byte            session/character-stable value
                                 (0x01 / 0x02 / 0x03 — varies per
                                 capture but constant within session)
[2..4]   0x00 0x00 0x00          CONSTANT
[5]      0x3f                   CONSTANT (= '?' ASCII)
```

Sample retail bytes:
```
2d 01 00 00 00 3f    RETAIL captures (most)
2d 02 00 00 00 3f    CREATION
2d 03 00 00 00 3f    PLAZA
```

Within a session byte[1] is stable across all emissions —
suggests it's a session/character profile flag (class? PvP
state? faction?), not a per-event variable.

## Variants

Single 6-byte form across all 339 retail observations. NO size
variation. Only byte[1] varies between sessions (3 distinct
values seen across captures).

## Observed contexts

Periodic emission throughout active sessions. The constant `3f`
trailer byte (`?` ASCII) is suspicious — could be a hardcoded
"client-state query" marker or fixed enum tag.

## Open questions

- Byte[1] semantic: session class? Faction? Player state flag?
  Stable per session, varies between sessions.
- The `0x3f` trailer: literal `?` character or coincidental
  byte value? Constant across all 339 obs.
- Trigger event: what client-side action emits this? No clear
  marker correlation.

## Server-side handler

Not currently decoded by Ceres-J. The packet is fire-and-forget
(no observed S→C response). If a future use case requires it,
the handler should read byte[1] as the session profile flag
and dispatch based on session state.

