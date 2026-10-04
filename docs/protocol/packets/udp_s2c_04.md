# `UDP S->C 0x04` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x04`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **182**
- Captures with this packet: **17/17**
- Size (bytes): min **7**, avg **7**, max **7**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_GENREP_TELE × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 50
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 18
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 15
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 13
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 10
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 9
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 9
  - `RETAIL_HANNIBAL_20260426_201501` × 8
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 7
  - `RETAIL_ODA_20260426_202428` × 7
  - `RETAIL_NORMAN_20260426_200458` × 6
  - `RETAIL_AUGUSTO_20260426_201952` × 5
  - `RETAIL_DRSTONE4_20260501_193336` × 5
  - `RETAIL_DRSTONE3_20260501_181349` × 5
  - `RETAIL_DRSTONE_20260501_172522` × 5
  - `RETAIL_DRSTONE_20260501_175315` × 5
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 5

Samples (first 32 bytes inner data):

```
#1: 04040011322b90
```
```
#2: 04040011322b90
```
```
#3: 04040011322b90
```

<!-- /catalog-evidence -->

## Structure

UDPAlive — UDP keepalive emitted by the server. Verified
2026-05-10 against 51 samples from 17/17 retail captures.
Fixed 7-byte raw datagram (NO 0x13 outer wrapper).

```
[0]      0x04                   sub-opcode (constant)
[1]      mapID                  player's world-object id (low byte;
                                 typically 1)
[2]      interfaceId             zone interface (typically 0x00;
                                 0x02 in DRSTONE_post-zoning; 0x04
                                 in PLAZA — a nested-zone marker)
[3..4]   -sessionkey LE16        negated UDP session key (per
                                 session, random per server boot)
[5..6]   server_port LE16        UDP port the server listens on
                                 (per-session ephemeral port)
```

Sample retail bytes:
```
04 01 00 54 20 79 8c    AUGUSTO  port=0x8c79=35961
04 01 00 ca 04 fc 86    CASH     port=0x86fc=34556
04 04 00 11 32 2b 90    PLAZA    interface=0x04, port=0x902b=36907
04 02 00 01 77 40 da    DRSTONE  interface=0x02, port=0xda40=55872
```

## Variants

Single 7-byte form. Within one UDP session every UDPAlive is
byte-identical. A NEW session (re-key) changes `[3..4]` (and may change
`[1]`/`[2]`, e.g. LONG_PARTY_A `04 02 00 5a2f …` at t=128 s).

## Observed contexts

The client answers each UDPAlive with a transport `0x03`
([`udp_c2s_03`](udp_c2s_03.md)) within ~1 ms. The client treats every
`0x04` as "new UDP session" and restarts its reliable sequence at 1
after answering. Three emit cases, verified across all 25 retail pcaps
(251 S→C `0x04`) and 39 retail harness traces:

1. **Handshake reply** — 1–4× within ~1 ms of the client's 3× `0x01`
   connect.
2. **Retry while unanswered** — the same bytes re-sent 3.0–3.6 s
   later, repeated until the client's `0x03` arrives (HANNIBAL: client
   loading until t=6.56 s → retries at 3.42 s and 6.67 s; LONG_PARTY_A
   retries at 3.55/100.29/131.76/388.48 s). Never re-sent after the
   answer, except a retry already racing it (≤0.12 s).
3. **Re-key** — a new session key mid-session (zone change etc.:
   LONG_PARTY_A t=306/384/454 s, CREATION_LEVELING t=277/444/2469 s,
   fire_multikill_v1 t=30.6 s), with case 2 applying to it.

The old reading "4 periodic UDPAlives at ~3 s per session" (task #158)
was case 2 in HANNIBAL. Ceres-J's unconditional 4-tick heartbeat sent
them after the answer, and the 64-bit client reset its reliable state
each time (wgate64_v3: 428 NAK/retransmit pairs in 43 s). Fixed
2026-10-04 on `track/transport-liveness`.

## Open questions

- Why the {@code -sessionkey} (negated) at body[3..4] instead of
  the raw value? Possibly a client-side NAT-traversal
  validation: the server sends {@code -sessionkey} so the client
  can verify by negating its own copy. Untested hypothesis.
- The interface byte at [2] varies across captures (0x00, 0x02,
  0x04) — probably reflects NC2's zone-interface system (city
  vs sewer vs apartment) but not yet pinned per-zone.
- What triggers a retail re-key besides zone crosses.

## Server-side handler

`server.gameserver.packets.server_udp.UDPAlive` — emits the
verified 7-byte body. Fired from:
- {@link server.gameserver.packets.client_udp.HandshakeUDP} — the
  handshake reply (case 1).
- {@link server.gameserver.internalEvents.UDPAliveHeartbeat} — case 2:
  retries at 3 s only while
  {@code GameServerUDPConnection.isAliveAcked()} is false (set by
  {@code SyncUDP} on the client's `0x03`, cleared on re-key).
- `Zoning2` — case 3, after `resetSessionForZoneCross()`.

The harness {@link
server.testtools.PcapReplayTest#isSpareUDPAlive} skip predicate
exempts retail's retry UDPAlives during replay.

<!-- constants-audit (generated by tools/constants_audit.py --inject; edit kinds in _data/constants.json) -->
## Constants (catalog-audit)

| Field | kind | note |
|---|---|---|
| `[0] 0x04 sub-opcode (constant)` | discriminator |  |
<!-- /constants-audit -->
