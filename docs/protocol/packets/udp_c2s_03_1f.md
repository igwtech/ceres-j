# `UDP C->S 0x03/0x1f` — Reliable/GamePackets

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x03/0x1f`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **188947**
- Captures with this packet: **17/17**
- Size (bytes): min **3**, avg **8**, max **42**
- Top markers (within ±2s):
  - OUTSIDE_AREAM5_TRADING_PLAYER × 72
  - WALK_TO_PEPPERPARK_1 × 48
  - MOB_AGGRO × 45
  - MOB_COMBAT_AND_DESPAWN × 32
  - KILL_MOB2 × 29
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 74790
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 41524
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 15572
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 9784
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 9775
  - `RETAIL_NORMAN_20260426_200458` × 8125
  - `RETAIL_ODA_20260426_202428` × 7733
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 6072
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 4749
  - `RETAIL_HANNIBAL_20260426_201501` × 3216
  - `RETAIL_AUGUSTO_20260426_201952` × 2723
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 2526
  - `RETAIL_DRSTONE4_20260501_193336` × 968
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 760
  - `RETAIL_DRSTONE_20260501_175315` × 314
  - `RETAIL_DRSTONE_20260501_172522` × 204
  - `RETAIL_DRSTONE3_20260501_181349` × 112

Samples (first 32 bytes inner data):

```
#1: 04003e0400000001
```
```
#2: 04002522
```
```
#3: 04002522
```

<!-- /catalog-evidence -->

## Structure

The high-frequency client-to-server multiplexer. Carries gameplay
events (item use, weapon fire, inventory moves, dialog choices,
heartbeats, chat-channel changes, …) within a single sub-tag.
188,947 retail observations across 17/17 captures — the busiest
C→S packet by an order of magnitude.

Verified 2026-05-09 against 67 samples drawn from AUGUSTO, CASH,
CREATION, DRSTONE3, DRSTONE4 retail captures.

**Common body layout (post {@code 0x1f} sub-op):**

```
[0]      0x1f                    sub-opcode (constant)
[1..2]   0x0001  LE16            CONSTANT prefix (67/67 samples) —
                                  likely "client-direction" or
                                  "uncompressed" tag
[3]      action tag              selects the gameplay event class
                                  (see Variants section)
[4]      sub-action              event-specific (e.g. 0x11 for
                                  heartbeat, 0x1e for inventory-move)
[5..N]   payload                 sub-action-specific
```

### Most common sub-shape — heartbeat (`tag 0x3d`)

70% of all C→S 0x03/0x1f traffic is the client in-flight heartbeat
emitted at ~90 Hz during gameplay:

```
9B  1f 01 00 3d 11 00 00 00 00       — heartbeat sub-action 0x11
                                       (fixed body, fires constantly)
12B 1f 01 00 3d 32 00 00 00 [4B]     — heartbeat sub-action 0x32 with
                                       4-byte status snapshot
