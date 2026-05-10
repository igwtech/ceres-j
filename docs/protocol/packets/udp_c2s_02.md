# `UDP C->S 0x02` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x02`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **13**
- Captures with this packet: **3/17**
- Size (bytes): min **11**, avg **11**, max **12**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_GENREP_OPEN × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 6
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 4
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 3

Samples (first 32 bytes inner data):

```
#1: 02445a1f01003d1100000000
```
```
#2: 02455a1f01003d1100000000
```
```
#3: 02465a1f01003d1100000000
```

<!-- /catalog-evidence -->

## Structure

UDP C→S raw 0x02 — **simplified-reliable wrapper** for client
retransmissions. Mirrors the `udp_s2c_02` server-side variant
(see that doc for full layout). Verified 2026-05-10 against
all 13 retail samples from 3/17 captures.

```
[0]      0x02                    outer opcode (simplified-reliable)
[1..2]   seq LE16                client sequence counter (1303-style)
[3..]    inner sub-packet        same shape as 0x03 reliable's
                                  inner — `[sub-tag][body]`
```

Verified samples:
```
02 9f 3b  1f 01 00 3d 11 00 00 00 00     CREATION — sub-tag 0x1f
02 a0 3b  1f 01 00 3d 11 00 00 00 00     CREATION — sub-tag 0x1f
02 a1 3b  1f 01 00 3d 11 00 00 00 00     CREATION — sub-tag 0x1f
02 4b 8a  1f 01 00 17 00 88 07 00         CREATION — different sub-action
02 44 5a  1f 01 00 3d 11 00 00 00 00     VEHICLE_DRONE
```

## Variants

Single 11-12B form. The inner sub-tag is consistently `0x1f`
(GamePackets) — the simplified-reliable wrapper carries
client-side game-action retransmissions for sub-tag 0x1f only
(no other sub-tags observed in retail).

The 1-byte size variation (11 vs 12B) comes from the inner
body length: most are 12B (`3d 11 00 00 00 00` = 6B body) with
a few 11B variants (`17 00 88 07 00` = 5B body).

## Observed contexts

Only 3/17 captures emit this:
- `RETAIL_RETAIL_VEHICLE_DRONE` × 6 (vehicle/drone control bursts)
- `RETAIL_CREATION_LEVELING_LONG` × 4 (intensive levelling activity)
- `RETAIL_ZONING_AND_ITEMS_LONG` × 3 (zoning bursts)

Top marker `OUTSIDE_AREAM5_GENREP_OPEN`. Pattern matches
retransmissions of GamePackets — the 0x02 wrapper is the C→S
equivalent of the server's retransmit-ring path.

## Open questions

- Why does the client only retransmit GamePackets (0x1f)
  through 0x02? Other reliable sub-tags presumably retry via
  the standard 0x03 reliable path with a fresh seq.
- The inner body `3d 11 00 00 00 00` is byte-identical across
  multiple 0x02 emissions in the same capture — the same
  GamePacket retried multiple times via 0x02.

## Server-side handler

`server.gameserver.packets.GamePacketReaderUDP.case 0x02:` —
the body should be parsed exactly like a 0x03 reliable's
inner, via the existing sub-tag handler table, with seq
tracking via the 0x02 wrapper's seq field.

This is the C→S complement of the server's
`PacketBuilderUDP1302` retransmission wrapper. See
`udp_s2c_02.md` for the symmetric S→C form.

