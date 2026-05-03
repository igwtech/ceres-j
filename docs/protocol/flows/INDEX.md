# Protocol Flows

Each flow doc maps a high-level scenario (login, zone walk, combat, …)
to the sequence of retail packets that implement it, citing the
captures and markers that back it.

The catalog under [`../INDEX.md`](../INDEX.md) tells us *which
packets exist*. The flows here tell us *when each one fires*.

## Status conventions

- **stub**: scenario is identified in the corpus but the byte-level
  sequence has not been written down yet.
- **partial**: at least one capture has been walked and the major
  packets have been ordered, but timing/optional packets are unclear.
- **verified**: ≥2 captures corroborate the same sequence, byte-level
  evidence quoted for each step.

## Flows

| Flow | Scenario | Status | Backing captures |
|---|---|---|---|
| [login.md](login.md) | TCP login → CharList → AuthB → UDP gameserver handshake | **verified** | all retail captures |
| [character_creation.md](character_creation.md) | Player creates a new character (fills creation gap) | **verified** | `RETAIL_CREATION_LEVELING_LONG` |
| [genesis_dungeon.md](genesis_dungeon.md) | Tutorial / Dr Stone Genesis dungeon flow (small CharInfo) | **verified** | 4× `RETAIL_DRSTONE*` captures |
| [in_world_movement.md](in_world_movement.md) | Steady-state movement + entity broadcast loop | **verified** | every retail capture |
| [zone_walk_same_district.md](zone_walk_same_district.md) | Player walks across a same-district zone edge (smooth, no splash) | **verified** | `RETAIL_ZONING_AND_ITEMS_LONG` (Pepper p1↔p2↔p3) |
| [zone_walk_cross_district.md](zone_walk_cross_district.md) | Player walks across a cross-district zone edge (Plaza→Pepper) | **verified** | `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT` |
| [death_respawn.md](death_respawn.md) | Player dies (poison) and respawns at apartment | **verified** | `RETAIL_CREATION_LEVELING_LONG` |
| [apartment_mail.md](apartment_mail.md) | Apartment entry + Hometerm mail (CityCom DCB RPC) | **verified** | `RETAIL_CREATION_LEVELING_LONG` |
| [level_up.md](level_up.md) | Skill point allocation (R-C subskill spend) | **verified** | `RETAIL_CREATION_LEVELING_LONG` |
| [inventory_equip.md](inventory_equip.md) | Inventory drag/equip/recycle/drop (12B `0x00 ?` ops) | **verified** | `RETAIL_CREATION_LEVELING_LONG` |
| [interactions.md](interactions.md) | NPC dialogue, vendor, chair, medbed, GenRep, door, elevator, inventory, terminal | partial | `RETAIL_ZONING_AND_ITEMS_LONG` (23 markers) |
| [combat_kill_npc.md](combat_kill_npc.md) | Player kills an NPC (mob), receives loot/cash | partial | `RETAIL_AUGUSTO`, `RETAIL_HANNIBAL`, `RETAIL_NORMAN`, `RETAIL_ODA`, `RETAIL_CREATION_LEVELING_LONG` |
| [missions.md](missions.md) | NPC quest accept → progress → complete (Lorcan/Ezra/Tasha/Geordi) | partial | `RETAIL_CREATION_LEVELING_LONG` |
| [vendor_buy.md](vendor_buy.md) | Player buys an item from an NPC vendor | partial | `RETAIL_CASH_VENDOR_PCAP_FRESH` |
| [fall_damage.md](fall_damage.md) | Player takes fall damage from a jump | partial | `RETAIL_CASH_VENDOR_PCAP_FRESH` |
| [p2p_trade.md](p2p_trade.md) | P2P trade attempt (request side, LE-blocked) | partial | `RETAIL_CREATION_LEVELING_LONG` |
| [npc_dialogue.md](npc_dialogue.md) | Player opens conversation with an NPC | partial — folded into [`interactions.md`](interactions.md) | `RETAIL_ZONING_AND_ITEMS_LONG` |
| [sewer_traversal.md](sewer_traversal.md) | Player enters and exits a sewer (verticality / interior zone) | stub | `RETAIL_AUGUSTO` (sewer markers) |
| [_capture_gaps.md](_capture_gaps.md) | Remaining scenarios with no retail capture | gap | successful PvP, successful trade, implants, vehicles, group/clan, … |

## Open scenarios (no capture yet)

These are documented as gaps so we know what to capture next:

- Death + respawn (medbed + GenRep flows)
- Trade between two players
- Apartment entry + furniture interaction
- PvP combat (player damages player)
- Implant install + removal
- Skill point allocation (level up)
- Faction sympathy change
- Map / city transition (subway, monorail)
- Drone / vehicle pilot mode
- Group/clan invite + accept

When a capture lands for one of these, the workflow is:

1. Place pcap under `ceres-j/strace/` with `RETAIL_<SCENARIO>` prefix.
2. Place `.markers` file alongside (one timestamp per phase).
3. Run `tools/catalog_extract.py --pcap-glob 'strace/*.pcap'
   --out-dir docs/protocol --update-evidence`. New types will appear
   in the master INDEX; existing per-packet docs will get their
   evidence blocks refreshed.
4. Hand-write `flows/<scenario>.md` walking the new capture.
