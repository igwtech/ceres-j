# `UDP C->S 0x20` — Movement

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x20`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **179481**
- Captures with this packet: **17/17**
- Size (bytes): min **5**, avg **17**, max **29**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_TRADING_PLAYER × 77
  - WALK_TO_PEPPERPARK_1 × 54
  - KILL_MOB2 × 52
  - MOB_AGGRO × 49
  - KILL_MOB × 39
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 69984
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 43123
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 16528
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 12281
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 12251
  - `RETAIL_NORMAN_20260426_200458` × 6557
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 4901
  - `RETAIL_ODA_20260426_202428` × 3035
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 2608
  - `RETAIL_HANNIBAL_20260426_201501` × 2435
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 2059
  - `RETAIL_AUGUSTO_20260426_201952` × 1308
  - `RETAIL_DRSTONE4_20260501_193336` × 1059
  - `RETAIL_DRSTONE_20260501_175315` × 577
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 342
  - `RETAIL_DRSTONE_20260501_172522` × 258
  - `RETAIL_DRSTONE3_20260501_181349` × 175

Samples (first 32 bytes inner data):

```
#1: 2004007f7ea90dc5bb055fc3647d2b4400000000000000000000000020
```
```
#2: 2004007f7ea90dc566e65fc3647d2b4400000000000000000000000020
```
```
#3: 2004007f7ea90dc566e65fc3647d2b4400000000000000000000000020
```

<!-- /catalog-evidence -->

## Structure

```
Offset  Size  Field          Notes
0x00    1     opcode         = 0x20
0x01    1     ?              skip(3) in Ceres-J handler
0x02    1     ?              "
0x03    1     type bitfield  set bits select which fields follow
0x04    var   payload        each set bit in `type` adds a field
                             in this order:
```

**Field order (gated by bits in `type`):**

| Bit  | Mask | Size | Field           | Encoding                        |
|------|------|------|-----------------|---------------------------------|
| 0    | 0x01 | 2    | Y               | `LE16 - 32000`                  |
| 1    | 0x02 | 2    | Z               | `LE16 - 32000`                  |
| 2    | 0x04 | 2    | X               | `LE16 - 32000`                  |
| 3    | 0x08 | 1    | tilt            | `0xd6` up · `0x80` mid · `0x2a` down |
| 4    | 0x10 | 1    | orientation     | south=0, east=45, north=90, west=135 (units of 1°/0xff?) |
| 5    | 0x20 | 1    | status          | bitmask: 0x02 kneel · 0x08 left-step · 0x10 right-step · 0x20 walking · 0x40 forward · 0x80 backward |
| 6    | 0x40 | ?    | unknown         | observed; payload format not yet decoded |
| 7    | 0x80 | 4    | parent_entity_id | LE4 — parent entity (chair, subway car, vehicle). Verified 2026-05-03 from SUBWAY capture. |

**Bit 6 (`0x40`) is confirmed UNUSED** as of 2026-05-03 — even
vehicle and drone scenarios don't set it. Reserved for future
features or unused entirely.

**Byte 1 (`entity class`):**

| Value | Class |
|---|---|
| `0x01` | Self / human player (C→S only) |
| `0x02` | Other player / mob (S→C broadcast) |
| `0x03` | Drone (C→S during pilot mode, S→C broadcast) |
| `0x06` | Vehicle / drivable object (S→C broadcast) |
| `0x08` | NPC (rare) |

The class byte affects the encoding of position fields:
- `0x01`/`0x02` (humanoids): **LE16 - 32000** quantized coords
- `0x03`/`0x06` (vehicle/drone): **LE32 IEEE-754 floats** (full 3D precision)

The min observed size in the corpus is **5 bytes** (header + type
byte + minimal payload, e.g. `type=0x20` status-only update). Max
observed is **29 bytes**, consistent with all bits set + the
unknown bit-6 field.

## Variants

The packet is a single shape; "variants" are the different bitmask
combinations of `type`. The most common combinations across the
corpus would be derivable by parsing all 36 941 samples; not yet
done.

## Observed contexts

Fires every game tick the client thinks the player has moved or
turned. Marker correlation shows it tracks normal play in every
capture (peaks during `WALK_TO_PEPPERPARK_*` and combat markers).
The packet is the primary "I am moving / facing here" stream.

## Open questions

- ~~**Bit 6 (`0x40`) payload format.**~~ **CLOSED 2026-05-03:**
  bit 6 is unused even in vehicle/drone scenarios. Vehicle
  piloting uses bit 7 (parent-entity). Drone piloting uses a
  separate `0x03/0x2d` 41B control channel.
- **Bytes 0x01–0x02 (`skip(3)`).** Might be a sequence number,
  client tick, or padding. The first 32B of every sample begins
  `20 04 00 …` so byte[1]=0x04, byte[2]=0x00 is the typical
  observation; whether the `0x04` ever varies needs checking.
- **Orientation units.** Comment says `0-45-90-135-180` for cardinal
  directions but the field is 1 byte — that suggests `degrees / 2`
  or 8-bit fixed-point, not raw degrees. Needs cross-checking with
  the world coordinate system.

## Server-side handler

Implemented at
[`server/gameserver/packets/client_udp/Movement.java`](../../../src/main/java/server/gameserver/packets/client_udp/Movement.java).
The handler also performs server-side zone-boundary detection — see
[`flows/zone_walk_same_district.md`](../flows/zone_walk_same_district.md).

