# `UDP C->S 0x07` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x07`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **5**
- Captures with this packet: **5/17**
- Size (bytes): min **1012**, avg **1012**, max **1012**
- Per-capture counts:
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 1
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 1
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 1
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 1
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 1

Samples (first 32 bytes inner data):

```
#1: 070000090000000107200000461a7f01000001736372697074735c6c75615c73
```
```
#2: 07000009000000010720000046b0a200000001736372697074735c6c75615c73
```
```
#3: 07000009000000060720000046b0a200000001736372697074735c6c75615c73
```

<!-- /catalog-evidence -->

## Structure

UDP C→S raw 0x07 — **multipart-fragment carrier (unreliable)**.
Fixed 1012-byte body across all 5 retail samples from 5/17
captures. This is the LARGE multipart variant of the same Lua
scenario reference channel seen elsewhere as
`udp_c2s_03_00` (240B) and the unreliable
`udp_c2s_00→0x07` (211/231B).

```
[0]      0x07                    sub-opcode (multipart marker)
[1..2]   00 00                   CONSTANT
[3..6]   discriminator+state     `09 00 00 00` —
                                  see multipart_framing.md
[7..10]  hdr LE32                `[01|02|06] 07 20 00`
[11]     0x00                    CONSTANT
[12]     flag                    0x46 OR 0x00
[13..16] char_uid LE32           player UID (e.g. 0x00017f1a,
                                  0x0000a2b0)
[17..]   payload                 ASCII Lua script reference path
                                  (`scripts\lua\scenario\…`)
                                  zero-padded to 1012B total
```

Verified samples (all share the Lua scenario reference
pattern):
```
07 00 00 09 00 00 00 01 07 20 00 00 46 1a 7f 01 00 00 01 [scripts\lua\…]
   CHARDEL_SUBWAY — char_uid=0x00017f1a, hdr_byte=0x01

07 00 00 09 00 00 00 01 07 20 00 00 46 b0 a2 00 00 00 01 [scripts\lua\…]
   LONG_PARTY_A — char_uid=0x0000a2b0, hdr_byte=0x01

07 00 00 09 00 00 00 06 07 20 00 00 46 b0 a2 00 00 00 01 [scripts\lua\…]
   VEHICLE_DRONE — char_uid=0x0000a2b0, hdr_byte=0x06
```

## Variants

Single 1012-byte form. The 1012B = full multipart fragment
size. Compares to:
- `udp_c2s_03_00` (240B reliable single-payload form)
- `udp_c2s_00 / 0x07` 211B/231B (unreliable smaller-multipart)

The 0x07 raw form represents a LARGE Lua scenario reference
that exceeded the unreliable single-packet threshold (~900B
per `nc2_tutorial_shard_finding.md`).

## Observed contexts

Each capture emits exactly ONE 1012B 0x07 fragment — these
are very rare large-payload events. Concentrated in PvP-heavy
and zoning captures.

The pattern matches the tutorial-shard finding: when the Lua
scenario payload exceeds ~900B, the client switches from
single-packet to multipart fragmentation.

## Open questions

- All 5 retail samples carry Lua script references — is this
  the only payload type that flows through raw 0x07? Or is
  this just our sample bias?
- Why use the UNRELIABLE (raw 0x07) for what looks like
  important state? Possibly because the modern client
  re-derives the state from CharInfo and the 0x07 is just a
  hint / debug / logging channel.

## Server-side handler

**NOT IMPLEMENTED** in Ceres-J. No `case 0x07:` raw handler.
The multipart machinery in
`server.gameserver.packets.GamePacketReaderUDP` only handles
the reliable form (`0x03/0x07`).

For full retail parity:
1. Add raw 0x07 dispatcher to multipart reassembly.
2. Recognise the inner payload as the same Lua-scenario
   reference data as `udp_c2s_03_00`.
3. Treat as fire-and-forget (no S→C response in retail).

**Low priority** parity gap — same rationale as
`udp_c2s_03_00` (the server appears to log/ignore the path
content).

