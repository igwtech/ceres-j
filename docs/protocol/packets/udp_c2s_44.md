# `UDP C->S 0x44` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x44`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **3**
- Captures with this packet: **3/17**
- Size (bytes): min **11**, avg **16**, max **27**
- Per-capture counts:
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 1
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 1
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 1

Samples (first 32 bytes inner data):

```
#1: 44400c000336061f01003d
```
```
#2: 44400c000336061f01003d
```
```
#3: 44400c00034b4d1f01003d11000000001100200100272c70ec4576
```

<!-- /catalog-evidence -->

## Structure

UDP C→S raw 0x44 — **compact-burst variant** (0x13-less
reliable-burst). Same family as `udp_c2s_08`'s 70B form and
`udp_c2s_45` / `udp_c2s_c4` / `udp_c2s_ef`. The catalog folds
these under byte[0] but they are NOT distinct opcodes — byte[0]
is the LOW byte of the encoded counter.

```
[0]      0x44                 counter low-byte (NOT a true outer)
[1]      0x40                 counter / flag byte
[2..3]   len LE16 = 0x000c    first sub-packet length
[4..]    [03][seq LE2][1f][body]   reliable sub-packet
[…]      additional sub-packets concatenated
```

Verified samples:
```
44 40 0c 00  03 36 06 1f 01 00 3d                  partial (11B)
44 40 0c 00  03 4b 4d 1f 01 00 3d 11 00 00 00 00   12B sub (16B)
                                  ↓ extra sub-packet appended:
              11 00 20 01 00 27 2c 70 ec 45 76     11B trailing
```

## Variants

3 retail samples across 3 captures. Sizes 11..27B.

## Observed contexts

Concentrated in PvP and intensive-activity captures. The
similar `0x45`, `0xc4`, `0xef` variants share this
compact-burst pattern — see those docs for the same context.

## Open questions

- See `udp_c2s_08.md` Open questions section. The whole 0x44
  / 0x45 / 0xc4 / 0xef family is conceptually one packet:
  0x13-less compact reliable-burst.

## Server-side handler

Not specifically handled (falls into `UnknownClientUDPPacket`).
**Low priority** parity gap — same payload is also delivered
via the standard 0x03 reliable path.

