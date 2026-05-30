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

TCP S→C 0xa001 — `SessionReady-S`. **Two known variants** (live
diff'd 2026-05-22 against Braine real-client capture):

### 2-byte variant (April 2026 captures, legacy)
Body `a0 01`. Wire `fe 02 00 a0 01` = 5 bytes total.
Verified 2026-05-10 against all 34 catalog samples from 11/17
captures.

```
[0..1]   a0 01                  TCP opcode
```

### 10-byte variant (May 2026 live)
Body `a0 01 + 15 00 00 00 00 00 80 3f`. Wire
`fe 0a 00 a0 01 15 00 00 00 00 00 80 3f` = 13 bytes total.
Live-verified 2026-05-22 from Braine real-client capture.

```
[0..1]   a0 01                  TCP opcode
[2..5]   15 00 00 00            LE32 = 21 (session-state const)
[6..9]   00 00 80 3f            float32 LE = 1.0 (version const)
```

The modern client accepts BOTH forms. Ceres-J emits the
10-byte form (current-retail-faithful) — see `SessionReady.java`.

## Variants

The `0xa0 NN` family encodes session-state transitions:
- `0xa001` — S→C (this packet)
- `0xa002` — S→C InteractionAck (separate doc)
- `0xa003` — C→S `ReadyProbe` ("ready-for-state-advance ping";
  see `tcp_c2s_a003.md`) — triggers the server to send `0xa001`

## Observed contexts

Emitted between `AuthAck` (0x8381) and `CharList` (0x8385) as
part of the post-Auth sequence. Top marker `RESUME` correlates
with the resume-login flow.

**2026-05-22 update**: emission is now SYNCHRONOUS in response
to the client's `0xa003` `ReadyProbe`. The legacy "fire-and-forget"
unsolicited emit still works (older clients) but modern clients
drive the request/reply pattern. Without `0xa001`, the modern
NCE 2.5.x client REJECTS the following CharList silently and
stays stuck on "updating data" forever, retrying via `0xa003`
pings. So the modern client REQUIRES this packet between
AuthAck and CharList.

34 catalog emissions / 11 captures means ~3 per session (resume +
re-login + char re-query).

Also emitted **after `AuthB` (Stage 3)** by `AuthB.execute` —
before `UDPServerData` + `Location` — to advance the post-Auth
world-entry burst on legacy clients that don't drive the
`0xa003` exchange.

## Open questions

The 8-byte payload `15 00 00 00 00 00 80 3f` (LE32=21 + float=1.0)
is invariant across all observed Braine samples. Interpretation
hypotheses:
- "session_id" + "client protocol version" pair
- "max chars per account" + "feature flag" pair
- Pure constants the client never inspects (most likely — Ceres-J
  emits them blindly and the modern client accepts).

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

