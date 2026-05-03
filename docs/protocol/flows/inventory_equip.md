# Flow: Inventory drag / equip

**Status:** verified  
**Backing capture:** `RETAIL_CREATION_LEVELING_LONG_20260502_160841`
— markers `BEFORE_EQUIPING_GEAR`, `EQUIPED_RIFLE_BELT_1`,
`EQUIPING_GAUNLET`, `EQUIPED_FLASHLIGHT_BELT_2`,
`EQUIPED_KNIFE_BELT3`, `EQUIPED_ARMOR_CHEST`,
`EQUIPPED_AMMO_SLOP9_OVERENCUMBERED`, `RECYCLING_AMMO`,
`DROPPING_TRASH`.

## Scenario

Player drags items between inventory slots: from inventory grid
to belt/chest/weapon slots, between containers (inventory ↔ box ↔
GoGo dance terminal storage), or drops items on the ground.

## Wire channel — `UDP 0x00 ?` 12-byte fixed

The entire inventory operation suite uses a single 12-byte
fixed-size channel on raw outer `0x00`. Both client and server
emit this opcode; each request/response pair has matching bytes
in well-defined positions.

```
Offset  Size  Field          Notes
0x00    1     0x00           opcode (raw outer, NOT 0x03 reliable)
0x01    1     0x3c           sub-channel = 60 (inventory)
0x02    1     0x01           constant
0x03    1     0x00           constant
0x04    1     op             operation (0x00 .. 0x09 observed)
0x05    4     item/slot LE4  item ID or slot index
0x09    3     trailer        request-specific bytes / checksum
```

### Operation byte (offset 4)

| op | Inferred meaning | Direction(s) | Notes |
|---|---|---|---|
| 0x00 | reserved? | C→S | rare; only one obs |
| 0x01 | drag from slot | both | item_id at offset 5 |
| 0x02 | drag to slot | both | item_id at offset 5 |
| 0x03 | swap / move | both | |
| 0x04 | use / consume | S→C → C→S | |
| 0x05 | drop on ground | C→S | server confirms |
| 0x06 | drop variant | C→S | |
| 0x07 | take from container | both | |
| 0x08 | put into container | both | |
| 0x09 | **equip / unequip** | both | item_id LE4 — `fe ff ff ff` from client = "no source slot" |

The "client says `fe ff ff ff`" pattern is consistent across many
ops: when the client's request has the source slot empty/inferred
(e.g. dragging from the cursor), it sends `0xfffffffe` (-2 LE32)
instead of an item_id.

## Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant U as GameServer (UDP)

    Note over C,U: Player picks up item, hovers over destination slot.
    Note over C,U: Player releases mouse over destination.

    C->>U: 0x00 ? 12B<br/>"00 3c 01 00 [op] [src_slot or fe ff ff ff] [trailer]"
    U->>C: 0x00 ? 12B<br/>"00 3c 01 00 [op] [item_id LE4] [trailer]"
    Note right of C: Server reply confirms with the actual item ID.

    Note over C,U: Equipping a weapon also triggers...
    U->>C: 0x03/0x2f UpdateModel (5-9B; visual model change)
    Note right of C: e.g. equip rifle → model holds rifle.

    Note over C,U: TCP keep-alive marker may fire.
    U-->C: 0xa002 (TCP 2B) — interaction marker
    U-->C: 0x838f (TCP 7B) — interaction marker
