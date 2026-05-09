# `UDP S->C 0x03/0x2c` — Reliable/StartPos

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x03/0x2c`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **37**
- Captures with this packet: **17/17**
- Size (bytes): min **71**, avg **190**, max **826**
- Top markers (within ±2s):
  - IN_WORLD × 2
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 13
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 3
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 2
  - `RETAIL_DRSTONE4_20260501_193336` × 2
  - `RETAIL_DRSTONE3_20260501_181349` × 2
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 2
  - `RETAIL_DRSTONE_20260501_172522` × 2
  - `RETAIL_DRSTONE_20260501_175315` × 2
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 1
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 1
  - `RETAIL_ODA_20260426_202428` × 1
  - `RETAIL_AUGUSTO_20260426_201952` × 1
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 1
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 1
  - `RETAIL_HANNIBAL_20260426_201501` × 1
  - `RETAIL_NORMAN_20260426_200458` × 1
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 1

Samples (first 32 bytes inner data):

```
#1: 010401a4496c7ea90dc566e65fc3647d2b440000000000000000000000000203
```
```
#2: 010100fc53b4ee59d24233f3ffc314e50545000000000000000000000000d002
```
```
#3: 01010000000155a5074566e67fc3bea57f44000000000000000000000000bc02
```

<!-- /catalog-evidence -->

## Structure

The {@code 0x03/0x2c} opcode is shared between two distinct
variants — discriminated by the first body byte (post sub-op):

```
[0]      0x2c                         sub-opcode (constant)
[1]      variant discriminator        0x01 (PositionUpdate, 75B fixed)
                                       0x02 (CharInfo, 71-826B varying)
[2..]    variant-specific payload
```

### Variant 0x01 — PositionUpdate (75 B, fully decoded)

Byte-pinned structure documented in
`server.gameserver.packets.server_udp.PositionUpdate.java` and
`PositionUpdateByteIdentityTest`. See task #159.

```
[0..2]   2c 01 01                      CONSTANT (4/4 retail captures)
[3..6]   4-byte session state          LE32 entity/zone token
[7..18]  3 LE32 floats                  Y / Z / X player coords
[19..28] 10 zero bytes                  CONSTANT padding
[29..30] LE16 trailer                   session zone hash
[31..]   character model section        head/torso/leg/textures/class
```

### Variant 0x02 — CharInfo single-packet form (71-826 B)

When the player's full character data fits in one UDP datagram
(≤ ~900 B post-encryption + framing), retail emits CharInfo as a
single {@code 0x03/0x2c} variant 0x02 packet rather than a
{@code 0x03/0x07} multipart chain. See
{@code memory/nc2_tutorial_shard_finding.md} for the size
threshold rule.

Body header (DRSTONE3 sample, 811 B):

```
[0..3]   2c 02 01 01                   sub-op + variant + 2 const
[4..5]   0a 00                         section marker / count
[6..7]   fa 23                         LE16 (0x23fa = 9210)
[8..9]   40 00                         LE16
[10..11] ac cc                         LE16 (entity hash bits)
[12..13] 01 00                         LE16 (= 1)
[14..15] 11 00                         section count?
[16..17] 02 1c                         section start
[18..]   CharInfo section data         see CharInfo section system
```

The body content past byte 18 is the same section-based
character-data dump documented in:
- `memory/charinfo_field_positions.md` (HP/PSI/STA/CASH offsets)
- `memory/charinfo_s4_subskill_table.md` (Section 4 subskill layout)

Section markers within the body — e.g. `0x03 0x3c 0x00 0x06 0x09`
sequences — delimit per-section payloads (skills, inventory,
attributes, faction, etc.).

The trailing bytes of the 811B sample contain a repeated header
prefix (`0d 08 00 fa 23 40 00 ac cc 01 00` at byte 800), suggesting
either a packet-end marker or a redundant chain-key for client-side
validation of the single-packet form.

## Variants

| Discriminator | Size      | Use                                |
|--------------:|----------:|------------------------------------|
| 0x01          | 75 B fixed| PositionUpdate (StartPos)          |
| 0x02          | 71-826 B  | CharInfo single-packet (≤ ~900B)   |

When CharInfo exceeds ~900 B, retail emits the {@code 0x03/0x07}
multipart variant instead. The variant discriminator at
{@code body[1]} switches between layouts.

## Observed contexts

Top markers (within ±2s):
- `IN_WORLD × 2` — fires on world-entry character refresh

Per-capture concentration: RETAIL_VEHICLE_DRONE × 13 (most),
suggesting periodic CharInfo refreshes during heavy gameplay
(stat changes, equipment swaps, etc.).

## Open questions

- Variant 0x02 byte[4..17] header detail — section count vs
  section markers vs entity-hash split not fully pinned.
- The trailing repeated header bytes — packet-end marker or
  validation chain-key?
- Is there a sub-variant 0x03 or higher in the wild? Catalog only
  shows the two discriminators.

## Server-side handler

Variant 0x01 (PositionUpdate):
- Builder: `server.gameserver.packets.server_udp.PositionUpdate`
- Tests: `PositionUpdateByteIdentityTest` (4 tests pinning constants
  + float positions + 10B padding)
- Closed task #159.

Variant 0x02 (CharInfo single-packet):
- Builder: `server.gameserver.packets.server_udp.CharInfo`
- Section system: `CharInfo.java` `newSection(...)` + per-section
  data writers
- Tests: `CharInfoContentTest` (subskill content + Section 8 cash)
- Multipart counterpart: `0x03/0x07` (when body > ~900B)
- Open: full per-section byte-pinning vs retail. Test fixture
  emits 423B vs retail's 811B in DRSTONE3 (more character data
  filled in retail). Task #161.

