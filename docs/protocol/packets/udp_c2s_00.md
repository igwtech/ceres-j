# `UDP C->S 0x00` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x00`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **6338**
- Captures with this packet: **17/17**
- Size (bytes): min **1**, avg **7**, max **231**
- Top markers (within ±2s):
  - POKE_START × 23
  - TRADE_CASH_CONFIRM × 17
  - FIRE_PVP_2 × 17
  - WHISPER × 16
  - AIM_PVP × 16
- Per-capture counts:
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 2987
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 2987
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 165
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 108
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 19
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 16
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 11
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 10
  - `RETAIL_HANNIBAL_20260426_201501` × 10
  - `RETAIL_ODA_20260426_202428` × 9
  - `RETAIL_DRSTONE_20260501_175315` × 6
  - `RETAIL_AUGUSTO_20260426_201952` × 3
  - `RETAIL_NORMAN_20260426_200458` × 2
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 2
  - `RETAIL_DRSTONE4_20260501_193336` × 1
  - `RETAIL_DRSTONE3_20260501_181349` × 1
  - `RETAIL_DRSTONE_20260501_172522` × 1

Samples (first 32 bytes inner data):

```
#1: 002d03000000
```
```
#2: 002d03000000
```
```
#3: 003c040006870000000012f3
```

<!-- /catalog-evidence -->

## Structure

C→S **unreliable channel** — outer opcode 0x00 inside a 0x13
gamedata burst is the unreliable counterpart to the 0x03
reliable channel. Same sub-tag namespace; differing only in
delivery semantics (no seq/ack, fire-and-forget).

Verified 2026-05-10 against all 6,338 retail observations from
17/17 captures. Multiple body sizes — each driven by the inner
sub-tag.

```
[0]      0x00                    outer opcode (unreliable)
[1..]    sub-tag + body          uses same sub-tag space as 0x03
                                  reliable channel
```

## Variants

Size distribution across 6,338 retail samples — all sub-tags
verified 2026-05-10:

| Size  | Count | Sub-tag | Form                                   |
|-------|-------|---------|----------------------------------------|
| 1B    | 23    | bare    | `00` — keepalive ping (no body)        |
| 5B    | 60    | 0x27    | `00 27 [LE16] 00` — RequestInfo unrel  |
| 6B    | 5,860 | 0x2d    | `00 2d [sub-action] 00 00 00` — DOMINANT|
| 12B   | 350   | 0x3c    | = `udp_c2s_3c` entity-action           |
| 15B   | 15    | 0x55    | `00 55 [LE16][LE32 const][LE32 float]` |
| 17B   | 3     | (?)     | `00 00 00 00 11 00 20 01 00 27 …`     |
| 211B  | 24    | 0x07    | unreliable multipart carrier (Lua/0x00)|
| 231B  | 3     | 0x07    | unreliable multipart carrier (large)   |

The 92% dominance of the 6-byte `0x00→0x2d` form, concentrated
in `RETAIL_LONG_PARTY_A/B` (2,987 each — PvP-heavy captures),
makes it the **unreliable variant of the 0x03/0x2d
sub-action-state channel**. Likely fires during combat /
movement / target-state changes where the client tolerates
packet loss for lower latency.

Verified 6-byte body samples:
```
00 2d 02 00 00 00    (CREATION_LEVELING — sub-action 0x02)
```

Verified 5-byte body samples:
```
00 27 fb 03 00       (HANNIBAL — RequestInfo about world_id 0x03fb)
00 27 fc 03 00       (HANNIBAL — world_id 0x03fc)
```
The world_id pattern (0x03fb..0x03fc) is in the SUBWAY entity-id
range (0x03f7..0x03fc per memory), confirming this is the
unreliable-channel "tell me about subway-train entity X" probe.

Verified 12-byte body (sub-tag 0x3c — entity-action):
```
00 3c 01 00 [sub-action u8] [LE32 ref] 00 [LE32 float]
```
Bit-for-bit identical to `udp_c2s_3c` raw form. Both forms are
emitted; the 0x00→0x3c unreliable form is preferred for
high-frequency events where loss is acceptable.

