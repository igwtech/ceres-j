# Capture gaps — scenarios with no retail evidence yet

This is the live list of scenarios we still cannot analyze at
the wire level because no capture exercises them. Each entry
explains what would need to be captured and what byte-level
question it would answer.

## Resolved gaps (delivered by `RETAIL_CREATION_LEVELING_LONG`)

The following gaps were closed by the 2026-05-02 long capture.
Each links to the now-written flow doc:

- **Character creation** → [`character_creation.md`](character_creation.md)
- **Tutorial dungeon (Genesis-equivalent)** → folded into
  [`genesis_dungeon.md`](genesis_dungeon.md) (existing) plus the
  AreaMC5 flow described in marker timeline
- **Apartment entry (private zone)** → [`apartment_mail.md`](apartment_mail.md)
- **Mail / home terminal** → [`apartment_mail.md`](apartment_mail.md)
  (CityCom DCB RPC channel with ASCII method names)
- **Mission flow (NPC quest accept/complete)** → [`missions.md`](missions.md)
- **Inventory drag / equip / drop / recycle** → [`inventory_equip.md`](inventory_equip.md)
- **Skill point allocation (level up)** → [`level_up.md`](level_up.md)
- **Death + respawn** → [`death_respawn.md`](death_respawn.md)
  — major finding: respawn uses GenRep teleport flow
- **P2P trade attempt (request side, LE-blocked)** → [`p2p_trade.md`](p2p_trade.md)

## Still-open gaps

### 1. Successful P2P trade (commit through to item swap)

**Why we can't analyze it.** The trade attempt in
`RETAIL_CREATION_LEVELING_LONG` was server-rejected because both
players had Law Enforcer (LE) chips active. We only see the
request side (`0x2d ?` 6B), not the trade-window opening, item
offering, lock/confirm, or commit.

**What to capture.** Two players WITHOUT LE chips trading items
+ cash. Markers per-side:
- `TRADE_REQ_SENT_A` / `TRADE_REQ_RECEIVED_B`
- `TRADE_ACCEPTED_B`
- `TRADE_ITEM_OFFERED_A` / `_B`
- `TRADE_LOCK_A` / `_B`
- `TRADE_CONFIRM_A` / `_B`
- `TRADE_COMPLETE`

### 2. Successful PvP combat (damage exchange)

**Why we can't analyze it.** Same LE-chip block. The PvP attempt
fired a weapon but no damage applied.

**What to capture.** Either (a) a player dueling another player
in an Anarchy zone where LE chips don't suppress PvP, or (b) two
players without LE chips fighting. Markers as in the regular
combat flow plus `PVP_KILL` / `PVP_DEATH`.

**What it would answer.** Self-HP loss decoding (still open
from PvE combat — PvE captures don't show the player taking
damage), faction/sympathy delta on PvP kill, soullight delta.

### 3. Implant install / removal (poker / surgeon)

**Why we can't analyze it.** No implant interaction in any
capture so far.

**What to capture.** Player visits a poker NPC, opens implant UI,
installs an implant in head/torso/leg, then removes one. Should
exercise the CityCom DCB channel (the implant UI is a kiosk-style
interaction).

### 4. Apartment furniture interaction

**Why we can't analyze it.** The apartment capture in
`RETAIL_CREATION_LEVELING_LONG` only opens the home terminal —
no other furniture (couches, beds, GenRep, storage).

**What to capture.** Sit on couch, open apartment storage,
interact with each piece of furniture.

### 5. Subway / monorail

**Why we can't analyze it.** No subway captures. The
`RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT` capture is district-walk,
not subway.

**What to capture.** Player walks into subway entrance,
selects destination, rides the subway. Should exercise a
GenRep-like teleport plus possibly a "queued teleport" UI.

### 6. Vehicle / drone pilot mode

**Why we can't analyze it.** No vehicle interactions captured.
The `0x20 Movement` `type` bit 6 (`0x40`) is suspected to be the
vehicle/drone control flag but not yet observed.

**What to capture.** Player enters a vehicle / drone, drives,
exits. Markers: `ENTER_VEHICLE`, `DRIVING`, `EXIT_VEHICLE`.

### 7. Group / clan UI

**Why we can't analyze it.** No multi-player social
interactions. The `0x03/0x30 ShortPlayerInfo` and `0x03/0x31
RequestShortPlayer` packets in the catalog are likely the
group/clan member-info channel but not yet exercised.

**What to capture.** Send group invite, accept, kick, leave.
Same for clan operations.

### 8. Successful weapon-fire packet (PvE, hit confirmed)

**Status:** indirectly — the combat captures DO show kills,
implying the fire packet exists; we just haven't isolated
which `0x03/0x1f` sub-tag carries the trigger pull.

**What to capture.** Single deliberate trigger pull at a
stationary target, with markers `BEFORE_FIRE`, `AT_FIRE`,
`AFTER_FIRE` flanking the exact moment.

### 9. Stack split / item-quantity drag

Inventory operations in `RETAIL_CREATION_LEVELING_LONG` cover
whole-item drags and op codes 0x00..0x09. Stack-split (drag
N of M) is not exercised.

### 10. Character delete

We have character CREATE. The complementary DELETE has not been
captured. Likely uses `0x8482` with another discriminator value.

## Capture priority recommendation

Ordered by uniqueness × coverage value:

1. **Successful PvP combat (no LE chips)** — unlocks self-HP
   loss + soullight + sympathy delta + weapon-fire packet.
2. **Successful P2P trade** — unlocks the largest still-unknown
   protocol (trade UI + commit) and validates the `0x2d ?` 6B
   hypothesis.
3. **Implant install / removal** — exercises CityCom DCB on a
   different kiosk.
4. **Subway / monorail** — verifies the GenRep flow generality.
5. **Vehicle pilot** — pins down `0x20 Movement` bit 6.
6. **Apartment furniture** — minor; mostly the same `0x03/0x1f
   sub=0x17` use-object pattern.

## Workflow when captures land

1. Drop `nc2_strace_RETAIL_<SCENARIO>_*.pcap` + `.markers` into
   `ceres-j/strace/`.
2. Run `python3 tools/catalog_extract.py
   --pcap-glob 'strace/*.pcap' --out-dir docs/protocol --update-evidence`
   — refreshes evidence blocks across all per-packet docs and
   updates the catalog.
3. Run `python3 tools/analyze_flow.py --pcap strace/<new>.pcap`
   to generate the per-capture timeline.
4. Hand-write `flows/<scenario>.md` walking the new capture's
   markers.
5. Re-run `python3 tools/render_flow_diagrams.py` to render any
   new mermaid diagrams to PNG.