```

## Sample exchanges from the capture

| t (s) | Marker context | C/S | Body |
|---:|---|---|---|
| 207.26 | EQUIPED_RIFLE_BELT_1 (-1.5s before marker) | S→C | `00 3c 01 00 09 4b 2b 00 00 0a 79 00` (op=09 equip, item 0x00002b4b) |
| 207.29 | same | C→S | `00 3c 01 00 09 fe ff ff ff 00 c0 e6` (client ack with -2 placeholder) |
| 246.58 | EQUIPED_FLASHLIGHT_BELT_2 (-12s before) | S→C | `00 3c 01 00 04 df 6f 00 00 a6 53 00` (op=04 use, item 0x00006fdf) |
| 246.60 | same | C→S | `00 3c 01 00 04 0f 00 00 00 00 54 5e` (client confirm) |
| 280.47 | EQUIPED_KNIFE_BELT3 | S→C | `00 3c 01 00 01 3a 4d 00 00 44 58 00` (op=01 drag-from, item 0x00004d3a) |
| 280.48 | same | C→S | `00 3c 01 00 01 27 00 00 00 00 68 f1` (drag-from ack) |
| 311.66 | EQUIPED_ARMOR_CHEST | C→S | `00 3c 01 00 09 fe ff ff ff 00 10 71` (equip from cursor) |
| 412.05 | post-tutorial gear arrange | C→S | `00 3c 01 00 02 17 00 00 00 00 a8 75` (op=02 drag-to slot 0x17) |
| 519.81 | inventory shuffle | C→S | `00 3c 01 00 08 fe ff ff ff 00 88 f8` (op=08 put-in-container) |
| 3154.62 | death (server unequips weapon) | S→C | `00 3c 01 00 09 17 09 00 00 65 02 00` (op=09 unequip, see [`death_respawn.md`](death_respawn.md)) |

## Item / slot ID encoding

- **Item IDs** are 32-bit LE integers in bytes 5-8 when the
  field is "real". Examples: `0x00002b4b`, `0x00006fdf`,
  `0x00004d3a`. Item ID space appears densely packed.
- **Slot IDs** in the same field for "drag-to" ops are smaller
  values: `0x00000017`, `0x00000027`, `0x0000003a` — likely
  belt/inventory grid coordinates.
- **`fe ff ff ff`** (-2 LE32) = "infer source from client
  cursor state". Sent by the client when it wants the server to
  use whatever was last picked up.

## RECYCLING_AMMO and DROPPING_TRASH

The recycle and drop-on-ground actions appear in the same channel
with different `op` bytes. RECYCLING_AMMO at t=1740.60 fires:

| Dir | Body |
|---|---|
| C→S | `00 3c 01 00 05 ff ff ff ff 00 [chksum]` (op=05 = drop / consume) |
| S→C | `00 3c 01 00 05 [ammo_id LE4] [chksum]` (server confirms) |

`op=0x05` with `ff ff ff ff` (-1) source = "consume from current
slot, no destination" — equivalent to the "Recycle" or "Use"
action.

## Inventory ack stream (C→S burst)

After major inventory state changes (login, equipping multiple
items in succession), the client emits a burst of small `0x01 ?`
3-byte packets:

```
01 0[slot_lo] 00     (e.g. 010100, 010200, …, 010800)
```

These appear to be per-slot "I have rendered this slot, send me
the contents" requests — the catalog shows
`UDP C->S 0x01 ? (size 3)` 8-at-once after every TCP login event.

Server replies arrive on `UDP S->C 0x02 ?`:

```
02 0[seq] 00 [TLV body containing inventory contents]
```

Sizes vary 9-66 bytes depending on the slot's contents.

## Open questions

- **`op` byte enum.** Need a capture that exercises EVERY op
  (drag, drop, swap, use, equip, unequip, recycle, consume) with
  explicit markers for each. Current capture has a mix but the
  ordering / op→action mapping above is best-effort.
- **The 3-byte trailer (offset 9-11).** Sometimes looks like a
  checksum (highly varying), sometimes seems to encode the
  destination slot. Not yet decoded.
- **Drop-to-ground** (`DROPPING_TRASH` marker at t=1942.29):
  trash should appear as a world entity after drop. What
  spawn packet creates it?
- **Stack split** (drag part of a stack): not in this capture.

## Backing evidence

Timeline:
[`_data/timelines/nc2_strace_RETAIL_CREATION_LEVELING_LONG_20260502_160841.md`](../_data/timelines/nc2_strace_RETAIL_CREATION_LEVELING_LONG_20260502_160841.md)
search for `0x00` with size 12.

Catalog: [`udp_c2s_00.md`](../packets/udp_c2s_00.md),
[`udp_s2c_00.md`](../packets/udp_s2c_00.md).
