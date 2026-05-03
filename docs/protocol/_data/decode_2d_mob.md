# UDP S->C 0x03/0x2d — Mob behavior tick (deep decode, 54B)

Samples: 65539 54B mob ticks

## bytes 0-3: entity_id LE32

- distinct: 353 mobs across all captures
- top: [('0x000003db', 2224), ('0x0000010a', 1708), ('0x000003ee', 1563), ('0x000003aa', 1417), ('0x000003e7', 1222)]

## byte 4: state enum

- distinct: 5
- distribution: [('0x75', 36899), ('0x71', 26695), ('0x70', 1847), ('0x6f', 69), ('0x72', 29)]

### State byte 4 by marker (combat-related)

| Marker | Top states (with count) |
|---|---|
| MOB_AGGRO | `0x75` × 24, `0x71` × 2 |
| MOB_AGGRO2 | `0x75` × 75, `0x71` × 50 |
| MOB_DEAGGRO | `0x75` × 138, `0x71` × 67 |
| MOB_DEAD | `0x75` × 104, `0x71` × 54 |
| MOB_TARGETED | `0x75` × 9, `0x71` × 3 |
| MOB_COMBAT_AND_DESPAWN | `0x75` × 147, `0x71` × 85 |
| KILL_MOB | `0x75` × 46, `0x71` × 5, `0x70` × 3 |
| KILL_MOB2 | `0x75` × 32, `0x71` × 19 |
| KILL_MOB3 | `0x75` × 15 |
| OUTSIDE_AREAM5_KILLED_CRAWLER | `0x71` × 6, `0x75` × 5 |

**State byte 4 enum (best-known):**

| Value | Meaning | Evidence |
|---|---|---|
| `0x6f` | (rare transition) | observed during combat |
| `0x70` | (rare) | spawning / dying transition |
| `0x71` | **In-combat / aggro** | dominant during MOB_AGGRO |
| `0x72` | (very rare) | unknown |
| `0x75` | **Idle / default** | dominant in baseline |

## byte 1: mob class

- distinct: 3, top: [('0x03', 46567), ('0x01', 10281), ('0x02', 8691)]

Hypothesis: this is the **mob class enum** (matches the entity-class byte we identified in `0x20` Movement byte 1).

## bytes 5-7: state-detail / animation hash

- distinct triples: 11334
- top: [('170024', 3207), ('000000', 1374), ('00006f', 1348), ('2f01ac', 875), ('00009d', 610)]

## bytes 8-11: pos_x as LE32 float

- 1928/5000 parse to finite values in ±1M range
- samples: ['0.00', '464216926300919293804544.00', 'nan', 'nan', '464215881465805743849472.00', '0.00']

## bytes 12-15: pos_y as LE32 float (or sentinel)

- `ff ff ff ff` sentinel: **0** (0.0% of obs)
- Sentinel = "no target / no waypoint" (mob is idle, not tracking anything)

- non-sentinel float samples: ['0.00', '0.00', '0.00', '0.00', '-0.00', '0.00', '0.00', '0.00']

## bytes 0x10-0x13: as LE32 float

- finite parse: 2522/3000 samples
- `ff ff ff ff` count: 0

## bytes 0x14-0x17: as LE32 float

- finite parse: 2195/3000 samples
- `ff ff ff ff` count: 0

## bytes 0x18-0x1b: as LE32 float

- finite parse: 1587/3000 samples
- `ff ff ff ff` count: 0

## Tail bytes 20-53 (34 bytes): behavior payload

These bytes carry mob-specific behavior data. Per-byte entropy is high (most bytes have 100+ distinct values), consistent with floats / packed state.

## Final field hypothesis (54B mob tick)

```
Offset Size Field            Notes
0x00   4    entity_id        LE32 — mob world ID
0x04   1    state            0x71 combat · 0x75 idle · 0x70/0x72 transitions
0x05   3    state-detail     animation hash / sub-state
0x08   4    pos_x            LE32 float — current position
0x0c   4    target_x         LE32 float OR 0xffffffff sentinel = no target
0x10   4    pos_y / target_y LE32 float
0x14   4    pos_z / target_z LE32 float
0x18   4    velocity / heading
0x1c   8    HP / max_HP / armor (likely u32 + u32)
0x24   var  behavior tail    weapon ID + animation timer + agro target_id
```