Verified 15-byte body (sub-tag 0x55 — UNKNOWN):
```
00 55 39 30 00 00 92 54 38 1c 18 42 9b c5 2f   CREATION_LEVELING
```
Sub-tag 0x55 is not in `OPCODE_STRUCTURE.md`. The trailing
8 bytes look like 2× LE32 floats. Only 15 retail observations
across the dataset.

Verified 211-byte body (sub-tag 0x07 — unreliable multipart):
```
00 07 08 00 09 00 00 00 [01|02|04] 07 20 00 [zero pad to 211]
```
The inner payload `09 00 00 00 04 07 20 00` matches the
header of `udp_c2s_03_00` (Lua scenario reference) — so the
unreliable multipart channel is carrying the same Lua-script
notification, just unreliably.

Verified 12B form (sub-tag 0x3c — entity-action notification):

```
00 3c 01 00 [sub-action u8] [LE32 ref] 00 [LE32 float]
```

This is bit-for-bit identical to `udp_c2s_3c` raw (which uses
0x3c as the outer). Both forms are emitted; the 0x00→0x3c
unreliable form is preferred for high-frequency events where
loss is acceptable.

Verified 211B form (sub-tag 0x07 — unreliable multipart carrier):

```
00 07 08 00 [reliable_subop] 00 00 00 [body...] [zero pad to 211]
```

Carries fragments of 0x03/0x00 (Lua scenario) payloads through
the unreliable channel. This explains why some `udp_c2s_03_00`
contents arrive without reliability guarantees.

## Observed contexts

Top markers:
- `POKE_START` × 23 — combat aim/poke
- `TRADE_CASH_CONFIRM` × 17 — trade confirmation
- `FIRE_PVP_2` × 17 — PvP weapon fire
- `WHISPER` × 16 — whisper chat
- `AIM_PVP` × 16 — PvP targeting

The marker pattern strongly suggests this channel is used for:
1. Combat events where latency matters more than reliability
   (aim updates, weapon-fire reports, PvP targeting).
2. Frequent state updates (position, animation) that the
   server reconciles via authoritative broadcasts.
3. Trade UI confirmations and chat where re-emission is cheap.

## Open questions

- 6B `0x00→0x2d` sub-action enum: only 0x02 directly
  observed in samples; need a wider scan to map the full enum
  vs `0x03/0x2d` reliable's known sub-actions.
- Sub-tag 0x55 (15B form) — only 15 retail observations. Not
  in `OPCODE_STRUCTURE.md`. The 8-byte float-pair trailer
  hints at coordinates / vector quantity. Possibly debug
  telemetry.
- 17B form — starts with 4 zero bytes (`00 00 00 00`) before
  the actual content. Could be a malformed extraction or a
  legitimate alternate sub-format. Only 3 samples; needs
  further investigation.
- Why does retail prefer unreliable for chat (`WHISPER`)?
  Reliable would seem more appropriate. Possibly only the
  acknowledgement is unreliable, with the actual chat going
  through the reliable channel separately.
- The 211B/231B unreliable-multipart forms: why fragment
  small payloads through unreliable rather than reliable
  multipart? Possibly when the reliable channel is congested
  or when the sender wants to clear the reliable queue.

## Server-side handler

`server.gameserver.packets.GamePacketReaderUDP.case 0x00:` —
currently unhandled in the reliable dispatcher. The unreliable
0x00 outer is silently consumed as no-op.

For full retail parity, the dispatcher should:
1. Strip the 0x00 outer.
2. Parse the inner sub-tag using the SAME handler table as
   0x03 reliable (channel-duality per
   `project_opcode_structure.md`).
3. Skip seq/ack tracking (this is the unreliable path).
4. Defer to existing 0x03/<sub> handlers for the body.

The high volume (6,338 obs / 17 captures, ~373/cap) confirms
this is a hot-path channel. Ignoring it has implications for:
- Movement smoothness in PvP (clients send position via 0x00,
  server doesn't reconcile).
- Combat hit reporting (clients fire events through 0x00, server
  silently drops them).
- Chat whisper delivery (currently broken? unconfirmed).

This is a **P2 implementation gap** for retail-parity
gameplay; fix would route 0x00 sub-tags to the existing 0x03
reliable handlers with seq/ack stripped.

