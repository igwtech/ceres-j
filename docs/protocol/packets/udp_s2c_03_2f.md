# `UDP S->C 0x03/0x2f` — Reliable/UpdateModel

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x03/0x2f`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **351**
- Captures with this packet: **14/17**
- Size (bytes): min **5**, avg **20**, max **102**
- Top markers (within ±2s):
  - MOB_DEAGGRO × 2
  - KILL_MOB2 × 2
  - GENREP_OPEN_PICK × 1
  - KILL_MOB × 1
  - SKILLS × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 105
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 83
  - `RETAIL_NORMAN_20260426_200458` × 42
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 33
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 33
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 16
  - `RETAIL_ODA_20260426_202428` × 15
  - `RETAIL_HANNIBAL_20260426_201501` × 8
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 4
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 3
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 3
  - `RETAIL_DRSTONE_20260501_172522` × 3
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 2
  - `RETAIL_AUGUSTO_20260426_201952` × 1

Samples (first 32 bytes inner data):

```
#1: 01000200330002014802023fe002031002040602058f02061702071002080502
```
```
#2: 01000200330002014802023fe002031002040602058f02061702071002080502
```
```
#3: 0100020025000201350205d6020621020708020804020a2201020b1300020d05
```

<!-- /catalog-evidence -->

## Structure

UpdateModel — character appearance / model state broadcast.
Verified 2026-05-10 against 39 samples from 17/17 retail captures.

Variable-length body (6-80 B observed). The long form (66-80 B)
contains the full character info including ASCII name; short
forms (6-17 B) are partial-update deltas.

```
[0]      0x2f                   sub-opcode (constant)
[1]      0x01 / 0x02            major variant (0x01 dominant)
[2]      0x00                   CONSTANT
[3]      type/sub-variant        0x01 (full character) /
                                 0x02 (partial update)
[4..]    sequence of TLV-like fields with `02 [tag] [val]`
         pattern, terminated by `03 03 00` + ASCII name + null
         + `03 03 00` trailer
```

### Long-form sample (HANNIBAL, 80B):

```
2f 01 00 01 00 20 02 00 31 00 02 01 48 02 02 3e
e0 02 03 10 02 04 06 02 05 8a 02 06 12 02 07 0f
02 08 0a 02 0d 2b 04 03 00 0f f8 02 02 01 01 00
00 00 00 00 00 5d 03 c1 03 03 01 11
48 61 6e 6e 69 62 61 6c 20 4c 65 63 74 75 72 65   "Hannibal Lecture"
00 03 03 00
```

The TLV-like sequence `02 [tag] [val]` repeats with various
tags (0x01..0x0e) — each carrying 1-2 bytes of character
attribute data. The trailer ASCII length-prefix is at byte
just before the name (`11 0x` = 17 bytes of "Hannibal Lecture\0"
including null).

## Variants

| size  | use                                               |
|------:|---------------------------------------------------|
| 6 B   | minimal partial update                            |
| 7-17 B | small attribute deltas                           |
| 66-80 B | full character broadcast (with ASCII name)      |

ASCII names extracted from full-form samples:
- "Hannibal Lecture" (HANNIBAL)
- "Oda Daramitz" (ODA)
- "Dra Moni" (PLAZA)
- "Drstone" (DRSTONE)
- "Norman Gates" (ZONING / RETAIL)
- "Krafteo" (FULL_PCAP_TRACE)

These are PLAYER character names — UpdateModel is the per-player
appearance broadcast.

## Observed contexts

Top markers correlate with player-visibility events (other player
walks into view, model change, equipment swap). Most heavily
emitted in long-session captures (RETAIL_VEHICLE_DRONE etc.).

## Open questions

- TLV tag enum (0x01..0x0e): which tags map to which character
  attributes? Some are clearly same-position fields (HP, PSI,
  STA, class, faction, model_head, etc.) but the mapping isn't
  yet pinned per-tag.
- The leading `02 [tag] [val]` pattern is consistent — TLV
  format. Decoding the tag→meaning mapping would unlock full
  byte-pinning.
- Variant 0x02 (rare — RETAIL has it as 78B): differences from
  0x01 not yet characterized.

## Server-side handler

Ceres-J has {@link
server.gameserver.packets.server_udp.InitUpdateModel02} (0x02
init-burst variant), but no S→C 0x03/0x2f reliable emitter for
runtime model changes (equipment swaps, faction-color updates,
etc.). The S→C 0x03/0x2f path would need a per-event broadcaster
to match retail's runtime model-update behavior.

The ASCII name decode is straightforward — server can extract
the player's name from PlayerCharacter.getName() when emitting.

## Cross-player EQUIP broadcast — CONFIRMED 2026-06-06 (two real clients)

`0x03/0x2f` is the **cross-player weapon/equip visual broadcast**: when a player
draws/holsters a quickbelt item, the server sends nearby clients a `0x2f` delta
so they render the item on that player. Captured on retail with a real Frida
observer (Oda) watching Krafteo draw/holster a pistol and a flashlight
(`../../../tools/frida_re/runs/obs1/trace.jsonl`). See [[equip_broadcast_2f_confirmed]].

Delta form (observer view), addressed by the **subject's runtime entity id**
(NOT the actor's self localId `0x0001`; the `0x30` ShortPlayer registration maps
runtime_id → char_id → name; Krafteo = entity `0x0002`):

```
2f [entity LE16] (02 [tag:1] [value LE16])*
   tag 0x02 = model IN HAND   (defs.weapons.id, or 0x0000 = holstered)
   tag 0x0a = belt slot A model  (0xffff when that item is in hand)
   tag 0x0b = belt slot B model
   (draw also carries 02 03 / 02 04 sub-records — barrel/attachment sub-models,
    not yet fully field-mapped; one weapon captured)
```

DRAW moves the id belt→hand (`tag02 = def id`, slot tag = `ffff`); HOLSTER moves
it hand→belt (`tag02 = 0000`, slot tag = def id). The in-hand value is a
**`defs.weapons.id`** → resolves the item (`290` = "Lazar Gun TL 2" pistol,
`60` = "Halogen Flashlight TL 4").

Captured Krafteo (entity `0x0002`):
```
draw pistol      02 02 2201 02 03 8902 02 04 3602 02 0a ffff   in-hand=0x0122
holster pistol   02 02 0000 02 0a 2201                          belt 0a=0x0122
draw flashlight  02 02 3c00 02 03 0e02 02 04 0602 02 0b ffff   in-hand=0x003c
holster flashl.  02 02 0000 02 0b 3c00                          belt 0b=0x003c
```

Ceres-J impl: `EquipHolster` already sends the SELF UpdateModel (localId 1); the
**nearby-player broadcast** is `Zone.sendPlayerEquip` → `UpdateModel(entityId,…)`
addressed by `pl.getMapID()` to every other receiver (same loop as
`sendPlayerMovement`).

<!-- constants-audit (generated by tools/constants_audit.py --inject; edit kinds in _data/constants.json) -->
## Constants (catalog-audit)

| Field | kind | note |
|---|---|---|
| `[0] 0x2f sub-opcode (constant)` | discriminator |  |
| `[2] 0x00 CONSTANT` | padding |  |
<!-- /constants-audit -->
