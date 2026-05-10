# `TCP C->S 0xa003` — SessionReady-C

**Transport:** TCP  
**Direction:** C->S  
**Identifier:** `0xa003`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **34**
- Captures with this packet: **11/17**
- Size (bytes): min **2**, avg **2**, max **2**
- Top markers (within ±2s):
  - RESUME × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 6
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 4
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 4
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 4
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 3
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 3
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 2
  - `RETAIL_DRSTONE4_20260501_193336` × 2
  - `RETAIL_DRSTONE3_20260501_181349` × 2
  - `RETAIL_DRSTONE_20260501_172522` × 2
  - `RETAIL_DRSTONE_20260501_175315` × 2

Samples (first 32 bytes inner data):

```
#1: a003
```
```
#2: a003
```
```
#3: a003
```

<!-- /catalog-evidence -->

## Structure

TCP C→S 0xa003 — `SessionReady-C`. Fixed 2-byte body
`a0 03`. Pure constant. Verified 2026-05-10 against all 34
retail samples from 11/17 captures.

```
[0..1]   a0 03                  TCP opcode (constant)
```

Wire framing: `fe 02 00 a0 03` (3-byte prefix `[fe][len LE16=2]`
+ 2-byte body) = 5 bytes total on the wire.

All 34 observations are byte-identical: `a0 03`. NO content
variation.

## Variants

Single 2-byte form. Pure constant.

The `0xa0 NN` family of session-state signals:
- `0xa001` — S→C SessionReady-S (auth complete; see
  `tcp_s2c_a001.md`)
- `0xa002` — S→C InteractionAck (separate doc)
- `0xa003` — C→S SessionReady-C (this packet) — client's
  "waiting for char list / state-ready ping"

## Observed contexts

Emitted by the client between `AuthAck` (0x8381) reception and
`CharList` (0x8385) reception, as a "are you still alive?"
keepalive probe. The retail client retries `0xa003` ~once per
second until it gets a CharList back.

If the server fails to emit `SessionReady-S` (`0xa001`)
between AuthAck and CharList, the client stays stuck spamming
`0xa003` forever (the symptom that diagnosed the missing
SessionReady issue, source-doc'd in `SessionReady.java`).

Top marker `RESUME` correlates with the resume-login flow. 34
emissions / 11 captures = ~3 per session (resume + re-login +
char re-query).

## Open questions

None — fully decoded constant probe. Server response is
implicit: the client stops emitting `0xa003` once it receives
the CharList.

## Server-side handler

**Currently no-op handled** by `GamePacketReaderTCP` — the
server recognises the 2-byte packet and consumes it without
explicit response. The implicit response is the natural flow
of the post-Auth sequence (`AuthAck` → `SessionReady-S` →
`CharList`).

If the client emits `0xa003` AFTER receiving CharList, that's
a sign the client believes its session is desynced. Currently
no remediation logic — server just continues normal flow.

For full retail parity, no specific handler is needed: this
packet is a passive probe and the server's natural emission
sequence already satisfies the contract.

