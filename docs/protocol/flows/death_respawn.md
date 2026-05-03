# Flow: Death + respawn (poison kill)

**Status:** verified  
**Backing capture:** `RETAIL_CREATION_LEVELING_LONG_20260502_160841`
— markers `POISONED_DIED` (t=3146.61s),
`RESPAWN_APARTMENT` (t=3169.53s).

## Scenario

Player takes lethal damage (poison ticks in this capture; same
mechanism applies to combat death and fall damage). Server marks
HP=0, plays death animation, unequips current weapon, then
**reuses the GenRep teleport flow** to deliver the player to
their bound respawn point (apartment / hospital).

## Major architectural finding

**Death does NOT have its own protocol.** It uses the same
40-byte `0x03/0x23` (`0c 00 …`) trigger that the GenRep fast-travel
flow uses ([`interactions.md` § GENREP](interactions.md#genrep_use--genrep_landed)).
From the wire's perspective, "you died" is indistinguishable from
"server initiated a teleport". The only asymmetry:

- GenRep: server emits a **cash deduction** (`0x03/0x1f sub=0x04`)
  to charge the GenRep fee.
- Death: no cash deduction (free) but precedes by an **equipment
  unequip burst** (`0x00 ?` 12B, op `0x09`) and a brief HP-zero
  window where the player is on the ground.

## Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant U as GameServer (UDP)
    participant T as GameServer (TCP)

    rect rgb(255,245,245)
    Note over C,U: Phase A — HP reaches zero
    U->>C: 0x1f ? 14B "1f 01 00 30 00 00 00 00 00 00 00 00 34 01"
    Note right of C: All-zero pool values + status byte 0x34 — death indicator.
    Note over C,U: Player ragdolls; movement still streams (body still in zone).
    end

    rect rgb(255,250,240)
    Note over C,U: Phase B — Server unequips equipped weapon
    U->>C: 0x00 ? 12B "00 3c 01 00 09 [item_id LE2] 00 00 [chksum]"
    Note right of C: op=0x09 — server-side unequip. Same as inventory drag in interactions.md.
    C->>U: 0x00 ? 12B "00 3c 01 00 09 fe ff ff ff 00 [chksum]"
    Note right of C: Client ack.
    end

    rect rgb(245,250,255)
    Note over C,T: Phase C — Respawn teleport — uses GenRep flow!
    U->>C: 0x03/0x23 40B "0c 00 00 00 …" — SAME packet as GenRep trigger
    U->>C: 0x03/0x23 19B "04 00 …00 32" — zone-transition meta
    Note right of C: byte[15] differs from regular GenRep — death-specific subtype?
    C->>U: 0x03/0x22 sub=0x03 (Zoning2 — server-initiated, NO Zoning1)
    T->>C: 0x830d GameinfoReady
    T->>C: 0x830c Location 32B "830c [zone_id LE4] … 'apt…' " — apartment zone
    U->>C: 0x04 ? — UDP cipher reseed
    C->>U: 0x03 Reliable — cipher reseed reply
    C->>U: 0x03/0x08 ZoningEnd
    end

    rect rgb(245,255,250)
    Note over C,U: Phase D — Apartment state stream
    U->>C: 0x03/0x23 6B "0f 00 03 00 01 00" — post-transition info
    U->>C: 0x03/0x2f UpdateModel 77B — full model resync — alive again
    U->>C: 0x03/0x09 2B "02 00"
    U->>C: 0x03/0x1b PosUpdate × N — entities entering view
    U->>C: 0x1f ? 14B "1f 01 00 30 3b 00 4c 00 22 00 a9 00 16 01"
    Note right of C: HP/PSI/STA values resume — non-zero. Player is alive.
    end

    Note over C,U: ~6s of UI animation gap (death cam, loading apartment).
    Note over C,U: User marker RESPAWN_APARTMENT fires when control returns.
```

## HP-zero indicator

The `0x1f ?` 14-byte packet with **all-zero pool values** is the
canonical "this entity has zero HP" signal:

```
Offset  Size  Field        Value at death
0x00    1     0x1f         opcode
0x01    1     0x01         constant
0x02    1     0x00         constant
0x03    1     0x30         sub-tag = "pool status burst"
0x04    2     HP LE2       0x0000 (zero)
0x06    2     PSI LE2      0x0000 (zero)
0x08    2     STA LE2      0x0000 (zero)
0x0a    2     ?            0x0000 (zero)
0x0c    1     status flags 0x34 (death animation flag)
0x0d    1     ?            0x01
```

Compare against pre-death values (sample from t=3145):
`1f 01 00 30 [HP] [PSI] [STA] [?] [status] 01` — all positive,
status byte normally 0x26 / 0x16.

This packet was earlier hypothesised to be foreign-entity HP. The
death capture proves it's also used for the **player's own HP**
in critical-state events. The difference: at death the values
broadcast all zero **for the player entity**.

## Equipment-unequip burst (Phase B)

The 12-byte `0x00 ?` packets are the inventory channel
(documented in [`interactions.md`](interactions.md) under
"INVENTORY_MANAGE"). On death, the server unequips the player's
weapon by sending `op=0x09` (equip slot) with the weapon's
item_id — equivalent to "drop weapon" — and the client acks.

Format:

```
Offset  Size  Field
0x00    1     0x00       opcode
0x01    1     0x3c       sub-channel = 60 (inventory)
0x02    1     0x01       constant
0x03    1     0x00       constant
0x04    1     0x09       op = unequip / drop
0x05    4     item_id LE4 (or `fe ff ff ff` from client = "no source")
0x09    3     trailing bytes — checksum or chain id
```

In the death capture this fires once for the rifle that was
previously equipped (matches `EQUIPED_RIFLE_BELT_1` from earlier
in the capture).

## Annotated walkthrough — t=3146.61s..3169.53s

| t (s) | Δ ms | Dir | Packet | Sz | Annotation |
|---:|---:|---|---|---:|---|
| **3146.61** | — | — | — | — | **▸ POISONED_DIED marker** |
| 3150.35 | 3740 | S→C | `0x1f ?` | 14 | **HP=0 burst** (all zeros + 0x34 status) |
| 3154.62 | 4270 | S→C | `0x00 ?` | 12 | **Unequip rifle** (op=0x09) |
| 3154.68 | 60 | C→S | `0x00 ?` | 12 | unequip ack |
| 3160.17 | 5490 | S→C | `0x03/0x23` | 40 | **GenRep transition trigger** (`0c 00 …`) |
| 3160.18 | 10 | S→C | `0x03/0x23` | 19 | zone-transition meta (`04 …`) |
| 3160.32 | 140 | C→S | `0x03/0x22 sub=0x03` | 18 | **Zoning2** (server-init, no Zoning1) |
| 3160.56 | 240 | S→C | `0x830d` | 4 | TCP GameinfoReady |
| 3161.00 | 440 | S→C | `0x830c` | 32 | TCP Location: apartment zone |
| 3161.00 | 0 | S→C | `0x04 ?` | 7 | UDP cipher reseed |
| 3161.00 | 0 | C→S | `0x03 Reliable` | 8 | cipher reseed reply |
| 3161.01 | 10 | C→S | `0x03/0x08` | 2 | **ZoningEnd** |
| 3161.21 | 200 | S→C | `0x03/0x23` | 6 | post-transition info `0f 00 03 00 01 00` |
| 3161.21 | 0 | S→C | `0x03/0x2f UpdateModel` | 77 | model resync (alive) |
| 3162.70 | 1490 | S→C | `0x03/0x1b PosUpdate` | 11 × 4 | entity stream resumes |
| 3166.62 | 3920 | S→C | `0x1f ?` | 14 | HP recovers — `3b 00 4c 00 22 00 a9 00` |
| **3169.53** | — | — | — | — | **▸ RESPAWN_APARTMENT marker** |

Total death-to-respawn time: **23 seconds** (most of which is
client-side death-cam + apartment loading; protocol-level work
finishes at t=3162.70, ~16 s before the user clicked their marker
key).

## Player exit world (after respawn)

**`UDP C→S 0x08` (1 byte, payload `08`)** is the disconnect
signal. Observed at t=3182.25 right after `EXIT_WORLD` marker:

```mermaid
sequenceDiagram
    participant C as Client
    participant U as GameServer (UDP)
    Note over C,U: User selects "Exit to Char Select" from menu.
    C->>U: 0x08 (1B "08") — disconnect
    Note over C,U: Server tears down session; UDP socket closes.
```

This is distinct from `0x03/0x08 ZoningEnd` (which is 2 bytes
inside the reliable wrapper).

## Open questions

- **Death-vs-GenRep distinction byte.** The 19B `0x03/0x23`
  zone-transition meta packet has a byte at offset 15 that
  differs:
  - GenRep (line 36014 of ZONING_AND_ITEMS_LONG): `04 … 00 d0`
  - Death (line 149484): `04 … 00 32`
  Could be a "transition reason" byte. Need more captures to
  confirm.
- **HP-recovery curve.** Between death (HP=0) and respawn
  (HP=0x3b/59), values increment slowly. Is this a server-side
  ramp, or does the value snap from 0 to full on respawn and
  what we're seeing is the regen tick after?
- **PvP rejection.** The user attempted PvP earlier in this
  capture (between `OUTSIDE_AREAM5_PULLOUT_SLOT1_RIFLE` and
  `OUTSIDE_AREAM5_COMBAT`) but both characters had Law
  Enforcer (LE) chips → server rejected. Wire-level signature of
  the rejection is not yet isolated; the player's HP didn't
  drop and the target's HP didn't drop — which means we should
  compare a "fired weapon, no damage" capture against this one
  to find the rejection notification packet.

## Backing evidence

Timeline:
[`_data/timelines/nc2_strace_RETAIL_CREATION_LEVELING_LONG_20260502_160841.md`](../_data/timelines/nc2_strace_RETAIL_CREATION_LEVELING_LONG_20260502_160841.md)
lines 149291-149557 (death) and 149620-149626 (exit world).
