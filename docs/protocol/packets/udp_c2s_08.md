# `UDP C->S 0x08` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x08`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **28**
- Captures with this packet: **16/17**
- Size (bytes): min **1**, avg **18**, max **70**
- Top markers (within ±2s):
  - EXIT_WORLD × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 6
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 4
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 4
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 2
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 1
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 1
  - `RETAIL_ODA_20260426_202428` × 1
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 1
  - `RETAIL_AUGUSTO_20260426_201952` × 1
  - `RETAIL_DRSTONE4_20260501_193336` × 1
  - `RETAIL_DRSTONE3_20260501_181349` × 1
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 1
  - `RETAIL_DRSTONE_20260501_172522` × 1
  - `RETAIL_HANNIBAL_20260426_201501` × 1
  - `RETAIL_NORMAN_20260426_200458` × 1
  - `RETAIL_DRSTONE_20260501_175315` × 1

Samples (first 32 bytes inner data):

```
#1: 08
```
```
#2: 08
```
```
#3: 08
```

<!-- /catalog-evidence -->

## Structure

C→S raw 0x08 — TWO distinct forms verified 2026-05-10:

```
1-byte form (21/28 samples — 16/17 captures):
  [0]   0x08                bare-opcode disconnect/logout signal

70-byte form (7/28 samples — only LONG_PARTY_A/B + VEHICLE_DRONE):
  Compact reliable-burst (0x13-less). Format:
  [len LE2][sub_data][len LE2][sub_data]…
  Each sub_data is a `[03][seq LE2][rt][body]` reliable sub-packet
  or a `[20][seq LE2][movement body]` raw movement.
```

The 70-byte form's leading byte `0x08` is a coincidence — it is
the LOW byte of the first sub-packet's `len LE16 = 0x0008`. The
catalog folds these under "outer=0x08" because byte[0] happens
to be 0x08, but they are NOT the same packet semantically as
the 1-byte form.

Verified 70-byte sample structure (LONG_PARTY_A):
```
08 00  03 01 00 1f 01 00 25 22         len=8, sub: [03][seq=1][1f][body 4B]
08 00  03 02 00 1f 01 00 25 22         len=8, sub: [03][seq=2][1f][body 4B]
0b 00  03 03 00 1f 01 00 4c 0f 00 03 00 len=11, sub: [03][seq=3][1f][body 7B]
1d 00  20 01 00 [movement 26B]          len=29, sub: [20][seq=1][movement]
0b 00  03 04 00 1f [...]                len=11, partial / next sub
```

## Variants

- **1-byte form** (21/28): bare `08` byte. EXIT_WORLD marker
  correlates with this — likely a logout / disconnect signal
  the client emits as it tears down the UDP socket. Byte-stable
  across all 21 samples (no body content).

- **70-byte form** (7/28): not actually "raw 0x08" — these are
  0x13-less compact bursts. Catalog mis-attribution. The content
  is the standard reliable + movement sub-packet format.

## Observed contexts

The 1-byte form's top marker `EXIT_WORLD` strongly suggests
session-teardown. The client emits a single `08` byte as a
goodbye signal before closing its UDP socket.

The 70-byte form is concentrated in the PvP-heavy captures
(LONG_PARTY_A/B = 4 each, VEHICLE_DRONE = 6) — these
compact-burst forms appear specific to high-throughput combat
sequences where 0x13 wrapper overhead is undesirable.

## Open questions

- Why does the modern client sometimes emit 0x13-less bursts?
  The 0x13 wrapper carries `counter` + `counter_key` fields
  (4 bytes overhead) — possibly omitted under bandwidth
  pressure during PvP, with the server inferring counter from
  the per-sub seq fields.
- Is the 1-byte `08` form acknowledged by the server, or does
  the server simply observe socket close?

## Server-side handler

**1-byte form**: Currently consumed as no-op by
`GamePacketReaderUDP` (catch-all for unknown bytes). The
client's UDP socket may close shortly after this, so server
should treat it as a hint to begin session teardown.

**70-byte form**: parsed normally by the per-sub-packet
dispatcher since each sub_data carries its own `[03][seq][rt]`
header. The 0x13-less framing is transparent to existing
handlers.

For full retail parity, the 1-byte 0x08 form should trigger:
1. Save player state (CharInfo, position, equipped gear).
2. Emit any pending broadcast events (player leaves zone).
3. Begin UDP-socket cleanup grace period.

This is currently a **low-priority parity gap** since the
session also tears down on TCP disconnect (which Ceres-J
handles), so the 1-byte 0x08 is essentially redundant.

