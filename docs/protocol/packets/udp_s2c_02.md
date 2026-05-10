# `UDP S->C 0x02` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x02`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **1901**
- Captures with this packet: **17/17**
- Size (bytes): min **1**, avg **16**, max **90**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_GENREP_OPEN × 80
  - IN_WORLD × 51
  - AFTER_CHAR_SELECT_WORLDENTRY × 38
  - INVENTORY_MANAGE × 2
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 603
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 221
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 152
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 124
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 110
  - `RETAIL_DRSTONE_20260501_175315` × 90
  - `RETAIL_AUGUSTO_20260426_201952` × 69
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 68
  - `RETAIL_DRSTONE_20260501_172522` × 65
  - `RETAIL_DRSTONE3_20260501_181349` × 64
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 62
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 52
  - `RETAIL_DRSTONE4_20260501_193336` × 51
  - `RETAIL_HANNIBAL_20260426_201501` × 49
  - `RETAIL_NORMAN_20260426_200458` × 44
  - `RETAIL_ODA_20260426_202428` × 39
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 38

Samples (first 32 bytes inner data):

```
#1: 0201002f04000200330002014802023fe002031002040602058f020617020710
```
```
#2: 0202001f0400252333
```
```
#3: 0203001f0400251f0000c842
```

<!-- /catalog-evidence -->

## Structure

S→C "simplified-reliable" channel. Wire shape mirrors the
{@code 0x03} reliable channel but with sub-op {@code 0x02} —
client does NOT ACK these (no symmetric retransmit channel).

Wire layout:

```
[0]      0x02                   sub-opcode (constant)
[1..2]   seq LE16                independent counter (starts at 1
                                  per session, separate from the
                                  0x03 reliable seq counter)
[3]      inner sub-type          gameplay tag (0x1f, 0x2c, 0x2e,
                                  0x2f, 0x23, etc.)
[4..]    payload                  inner-sub-type-specific
```

Sample retail bodies (catalog):
```
0201002f 04000200330002014802023fe002031002040602058f020617020710
0202001f 0400252333
0203001f 0400251f0000c842
```

The `[02][seq LE2]` prefix is identical to the `[03][seq LE2]`
reliable form. Both share the inner sub-type space (0x1f
GamePackets, 0x2c PositionUpdate, 0x23 InfoResponse, etc.).

## Variants

Two distinct uses of the {@code 0x02} channel:

1. **Init-burst replay** (the dominant use, ~1700 obs). Retail
   server pushes init-burst data (UpdateModel, Weather,
   InfoResponse zoneInfo, GamePackets/Soullight) on this channel
   right after the CharInfo multipart burst. Each emit has its
   own independent seq starting at 1.

2. **Retransmit responder** (the new use, since task #136). When
   the client misses a reliable {@code 0x03} sub-packet, it
   sends a C→S {@code 0x01 [seq LE2]} ack-request and the server
   re-emits via {@code 0x02} — same body content as the original
   reliable, but with the simplified wrapper. See
   `server.gameserver.packets.client_udp.ReliableAckSubPacket`.

## Observed contexts

Heaviest emission concentration is at world-entry post-handshake
(catalog top markers near the WORLDENTRY phase). The init-burst
batch contains about 6-8 packets (UpdateModel, Weather,
InfoResponse, Soullight, etc.) emitted within a ~100ms window.

Per-capture: RETAIL_VEHICLE_DRONE has the highest counts
(actively-played sessions emit more retransmit responses);
short captures (DRSTONE3) have fewer.

## Open questions

- Is the init-burst seq numbering guaranteed to start at 1, or
  can multiple session re-syncs reset it? Catalog only shows
  monotonic-from-1.
- Does the client treat init-burst 0x02 differently from
  retransmit 0x02 by content? Empirically the bytes look
  identical (just different inner sub-types).

## Server-side handler

Ceres-J emits {@code 0x02} via two paths:

1. **Init02 builders** (init-burst):
   - {@link server.gameserver.packets.server_udp.InitInfoResponse02}
   - {@link server.gameserver.packets.server_udp.InitWeather02}
   - {@link server.gameserver.packets.server_udp.InitUpdateModel02}
   - {@link server.gameserver.packets.server_udp.InitSoullight02}

   Wired in {@code WorldEntryEvent.execute()}.

2. **Retransmit responder**: {@link
   server.gameserver.packets.client_udp.ReliableAckSubPacket} →
   {@link server.networktools.PacketBuilderUDP1302} (via the
   {@link server.networktools.ReliablePacketRing}).

Decoded in `GamePacketReaderUDP.decodesub13()` `case 0x02:` —
which falls through to the same sub-action switch as `case 0x03`
(both share the inner sub-type space). 4 tests pin this:
`PacketBuilderUDP1303RingHookTest` + `ReliableAckSubPacketTest`.

