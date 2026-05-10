# `UDP C->S 0xef` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0xef`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **1**
- Captures with this packet: **1/17**
- Size (bytes): min **39**, avg **39**, max **39**
- Per-capture counts:
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 1

Samples (first 32 bytes inner data):

```
#1: ef2e044662ad654489186044600c0003f2491f01003d11000000001100200100
```

<!-- /catalog-evidence -->

## Structure

Same family as `udp_c2s_44` / `_45` / `_c4` — a
**compact-burst variant** (0x13-less reliable-burst) where
byte[0] = 0xef is the counter's low-byte. See `udp_c2s_44.md`
for the full layout.

The 39B size includes a longer prefix before the standard
sub-packet block:

```
ef 2e 04 46 62 ad 65 44 89 18 60 44     12B prefix (Movement floats?)
60 0c 00  03 f2 49 1f 01 00 3d 11 00 00 00 00     [03][seq][1f][body]
11 00 20 01 00                                    next sub
```

The 12-byte prefix `[ef 2e 04 46 62 ad 65 44 89 18 60 44]`
contains 3× LE32 floats (0x4604 2eef = ~8459, 0x4465 ad62 =
~918, 0x4460 1889 = ~896) — looks like a position vector
appended before the sub-packet stream.

## Variants

1 retail sample only (CREATION_LEVELING_LONG). Likely a
specific compact-burst variant carrying both a Movement
position float-triple AND a 0x03/0x1f GamePacket.

## Observed contexts

CREATION_LEVELING_LONG only — single sample. See
`udp_c2s_44.md` for context.

## Open questions

The 12-byte float prefix's exact layout — possibly an inline
Movement record with no length prefix.

## Server-side handler

Same as `udp_c2s_44.md` — falls into `UnknownClientUDPPacket`,
low-priority parity gap (1 sample only).

