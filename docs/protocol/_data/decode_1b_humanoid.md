# UDP S->C 0x1b — Humanoid entity broadcast (deep decode)

Samples: 131131 (filtered to 19B variants)

## Position hypothesis: LE16 - 32000 (matches Movement)

- 5000/5000 samples have all 3 coords in [-32000, 32000]
- Sample positions (Y, Z, X):

  - Y=  -1361 Z=    256 X=   -140
  - Y=  -1472 Z=    512 X=   -303
  - Y=  -1374 Z=    512 X=   -341
  - Y=   -555 Z=    512 X=   -187
  - Y=   -468 Z=    520 X=    190
  - Y=  -1198 Z=    768 X=   -165
  - Y=   -265 Z=    464 X=    178
  - Y=   -577 Z=    464 X=   -676

- Z (vertical) range: **256** to **774** (small range = consistent with vertical axis)

## Bytes 12-13 hypothesis: yaw + tilt

- byte 12: **11** distinct values; top = [('0x40', 56846), ('0x20', 26029), ('0x02', 23048), ('0x42', 20634), ('0x80', 3638), ('0x30', 440), ('0x50', 254), ('0x22', 202)]
- byte 13: **256** distinct values; top = [('0xb9', 22496), ('0x8f', 4618), ('0x02', 4467), ('0x96', 4455), ('0xe0', 4430), ('0x99', 4310), ('0xca', 4210), ('0x3a', 4109)]

Byte 12 has only ~7 distinct values: this is the **tilt** byte (0x40, 0x42, 0x20, 0x02 are the discrete tilts; matches Movement encoding).

Byte 13 varies smoothly: this is the **yaw** byte (quantized 1-byte angle).

## Byte 14: status / state byte

- distinct values: 99
- top: [('0x00', 38227), ('0x88', 22447), ('0x01', 14064), ('0x02', 9293), ('0x04', 6331), ('0x05', 5802), ('0x71', 3232), ('0x3b', 2582), ('0x06', 2061), ('0x07', 1987)]

### State byte by marker (top 6 markers, window ±1s)

| Marker | Top states |
|---|---|
| BASELINE_HUD | `0x88` × 27, `0x71` × 16, `0x00` × 12, `0x06` × 8 |
| KILL_MOB2 | `0x71` × 33, `0x00` × 32, `0x01` × 5, `0x04` × 3 |
| KILL_MOB | `0x71` × 48, `0x00` × 19, `0x01` × 3, `0x06` × 2 |
| SKILLS | `0x71` × 36, `0x00` × 11, `0x01` × 3, `0x06` × 2 |
| POKE_START | `0x00` × 26, `0x04` × 10, `0x01` × 9, `0x05` × 8 |
| IN_WORLD | `0x88` × 21, `0x01` × 7, `0x02` × 5, `0x3b` × 4 |
| MOB_COMBAT_AND_DESPAWN | `0x00` × 38, `0x06` × 3, `0x08` × 3, `0x02` × 2 |
| DISMISS_VEHICLE | `0x00` × 35, `0x01` × 6, `0x0b` × 2, `0x02` × 2 |

## Bytes 15-16: separator

- distinct: 1, top: [('0000', 131131)]

## Bytes 17-18: animation tick / action enum

- byte 17: distinct=256, top=[('0x00', 28745), ('0xff', 10372), ('0x09', 5666), ('0x02', 5161), ('0x1e', 2518), ('0x17', 2450), ('0x0a', 1833), ('0x10', 1635)]
- byte 18: distinct=27, top=[('0x11', 59486), ('0x0f', 17727), ('0x12', 15776), ('0x0a', 15391), ('0x20', 8831), ('0x15', 3323), ('0x21', 3209), ('0x13', 2490)]

### Byte 18 by marker (top markers)

| Marker | Top byte 18 values |
|---|---|
| BASELINE_HUD | `0x11` × 45, `0x0f` × 31, `0x12` × 12, `0x1a` × 4 |
| KILL_MOB2 | `0x11` × 42, `0x0a` × 9, `0x12` × 8, `0x15` × 8 |
| KILL_MOB | `0x11` × 52, `0x15` × 6, `0x12` × 6, `0x0a` × 6 |
| SKILLS | `0x11` × 37, `0x0a` × 8, `0x12` × 5, `0x1a` × 2 |
| POKE_START | `0x11` × 33, `0x12` × 16, `0x0f` × 8 |
| IN_WORLD | `0x11` × 31, `0x0f` × 13, `0x13` × 2, `0x0a` × 2 |
| MOB_COMBAT_AND_DESPAWN | `0x0a` × 22, `0x15` × 15, `0x20` × 9, `0x21` × 4 |
| DISMISS_VEHICLE | `0x20` × 22, `0x0a` × 20, `0x21` × 5, `0x1f` × 3 |

## Final byte-level field map

```
Offset Size Field         Encoding / Notes
0x00   1    opcode        = 0x1b
0x01   2    entity_id     LE16
0x03   2    0x00 0x00     constant separator
0x05   1    0x1f          constant marker (start of payload)
0x06   2    Y coord       LE16 - 32000
0x08   2    Z coord       LE16 - 32000 (vertical)
0x0a   2    X coord       LE16 - 32000
0x0c   1    tilt          discrete (0x40/0x42/0x20/0x02 …)
0x0d   1    yaw           1B quantized angle
0x0e   1    status        0x71 combat · 0x75 idle · others (matches 0x03/0x2d byte 4)
0x0f   2    0x00 0x00     constant separator
0x11   1    animation     animation tick / frame
0x12   1    flags         action enum (0x11=walk, 0x0a=stand, 0x0f=...)
```

**Result: 19B fully decoded**, every byte's role accounted for.
