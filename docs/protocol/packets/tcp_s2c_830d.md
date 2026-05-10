# `TCP S->C 0x830d` — GameinfoReady

**Transport:** TCP  
**Direction:** S->C  
**Identifier:** `0x830d`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **54**
- Captures with this packet: **16/17**
- Size (bytes): min **4**, avg **4**, max **4**
- Top markers (within ±2s):
  - ZONING_AREAMC5_COMMANDUNIT × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 12
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 7
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 5
  - `RETAIL_ODA_20260426_202428` × 4
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 4
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 4
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 3
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 3
  - `RETAIL_HANNIBAL_20260426_201501` × 3
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 2
  - `RETAIL_NORMAN_20260426_200458` × 2
  - `RETAIL_AUGUSTO_20260426_201952` × 1
  - `RETAIL_DRSTONE4_20260501_193336` × 1
  - `RETAIL_DRSTONE3_20260501_181349` × 1
  - `RETAIL_DRSTONE_20260501_175315` × 1
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 1

Samples (first 32 bytes inner data):

```
#1: 830d0000
```
```
#2: 830d0000
```
```
#3: 830d0000
```

<!-- /catalog-evidence -->

## Structure

TCP S→C 0x830d — `GameinfoReady`. Fixed 4-byte body, all-zero
trailer. Verified 2026-05-10 against samples from 16/17 retail
captures.

```
[0..1]   83 0d                  TCP opcode (constant)
[2..3]   00 00                  CONSTANT padding
```

Wire framing in TCP: `fe 04 00 83 0d 00 00` (3-byte prefix
`[fe][len LE16=4]` + 4-byte body). Total wire size 7 bytes for
the standalone form.

All 54 observations are byte-identical: `83 0d 00 00`. NO
content variation.

## Variants

Single 4-byte form. The packet is most often **bundled** in the
same TCP segment with neighbouring packets:

- After `0x8305 UDPServerData` (28-byte body) — typical
  zone-handoff bundle: `[fe1c00 8305 ...28B...] [fe0400 830d
  0000]` = 38B segment.
- Standalone — `fe 04 00 83 0d 00 00` = 7B segment, emitted
  later in session.
- Bundled with `0x830c Location` — `[fe0400 830d 0000] [fe1d00
  830c ...29B...]` = 70B segment (seen in
  `RETAIL_LONG_PARTY_A/B` zoning to plaza).

Total 6 captures emit the standalone 7B form; all others bundle
it after `0x8305`.

## Observed contexts

Fired immediately after the server has staged the UDP gameserver
session (`0x8305 UDPServerData`) — the client treats `0x830d`
as the "OK, gameinfo is ready, you may begin UDP traffic"
signal. Top marker: `ZONING_AREAMC5_COMMANDUNIT` (zone
transitions trigger this).

When emitted standalone (no `0x8305` companion), it appears to
serve as a "zoning complete" / "gameinfo refreshed" signal —
typically following a zone-change or character switch.

The bundle with `0x830c Location` (zoning to plaza) suggests
the server uses 0x830d as a generic "stage transition complete"
acknowledgement.

## Open questions

None — pure constant signal, fully decoded. The two trailing
zero bytes are likely a reserved-flag field that retail never
sets non-zero.

## Server-side handler

`server.gameserver.packets.server_tcp.Packet830D` — emits the
verified 4-byte constant body `83 0d 00 00`. Implemented and
used in production code:

- `GetGamedata.java:47` — bundled after `UDPServerData` in
  initial world entry.
- `Zoning1.java:65` — emitted on zoning request.
- `Zoning2.java:24` — emitted on zoning ack.
- `Movement.java:95` — emitted from BSP transition path.
- `AuthB.java:123` — emitted in auth-flow tail.
- `AdminCommandHandler.java:214` — emitted via `!zoning`
  admin command.

The opcode constant `ProtocolConstants.TCP_GAMEINFO_READY =
0x830d` is defined but unused — `Packet830D` class hardcodes
its bytes directly, bypassing the constant.

Tests:
- `BytesIdenticalAssertionTest.sliceWireTrimsPacket830DToSevenBytes`
  — pins the `fe 04 00 83 0d 00 00` wire framing (7 bytes
  including TCP framing prefix).
- `Zoning1Test`, `Zoning2Test` — verify `Packet830D` is
  emitted at the right point in zoning flows.

Naming discrepancy: catalog/protocol-doc refers to this as
"GameinfoReady" but the class is named `Packet830D`. The
constant `TCP_GAMEINFO_READY` confirms the intended name.
Future cleanup task: rename `Packet830D` → `GameinfoReady` for
clarity (low priority — wire is correct).

