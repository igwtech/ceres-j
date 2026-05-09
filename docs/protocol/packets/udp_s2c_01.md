# `UDP S->C 0x01` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x01`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **13**
- Captures with this packet: **3/17**
- Size (bytes): min **3**, avg **3**, max **3**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_GENREP_OPEN × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 6
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 4
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 3

Samples (first 32 bytes inner data):

```
#1: 01445a
```
```
#2: 01455a
```
```
#3: 01465a
```

<!-- /catalog-evidence -->

## Structure

Fixed 3-byte body — reliable-ACK frame:

```
offset  size  field
   0      1   0x01      tag
   1      2   seq LE16  sequence-number being ACKed
```

This is the same wire shape that
`GamePacketReaderUDP.readPacket` synthesises when ACKing every
client-pushed `0x03` reliable. The ACK is server-pushed back to
the client as a one-frame `0x13` body with a single `0x01` sub.

## Variants

None. All 13 hits are exactly 3 bytes. The LE16 is not constant
— it monotonically increments across each capture's connection,
e.g. the `RETAIL_VEHICLE_DRONE` capture shows
`01 44 5a, 01 45 5a, 01 46 5a, 01 47 5a, 01 48 5a, 01 49 5a` —
proving sequence-number semantics rather than a constant tag.

Per-capture seq base differs (sample of three captures):

- `RETAIL_VEHICLE_DRONE` — base around `0x5a44`
- `CREATION_LEVELING_LONG` — bases around `0x3b9f` and `0x8a4b`
- `ZONING_AND_ITEMS_LONG` — base around `0x147e`

## Observed contexts

3 of 17 captures, 13 hits. Top marker:
`OUTSIDE_AREAM5_GENREP_OPEN` (×1). The ACK is a transport-layer
artefact — it fires whenever the server has a pending reliable
to acknowledge, not in response to any specific gameplay event.

## Open questions

None at the field level — layout is identical to the symmetric
C→S ACK already implemented in the dispatcher.

## Server-side handler

[`server.gameserver.packets.client_udp.ReliableAckSubPacket`](../../../src/main/java/server/gameserver/packets/client_udp/ReliableAckSubPacket.java)
— recognise-only. The class exposes `ackSeq()` for tests; full
reliable-retransmit modelling is a separate task.

