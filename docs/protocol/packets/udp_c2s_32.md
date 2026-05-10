# `UDP C->S 0x32` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x32`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **2156**
- Captures with this packet: **7/17**
- Size (bytes): min **9**, avg **17**, max **21**
- Top markers (within ±2s):
  - AT_DEST_SUBWAY_CAR × 10
  - IN_SUBWAY_CAR × 6
  - LEAVE_SUBWAY_CAR × 4
  - ENTER_SUBWAY_CAR × 3
  - NPC_TALK × 3
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 1832
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 98
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 79
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 79
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 36
  - `RETAIL_HANNIBAL_20260426_201501` × 18
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 14

Samples (first 32 bytes inner data):

```
#1: 32fb03000000c84100
```
```
#2: 32fc03000000000000
```
```
#3: 32fb03000000c84100
```

<!-- /catalog-evidence -->

## Structure

**Same wire format as `0x03/0x32`** — see
`udp_c2s_03_32.md` for the verified body layout. The catalog
double-indexes 0x32 packets under both `udp_c2s_32.md` (raw
view) and `udp_c2s_03_32.md` (full reliable form). The body
bytes are identical.

This is the **C→S complement** of `udp_s2c_32.md` (subway/
transit). When the client travels via subway, it both
RECEIVES authoritative subway state (S→C 0x32) AND EMITS its
own observed subway state back (C→S 0x32) — likely a sync /
"I'm aware of subway X at state Y" feedback channel.

```
[0]      0x32                    sub-opcode
[1..2]   subway_entity LE16      0x03fb..0x03fc range (subway IDs)
[3..5]   3-byte zero pad         CONSTANT 0x000000
[6..9]   speed LE32 float        clean discrete values (0, 25.0)
                                  e.g. 0x4441c800 = 1576.0 (km/h ≪ 1)
                                  or zero
```

Sample retail bytes:
```
32 fb 03 00 00 00 c8 41 00     entity=0x03fb, speed_float ≈ 25.125
32 fc 03 00 00 00 00 00 00     entity=0x03fc, speed=0 (stopped)
32 fb 03 00 00 00 c8 41 00     repeat — periodic emission
```

## Variants

Body sizes 9-21B (catalog says min=9, avg=17, max=21). The 9B
form is the dominant subway-position report; longer variants
(17B, 21B) likely carry additional movement deltas or
passenger-list state. Sample analysis for the longer forms is
pending.

## Observed contexts

Top markers tightly correlate with subway events:
- `AT_DEST_SUBWAY_CAR` × 10
- `IN_SUBWAY_CAR` × 6
- `LEAVE_SUBWAY_CAR` × 4
- `ENTER_SUBWAY_CAR` × 3

Concentrated in `RETAIL_VEHICLE_DRONE` (1,832 emissions —
likely the vehicle/drone code path treats them as transit
entities) and `RETAIL_CHARDEL_SUBWAY` (98 emissions — explicit
subway capture). Most retail captures don't touch subway and
emit 0 of these.

## Open questions

- Why does the client EMIT subway state? Most multiplayer
  designs make subway authoritative on the server. The C→S
  emission may be for client-side prediction reconciliation
  ("here's what I see; correct me if wrong").
- 17B / 21B longer variants: not yet sampled. Likely
  additional fields (rotation, passenger list, door states).
- Sub-actions / variants of byte[3]: catalog samples show 0x00
  dominant — full enum unknown.

## Server-side handler

`server.gameserver.subtagrouter.SubtagRouter` — recognises
0x32 as subway-related but does not emit. The C→S variant is
consumed (not silently dropped) but body content is currently
ignored.

For full retail parity:
1. Decode subway_entity + speed + state.
2. Reconcile against server-authoritative subway position
   (when implemented).
3. Optionally echo a corrected S→C 0x32 broadcast if the
   client's reported state diverges.

See `udp_c2s_03_32.md` for the full reliable-channel variant
and `udp_s2c_03_32.md` for the S→C complement.

