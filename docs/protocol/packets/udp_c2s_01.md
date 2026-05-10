# `UDP C->S 0x01` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x01`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **2009**
- Captures with this packet: **17/17**
- Size (bytes): min **3**, avg **8**, max **176**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_GENREP_OPEN × 80
  - IN_WORLD × 51
  - AFTER_CHAR_SELECT_WORLDENTRY × 19
  - INVENTORY_MANAGE × 2
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 599
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 227
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 155
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 127
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 116
  - `RETAIL_DRSTONE_20260501_175315` × 93
  - `RETAIL_AUGUSTO_20260426_201952` × 72
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 71
  - `RETAIL_HANNIBAL_20260426_201501` × 70
  - `RETAIL_DRSTONE_20260501_172522` × 68
  - `RETAIL_DRSTONE3_20260501_181349` × 67
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 65
  - `RETAIL_ODA_20260426_202428` × 61
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 60
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 57
  - `RETAIL_DRSTONE4_20260501_193336` × 54
  - `RETAIL_NORMAN_20260426_200458` × 47

Samples (first 32 bytes inner data):

```
#1: 01d18421e221e211a000
```
```
#2: 01d18421e221e211a000
```
```
#3: 01d18421e221e211a000
```

<!-- /catalog-evidence -->

## Structure

C→S {@code 0x01} has TWO distinct variants discriminated by total
size — same opcode, different semantics:

### 10-byte handshake variant

Sent by the client during the UDP 3-way handshake. Body bytes
are CONSTANT across all retail captures:

```
01 d1 84 21 e2 21 e2 11 a0 00
```

Wire layout (10 bytes total):
```
[0]      0x01                   sub-opcode
[1..9]   handshake constant     `d1 84 21 e2 21 e2 11 a0 00`
                                 — appears to be a hardcoded
                                   client-build identifier or
                                   protocol-version magic
```

### 3-byte reliable-ack-request variant

Sent throughout the session to request a reliable retransmit:

```
[0]      0x01                   sub-opcode
[1..2]   seq LE2                target seq the client wants
                                 the server to resend
```

Server responds via the {@link
server.networktools.ReliablePacketRing} → S→C {@code 0x02}-wrapped
retransmit (task #136 / #151).

Observed in retail: 17 ack-requests in 2 rounds within ~1 s
post-handshake (HANNIBAL).

## Variants

| size  | use                                  |
|------:|--------------------------------------|
| 10 B  | UDP handshake — constant body bytes  |
| 3 B   | reliable-ack-request — variable seq  |

Decoder dispatch is size-based:
- ≥ 10 B → `HandshakeUDP` (the 3-way handshake responder)
- < 10 B (i.e. 3 B) → `ReliableAckSubPacket` (retransmit responder)

Verified by `GamePacketReaderUDPRawDispatchTest` (5 tests pinning
the size-discrimination contract).

## Observed contexts

Top markers confirm both use cases — handshake at session start,
ack-requests throughout. The 10 B handshake is fired ONCE per UDP
endpoint binding (multiple times if the client zone-handoffs and
opens a fresh UDP socket).

## Open questions

- Body bytes `d1 84 21 e2 21 e2 11 a0 00` of the handshake variant
  — these look like a UUID or 9-byte client identifier. Constant
  across all retail captures regardless of account/character.
  Likely embedded in the client binary as a hardcoded magic
  ("client build hash" or "protocol version GUID"). Server doesn't
  validate (Ceres-J accepts any 10 B 0x01 as handshake).

## Server-side handler

Dispatched in {@link
server.gameserver.packets.GamePacketReaderUDP#decode}:

```
case 0x01:
    if (dp.getLength() >= 10) {
        return new HandshakeUDP(dp);
    }
    return new ReliableAckSubPacket(...);
```

Tests:
- `GamePacketReaderUDPRawDispatchTest` (5 tests) — pins
  size-based dispatch + LE16 ack-seq decode.
- `ReliableAckSubPacketTest` (5 tests) — pins the retransmit
  responder behavior.

