# `UDP S->C 0x0b` — CPing

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x0b`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **4939**
- Captures with this packet: **17/17**
- Size (bytes): min **9**, avg **9**, max **13**
- Top markers (within ±2s):
  - BASELINE_HUD × 6
  - KILL_MOB × 6
  - KILL_MOB2 × 6
  - POKE_START × 6
  - FIRE_PVP_2 × 4
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 1331
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 990
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 818
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 810
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 300
  - `RETAIL_NORMAN_20260426_200458` × 126
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 106
  - `RETAIL_ODA_20260426_202428` × 103
  - `RETAIL_DRSTONE_20260501_175315` × 69
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 67
  - `RETAIL_AUGUSTO_20260426_201952` × 57
  - `RETAIL_HANNIBAL_20260426_201501` × 53
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 41
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 26
  - `RETAIL_DRSTONE4_20260501_193336` × 19
  - `RETAIL_DRSTONE3_20260501_181349` × 12
  - `RETAIL_DRSTONE_20260501_172522` × 11

Samples (first 32 bytes inner data):

```
#1: 0bb8692d00d7c80205
```
```
#2: 0b59772d006fd60205
```
```
#3: 0b10832d002ee20205
```

<!-- /catalog-evidence -->

## Structure

The server's reply to a {@code C→S 0x0b CPing} client keepalive: an
ordinary {@code 0x13} datagram carrying ONE unreliable 9-byte
sub-packet (16 bytes on the wire). The outer counter is the current
reliable counter, not consumed (`PacketBuilderUDP13`).

```
0x13 frame:
[0]      0x13
[1..2]   counter LE16             current reliable counter (not consumed)
[3..4]   counter+sessionkey LE16
[5..6]   sub_len LE16 = 9
sub-packet:
[0]      0x0b                    sub-opcode (constant)
[1..4]   server_time LE32         Timer.getIngametime() — 6-day
                                   cycle modulo encoding (max ~518.4M ms)
[5..8]   client_time echo LE32   the LE32 sent in the C→S 0x0b CPing
```

Retail ground truth = the client's own decrypted receive path (Frida
harness `udp_recv`): 884 S→C `0x0b` replies across the retail traces,
every one inside a `0x13` frame, none at transport level. Sample
(retail_draw_v3, t=2.724 s, answering C→S `0b abaa9502` sent at
t=2.534 s):
```
13 4700 3181 0900 | 0b 6d4a6a00 abaa9502
                     server_time=0x006a4a6d, client_time echo=0x0295aaab
```
Inner sub-packet samples (catalog, pcap-decoded with the frame stripped):
```
0b cb 6e 69 00  60 0c a7 04        server_time=0x00696ecb,
                                    client_time=0xa7040c60
0b b8 69 2d 00  d7 c8 02 05        server_time=0x002d69b8,
                                    client_time=0x0502c8d7
```

The {@code server_time} field advances ~3000 per 3 s ping (milliseconds)
and stays in the {@code 0x0..0x1f000000} range — consistent with the
6-day-cycle {@code Timer.getIngametime()} encoding.

## Variants

Two reply forms, both inside a `0x13` frame and both echoing the
client_time correctly:

- **9 B** `[0b][server_time LE4][client_time LE4]` — the normal reply.
- **13 B** `[0b][server_time LE4][client_time LE4][A LE2][B LE2]` — on
  every 20th reply (~60 s; the count restarts with a new UDP session:
  fire_multikill_v1 re-keyed at 30.6 s → first 13 B reply at 88.5 s).
  33 of 884 retail replies. `A` = 2774 (0x0ad6) in every sample. `B`
  grows slowly across days and within sessions: 5 (2026-05-30),
  17 (05-31), 73/74/77 (06-06), 141→142→143 (06-13; 142→143 mid-session
  in fire_bc_v1 at t≈121 s). Meaning UNKNOWN (not hours of uptime) —
  needs the client's 0x0b reply parser. **Ceres-J does not emit this
  form yet** (do not invent `A`/`B`).

History: commit `bbf3b35` (2026-05-09) switched Ceres-J to a bare
9-byte transport datagram after reading HANNIBAL through PcapReplay,
which unwraps `0x13` frames into their inner sub-packets — so the
retail record only looked "raw". Corrected 2026-10-04 on
`track/transport-liveness` against the client-side traces above.

## Observed contexts

One reply per C→S CPing. The client pings every ~3.0 s; retail answers
~0.2 s later (retail_draw_v3: 2.534→2.724, 5.542→5.742, 8.559→8.809).

## Open questions (resolved)

- The client_time echo DOES equal the preceding C→S CPing's
  client_time: 12/12 pairs in retail_draw_v3 + fire_retail_v1
  (e.g. C→S `0b 34c29502` → S→C `… 34c29502`). The 2026-05-09
  mismatch came from PcapReplay mis-pairing replies, not from
  server-initiated pings.

## Server-side handler

`server.gameserver.packets.server_udp.SPing` — extends
{@code PacketBuilderUDP13}; fired from
`server.gameserver.packets.client_udp.CPing.execute()` on every
client keepalive.

Tests:
- `SPingByteIdentityTest` — pins the 16-byte `0x13` frame (counter
  not consumed, counter+key, sub_len=9), the client_time echo at
  sub-packet bytes 5..8 LE32, and server_time within
  {@code Timer.getIngametime()} range.

<!-- constants-audit (generated by tools/constants_audit.py --inject; edit kinds in _data/constants.json) -->
## Constants (catalog-audit)

| Field | kind | note |
|---|---|---|
| `[0] 0x0b sub-opcode (constant)` | discriminator |  |
<!-- /constants-audit -->
