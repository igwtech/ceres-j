# `UDP C->S 0x45` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x45`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **4**
- Captures with this packet: **1/17**
- Size (bytes): min **3**, avg **8**, max **15**
- Per-capture counts:
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 4

Samples (first 32 bytes inner data):

```
#1: 45400c000371421f01003d11000000
```
```
#2: 45400c0003ae421f01003d11
```
```
#3: 45400c00
```

<!-- /catalog-evidence -->

## Structure

Same as `udp_c2s_44` — a **compact-burst variant** (0x13-less
reliable-burst) where byte[0] = 0x45 is the counter's
low-byte. See `udp_c2s_44.md` for the full layout; this is
the same packet family with a different counter value.

```
[0]      0x45                 counter low-byte
[1]      0x40                 counter flag
[2..3]   len LE16 = 0x000c    first sub-packet length
[4..]    [03][seq LE2][1f][body]   reliable sub-packet
```

Verified samples:
```
45 40 0c 00 03 71 42 1f 01 00 3d 11 00 00 00     15B
45 40 0c 00 03 ae 42 1f 01 00 3d 11               12B
45 40 0c 00                                       4B (header-only?)
45 40 04                                          3B (truncated?)
```

## Variants

4 retail samples in 1 capture (CREATION_LEVELING_LONG only).
The 3-byte and 4-byte forms appear truncated — possibly
parser-extraction edge cases on partial-burst boundaries.

## Observed contexts

CREATION_LEVELING_LONG only. See `udp_c2s_44.md` for the
shared compact-burst family context.

## Open questions

See `udp_c2s_44.md` Open questions section.

## Server-side handler

Same as `udp_c2s_44.md` — falls into `UnknownClientUDPPacket`,
low-priority parity gap.

