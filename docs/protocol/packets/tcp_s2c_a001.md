# `TCP S->C 0xa001` — SessionReady-S

**Transport:** TCP  
**Direction:** S->C  
**Identifier:** `0xa001`  
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
#1: a001
```
```
#2: a001
```
```
#3: a001
```

<!-- /catalog-evidence -->

## Structure

TCP S→C 0xa001 — `SessionReady-S`. Fixed 2-byte body
`a0 01`. Pure constant. Verified 2026-05-10 against all 34
retail samples from 11/17 captures.

```
[0..1]   a0 01                  TCP opcode (constant)
```

Wire framing: `fe 02 00 a0 01` (3-byte prefix `[fe][len LE16=2]`
+ 2-byte body) = 5 bytes total on the wire.

All 34 observations are byte-identical: `a0 01`. NO content
variation.

## Variants

Single 2-byte form. Pure constant. The `0xa0 NN` family
encodes session-state transitions as 2-byte signals:
- `0xa001` — S→C "auth complete, advancing to char-list"
- `0xa002` — S→C InteractionAck (separate doc)
- `0xa003` — C→S SessionReady-C ("waiting / state-ready
  ping"; see `tcp_c2s_a003.md`)

## Observed contexts

Emitted between `AuthAck` (0x8381) and `CharList` (0x8385) as
part of the post-Auth sequence. Top marker `RESUME` correlates
with the resume-login flow.

Without 0xa001, the modern NCE 2.5.x client REJECTS the
following CharList silently and stays stuck on "updating data"
forever, retrying via `0xa003` keepalive pings. So the modern
client REQUIRES this packet between AuthAck and CharList.

34 emissions / 11 captures means ~3 per session (resume +
re-login + char re-query).

## Open questions

None — fully decoded constant signal. The `0xa0` prefix is
shared with `0xa002` (InteractionAck) and `0xa003`
(SessionReady-C), suggesting it's a reserved subsystem byte
for session/state management.

## Server-side handler

`server.gameserver.packets.server_tcp.SessionReady` — emits
the 2-byte constant body `a0 01`.

Wired from:
- `server.gameserver.packets.client_tcp.Auth.java:69` —
  emitted between `AuthAck` and `CharList` in the post-Auth
  sequence.
- `server.gameserver.packets.client_tcp.AuthB.java:111` —
  emitted in the AuthB post-flow between UDP descriptors.

This packet is **critical** for client state advancement.
Removing it causes the modern NC2 client to hang indefinitely
on "updating data". See task #151 (overlay-clear identification)
for related notes.

