# UDP C->S 0x03/0x2d — Drone control (deep decode, 41B)

Samples: 782 41B drone control packets

## bytes 0-3: drone_id LE32

- distinct: 1
- top: [('0x000003d6', 782)]

## byte 4: drone state

- distinct: 1, top: [('0x02', 782)]

## bytes 5-16: position vector (3× LE32 float)

- 782/2000 samples have all 3 finite
- sample positions:
  - X=    374.34 Y=   -438.65 Z=   3160.63
  - X=    374.34 Y=   -439.59 Z=   3160.63
  - X=    374.34 Y=   -440.49 Z=   3160.63
  - X=    374.34 Y=   -440.54 Z=   3160.63
  - X=    374.34 Y=   -440.54 Z=   3160.63
  - X=    374.34 Y=   -440.54 Z=   3160.63
  - X=    374.34 Y=   -440.54 Z=   3160.63
  - X=    374.34 Y=   -440.54 Z=   3160.63

## bytes 17-32: orientation (4× LE32 float = quaternion?)

- No quaternion-like values found; bytes 17-32 are NOT a quaternion.
  - bytes 17-32 floats: 0.000, 0.000, 0.000, 3.369
  - bytes 17-32 floats: 0.000, 0.000, 0.000, 3.369
  - bytes 17-32 floats: 0.000, 0.000, 0.000, 3.369
  - bytes 17-32 floats: 0.000, 0.000, 0.000, 3.369
  - bytes 17-32 floats: 0.000, 0.000, 0.000, 3.369
  - bytes 17-32 floats: 0.000, 0.000, 0.000, 3.369

## bytes 33-40: control inputs / fire trigger

- byte 33: distinct=190, top=[('0xfa', 202), ('0x12', 93), ('0x26', 62), ('0x69', 12), ('0x90', 10)]
- byte 34: distinct=187, top=[('0xbc', 202), ('0xe8', 93), ('0xcd', 62), ('0x75', 13), ('0x15', 9)]
- byte 35: distinct=134, top=[('0x1f', 209), ('0x76', 93), ('0x83', 65), ('0x8a', 15), ('0xb4', 15)]

## Marker-correlated samples

- DRONE_INUSE (idle): 36 samples
- DRONE_INUSE_FIRING: 36 samples

- byte 33: idle=(18, 30), fire=(38, 30) ← DIFFERENT
- byte 34: idle=(232, 30), fire=(205, 30) ← DIFFERENT
- byte 35: idle=(118, 30), fire=(131, 30) ← DIFFERENT
- byte 36: idle=(67, 36), fire=(67, 36)
- byte 37: idle=(0, 36), fire=(0, 36)
- byte 38: idle=(0, 36), fire=(0, 36)
- byte 39: idle=(0, 36), fire=(0, 36)
- byte 40: idle=(0, 36), fire=(0, 36)

## Final field hypothesis (41B drone control)

```
Offset Size Field         Notes
0x00   4    drone_id      LE32
0x04   1    0x02          drone class indicator
0x05   12   position 3D   LE32 float × 3 (X, Y, Z)
0x11   16   orientation   4× LE32 float (quaternion or Euler+roll)
0x21   8    control bytes throttle, yaw input, fire trigger, etc.
```