```

The 0x3d/0x11 sub-action is documented as the "in-flight ACK burst
for server-pushed reliables" — fires at ~90/sec during gameplay,
no server response required.

## Variants

Tag byte distribution across 67 samples:

| tag  | count | meaning                                            |
|-----:|------:|----------------------------------------------------|
| 0x3d |    47 | heartbeat / in-flight ACK (70% of all traffic)     |
| 0x4c |     7 | ChangedChannels (chat channel subscription state)  |
| 0x25 |     6 | inventory/F2-panel sub-actions (matches memory      |
|      |       | project_opcode_structure.md sub-tag map)            |
| 0x3e |     5 | unknown (TODO)                                     |
| 0x32 |     2 | unknown (TODO)                                     |

### Sub-tag detail (from {@code memory/project_opcode_structure.md}):

The 0x03/0x1f S→C sub-tag map applies symmetrically here:

- 0x01 — weapon-fire
- 0x17 — use-obj (UseItem trigger)
- 0x1a — dialog
- 0x1e — InventoryMove (verified — handled by Ceres-J)
- 0x26 — vendor/loot
- 0x2a — mission-grant
- 0x3b — chat (whisper/team/clan/buddy — routed via SubtagRouter)

Empirically observed in retail samples: 0x25, 0x3d, 0x3e, 0x32, 0x4c.

## Observed contexts

Top markers (within ±2s):
- `OUTSIDE_AREAM5_TRADING_PLAYER × 72` — player-trade events
- `WALK_TO_PEPPERPARK_1 × 48` — walking heartbeats
- `MOB_AGGRO × 45` — combat-state updates
- `MOB_COMBAT_AND_DESPAWN × 32` — combat resolution
- `KILL_MOB2 × 29` — kill confirmation

Highest concentration: RETAIL_VEHICLE_DRONE × 74,790 (40% of all
obs) — combat/PvP captures emit heaviest. Heartbeat traffic
dominates per-second rate; gameplay-event traffic dominates per-
event diversity.

## Open questions

- Tag {@code 0x3e} — 5 samples, all `1f 01 00 3e 01 00 00 00 01`
  fixed shape. Looks like an ACK or session-flag toggle. Not
  decoded.
- Tag {@code 0x32} — 2 samples, e.g. `1f 01 00 32 02 55 86 01 00`.
  Different variants in different captures (`02 55 86 01 00` vs
  `02 fe 85 01 00`). Possibly an entity-target ID payload.
- Per-tag full sub-action enumeration — many sub-actions per tag
  observed but not exhaustively decoded.

## Canonical C→S envelope (2026-05-22 update)

After byte-pinning across the full retail catalog (188K
observations), the C→S envelope for the GamePackets multiplexer
is now fully specified:

```
[0]      0x03                    reliable outer
[1..2]   seq LE16                reliable sequence number
[3]      0x1f                    GamePackets sub-op
[4..5]   0x0001 LE16             CONSTANT envelope prefix
[6]      act_tag                 ACTION DISCRIMINATOR (1 byte)
[7..N]   act-specific body       variable per act_tag
```

The byte at offset 6 (the **act_tag**) is the request type. The
dispatcher (`GamePacketReaderUDP.decodesub13` `case 0x1f:`)
skip(2)s past `01 00` and reads the act_tag.

### Full act_tag space (retail-byte-pinned 2026-05-22)

| act_tag | Handler | Body shape | Notes |
|---:|---|---|---|
| `0x06` | AdminCommandRequest | varies | Native client built-in GM cmds (`/gm_noclip`) |
| `0x17` | UseItem | `17 [target_LE32] [trailer]` | Use object (chair/medbed/GoGo/CityCom/GenRep/vendor open) |
| `0x1a` | (none yet) | dialog choice | 87 retail obs; not handled |
| `0x1b` | LocalChat | text body | |
| `0x1e` | InventoryMove | `1e [src_cont:1][src_pos:LE16][dst_cont:1][dst_pos:LE16][flags:LE16]` | F2 drag/drop, vendor sell |
| `0x1f` | **EquipHolster (NEW)** | `1f [slot:1]` | Toolbelt equip — slot is bitmask: 0x00=holster, 0x01/02/04/08=slots 1-4, 0xff=sentinel. **Task #195 closed**. |
| `0x22` | ExitSeatRequest | (no body) | Stand without moving |
| `0x25` | InsideF2InvMove / InventoryCombineItems | `25 14 …` / `25 17 …` | F2 inner sub-actions |
| `0x26` | (none yet) | varies (vendor/loot) | S→C side has 821 B variants |
| `0x27` | **CloseDialog (NEW)** | (no body) | Vendor/CityCom/dialog close — release lock |
| `0x32` | (unhandled) | `32 02 [u32]` | Possibly entity-target ID payload — 6 obs |
| `0x3b` | SubtagRouter → chat | varies | Whisper/team/clan/buddy chat |
| `0x3d` | KillSelfRequest / heartbeat | `3d [sub:1][body]` | 88K obs — 70% heartbeat; sub=0x10 = `/set kill_self` |
| `0x4c` | ChangedChannels | `4c [mask:LE32]` | Chat channel listen-state (~10 s cadence) |

### InventoryMove byte map (retail-pinned)

Body after envelope `03 [seq2] 1f 01 00`:

```
[0]      0x1e                    InventoryMove discriminator
[1]      src_container           e.g. 0x03 = F2 main inv
[2..3]   src_pos LE16            slot in source container
[4]      dst_container           e.g. 0x02 = belt
[5..6]   dst_pos LE16            slot in dest container
[7..8]   flags LE16              0x0001 dominant
```

Retail samples decoded:
- `1e 03 06 00 02 00 00 01 00` — src=cont3/slot6 → dst=cont2/slot0, flags=1
- `1e 04 01 00 03 ff ff 02 00 0f 00` — src=cont4/slot1 → dst=cont3/slot0xffff,
  flags=2, with trailing extension `0f 00` (rare longer variant)

### EquipHolster byte map (retail-pinned 2026-05-22, task #195)

Body after envelope: 2 bytes total `1f [slot]`.

| slot | hits | meaning |
|---:|---:|---|
| `0x00` | 12 | Holster / unarmed |
| `0x01` | 8 | Toolbelt slot 1 |
| `0x02` | 3 | Toolbelt slot 2 |
| `0x04` | 1 | Toolbelt slot 3 (bitmask form) |
| `0x08` | 2 | Toolbelt slot 4 |
| `0xff` | 1 | Sentinel / reset |

Slot is a **bitmask**, not a 0-based index. Total 28 retail
observations across all captures.

### CloseDialog byte map (retail-pinned 2026-05-22)

Body after envelope: **1 byte** `27`. No payload — pure signal.
27 retail observations; top marker `NPC_VENDOR_CLOSE × 1`.

## Server-side handler

Decoded in `server.gameserver.packets.GamePacketReaderUDP.decodesub13`:

- `case 0x1f:` skip(2) past `01 00`, then switch on act_tag:
  - `0x06` → AdminCommandRequest
  - `0x17` → UseItem
  - `0x1b` → LocalChat
  - `0x1e` → InventoryMove
  - `0x1f` → **EquipHolster** (NEW 2026-05-22, task #195)
  - `0x22` → ExitSeatRequest
  - `0x25` → switch on byte 4: `0x14`=InsideF2InvMove, `0x17`=InventoryCombineItems
  - `0x27` → **CloseDialog** (NEW 2026-05-22)
  - `0x3b` → SubtagRouter (cross-channel chat)
  - `0x3d` → switch on sub: `0x10`=KillSelfRequest, else recognise-only
  - `0x4c` → ChangedChannels
  - default → fall-through (UnknownClientUDPPacket)

<!-- constants-audit (generated by tools/constants_audit.py --inject; edit kinds in _data/constants.json) -->
## Constants (catalog-audit)

| Field | kind | note |
|---|---|---|
| `[0] 0x1f sub-opcode (constant)` | discriminator |  |
| `[1..2] 0x0001 LE16 CONSTANT prefix (67/67 samples) —` | UNVERIFIED |  |
| `[4..5] 0x0001 LE16 CONSTANT envelope prefix` | UNVERIFIED |  |
<!-- /constants-audit -->
