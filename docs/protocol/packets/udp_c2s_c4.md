# `UDP C->S 0xc4` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0xc4`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **2**
- Captures with this packet: **1/17**
- Size (bytes): min **26**, avg **28**, max **30**
- Per-capture counts:
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 2

Samples (first 32 bytes inner data):

```
#1: c4300c0003757c1f01003d1100000000110020010027091bd7456f286344
```
```
#2: c4300c0003957c1f01003d110000000011002001002745c8d645
```

<!-- /catalog-evidence -->

## Structure

Same as `udp_c2s_44` — a **compact-burst variant** (0x13-less
reliable-burst) where byte[0] = 0xc4 is the counter's
low-byte. See `udp_c2s_44.md` for the full layout.

```
[0]      0xc4                 counter low-byte
[1]      0x30                 counter flag
[2..3]   len LE16 = 0x000c    first sub-packet length
[4..]    [03][seq LE2][1f][body] + additional sub-packets
```

Verified samples:
```
c4 30 0c 00  03 75 7c 1f 01 00 3d 11 00 00 00 00
            11 00 20 01 00 27 09 1b d7 45 6f 28 63 44   30B (2 sub-packets)

c4 30 0c 00  03 95 7c 1f 01 00 3d 11 00 00 00 00
            11 00 20 01 00 27 45 c8 d6 45              26B (2 sub-packets)
```

## Variants

2 retail samples, both in CREATION_LEVELING_LONG. Sizes 26B
and 30B — each carries 2 chained sub-packets (a 0x03/0x1f
GamePacket plus a 0x20 Movement).

## Observed contexts

CREATION_LEVELING_LONG only. See `udp_c2s_44.md` for context.

## Open questions

See `udp_c2s_44.md`.

## Server-side handler

Same as `udp_c2s_44.md` — falls into `UnknownClientUDPPacket`,
low-priority parity gap.

