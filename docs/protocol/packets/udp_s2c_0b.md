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

The server's reply to a {@code C→S 0x0b CPing} client keepalive.
Fixed 9-byte raw datagram (NO 0x13 outer wrapper — verified via
SPingByteIdentityTest 2026-05-09).

```
[0]      0x0b                    sub-opcode (constant)
[1..4]   server_time LE32         Timer.getIngametime() — 6-day
                                   cycle modulo encoding (max ~518.4M ms)
[5..8]   client_time echo LE32   the LE32 sent in the C→S 0x0b CPing
```

Sample retail bytes (catalog):
```
0b cb 6e 69 00  60 0c a7 04        server_time=0x00696ecb,
                                    client_time=0xa7040c60
0b b8 69 2d 00  d7 c8 02 05        server_time=0x002d69b8,
                                    client_time=0x0502c8d7
0b 10 83 2d 00  2e e2 02 05
```

The {@code server_time} field is consistently in the
{@code 0x0..0x1f000000} range — confirms the 6-day-cycle
{@code Timer.getIngametime()} encoding (518.4M ms = ~6 days).

## Variants

Single 9-byte form across 4,939 observations in 17/17 captures.
NO size variants observed — fixed wire form.

The earlier hypothesis that SPing was 0x13-wrapped (16 bytes) was
WRONG and produced an actual client-handling regression — fixed
2026-05-09 by reverting to raw 9 B (commit `41b661e`).

## Observed contexts

Top markers correlate with periodic CPing exchanges throughout
the session. ~1 SPing emitted per CPing received; cadence is
client-driven (~1 Hz typical).

## Open questions

- The {@code client_time echo} field in this S→C 0x0b doesn't
  always equal the most-recent C→S CPing's {@code client_time}.
  Possible causes investigated 2026-05-09 (see HANNIBAL replay
  21-divergence cluster in PcapReplayTest):
  - Retail emits server-initiated 0x0b pings (not just replies),
    so byte[5..8] in those is NOT a "client_time echo" but a
    server-side session token.
  - The harness masks bytes 1..8 entirely as session-derived to
    avoid false divergences.
- No definitive resolution yet — pin layout via SPingByteIdentityTest
  rather than trusting harness echo equality.

## Server-side handler

`server.gameserver.packets.server_udp.SPing` — extends
{@code PacketBuilderUDP} (NOT UDP13). Emits the verified 9-byte
raw form. Fired from
`server.gameserver.packets.client_udp.CPing.execute()` on every
client keepalive.

Tests:
- `SPingByteIdentityTest` (5 tests) — pins client_time echo at
  bytes 5..8 LE32, server_time within {@code Timer.getIngametime()}
  range, total 9-byte size, opcode constant 0x0b.

