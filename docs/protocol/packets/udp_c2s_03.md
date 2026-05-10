# `UDP C->S 0x03` — Reliable

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x03`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **182**
- Captures with this packet: **17/17**
- Size (bytes): min **8**, avg **8**, max **8**
- Top markers (within ±2s):
  - IN_WORLD × 5
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
#1: 03b0a20000042b90
```
```
#2: 03b0a20000042b90
```
```
#3: 03b0a20000042b90
```

<!-- /catalog-evidence -->

## Structure

Raw C→S {@code 0x03} — handshake-ack echoing UDPAlive parameters
back to the server. Verified 2026-05-10 against 51 cross-pcap
samples from 17/17 retail captures. Fixed 8-byte body:

```
[0]      0x03                   opcode (constant)
[1..4]   char_uid LE32           player's character UID (matches
                                  the value in C→S 0x2a / 0x03/0x22
                                  Zoning1)
[5]      interface_byte          0x01 dominant; 0x02 in
                                  DRSTONE_post-zoning; 0x04 in PLAZA
                                  — same enum as UDPAlive's
                                  byte[2] interfaceId
[6..7]   server_port LE16        echoed from S→C UDPAlive's
                                  bytes[5..6] (e.g. 0x902b in PLAZA,
                                  0x8c79 in AUGUSTO, 0x86fc in CASH)
```

Sample retail bytes:
```
03 bd 7e 01 00 01 79 8c     AUGUSTO  port=0x8c79=35961 (echoes UDPAlive)
03 bd 7e 01 00 01 fc 86     CASH     port=0x86fc=34556
03 b0 a2 00 00 04 2b 90     PLAZA    interface=0x04, port=0x902b
```

Within a session bytes 1..7 are byte-stable — same handshake-ack
content emitted multiple times (presumably one ack per
handshake-burst UDPAlive received).

## Variants

Single 8-byte form across all 182 retail observations. The
state byte at [5] varies (0x01/0x02/0x04) matching UDPAlive's
zone-interface enum.

## Observed contexts

Emitted post-handshake — client confirms it received the
server's UDPAlive 4× burst by echoing back the
{@code (char_uid, interface, port)} tuple. The server uses this
ack to confirm the client's UDP socket is bound and the session
is established.

This packet is the COMPLEMENT of S→C UDPAlive (0x04) — both
share the same `(char_uid LE32, interface_byte, port LE16)`
payload content (just different opcode and field order).

## Open questions

- Does the server need to validate the echoed values match its
  emitted UDPAlive? Empirically Ceres-J doesn't validate — the
  C→S 0x03 raw is recognised but body content unparsed.

## Server-side handler

Decoded via {@code GamePacketReaderUDP} `case 0x03:` — but the
8-byte raw form (vs the typical 0x13-wrapped reliable) is
treated as a no-op recognition. The session validation is
implicit (any handshake-correct UDP socket is accepted; ack
content isn't verified).

If a future stricter handshake validation requires it, the
handler should compare bytes [1..4] against the player's
char_uid and bytes [6..7] against the server's expected port.

