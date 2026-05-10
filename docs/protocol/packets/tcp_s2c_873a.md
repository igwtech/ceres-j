# `TCP S->C 0x873a` — Gamedata

**Transport:** TCP  
**Direction:** S->C  
**Identifier:** `0x873a`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **12**
- Captures with this packet: **6/17**
- Size (bytes): min **2**, avg **2**, max **2**
- Per-capture counts:
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 2
  - `RETAIL_ODA_20260426_202428` × 2
  - `RETAIL_AUGUSTO_20260426_201952` × 2
  - `RETAIL_HANNIBAL_20260426_201501` × 2
  - `RETAIL_NORMAN_20260426_200458` × 2
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 2

Samples (first 32 bytes inner data):

```
#1: 873a
```
```
#2: 873a
```
```
#3: 873a
```

<!-- /catalog-evidence -->

## Structure

TCP S→C 0x873a — `Gamedata`. Server's 2-byte ack to the
client's `GetGamedata` (0x8737) request. Fixed 2-byte body,
pure opcode. Verified 2026-05-10 against all 12 retail samples
from 6/17 captures.

```
[0..1]   87 3a                  TCP opcode (constant)
```

Wire framing: `fe 02 00 87 3a` (3-byte prefix + 2-byte body)
= 5 bytes total on the wire.

All 12 observations are byte-identical: `87 3a`. NO content
variation.

## Variants

Single 2-byte form. Pure constant signal.

## Observed contexts

Emitted by the server in direct response to `GetGamedata`
(0x8737). Always paired 1:1 — 12 emissions for 12 client
requests.

This packet is the FIRST of the world-entry response burst,
followed by `UDPServerData` (0x8305) + `Packet830D` (0x830d)
+ `Location` (0x830c) in the same TCP segment.

## Open questions

- Why is this packet so minimal (just the opcode)? Most server
  acks carry at least a status byte. Possibly `0x873a` is a
  pure "I received your GetGamedata, more data follows"
  signal — relying on the next bundled packet for content.

## Server-side handler

`server.gameserver.packets.server_tcp.Gamedata` — emits the
2-byte constant body. Trivial constructor:

```java
public Gamedata() {
    write(0x87);
    write(0x3a);
}
```

Wired from `GetGamedata.GetGamedataAnswer.execute()` as the
first packet in the world-entry response sequence (after a
200ms `DummyEvent` delay). See `tcp_c2s_8737.md` for the full
sequencing constraint.

