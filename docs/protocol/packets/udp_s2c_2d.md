# `UDP S->C 0x2d` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x2d`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **8**
- Captures with this packet: **2/17**
- Size (bytes): min **6**, avg **6**, max **6**
- Per-capture counts:
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 4
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 4

Samples (first 32 bytes inner data):

```
#1: 2d000002000a
```
```
#2: 2d007402000a
```
```
#3: 2d005c00000a
```

<!-- /catalog-evidence -->

## Structure

UDP S→C raw 0x2d — small variant of NPCData (= `0x03/0x2d`).
Fixed 6-byte body. Verified 2026-05-10 against all 8 retail
samples from 2/17 captures.

```
[0]      0x2d                    sub-opcode (constant)
[1]      0x00                    CONSTANT
[2..3]   value LE16              varies (0x0d80, 0xf400, 0x0670,
                                  0x0688, 0x0200, 0x0274, 0x005c, ...)
[4]      0x00                    CONSTANT
[5]      0x0a                    CONSTANT TRAILER (= 10)
```

Sample retail bytes:
```
2d 00 00 02 00 0a   value=0x0200 (=512)
2d 00 74 02 00 0a   value=0x0274 (=628)
2d 00 5c 00 00 0a   value=0x005c (=92)
2d 00 80 0d 00 0a   value=0x0d80 (=3456)
2d 00 f4 00 00 0a   value=0xf4 (=244)
```

The trailing `0x0a` byte at [5] is constant in all samples —
likely a sub-action/sub-type marker (0x0a within the 0x2d
sub-action enum).

## Variants

Single 6-byte form. Same wire shape as the inner of
`0x03/0x2d` (NPCData) sub-action 0x0a. The raw 0x2d emission
is the unreliable variant of the reliable NPC-state update.

## Observed contexts

Only 2/17 captures emit this:
- `RETAIL_ZONING_AND_ITEMS_LONG` × 4
- `RETAIL_CREATION_LEVELING_LONG` × 4

Both are LONG-duration captures with extended NPC interaction
phases. The 6-byte form is rare compared to the reliable
`0x03/0x2d` (69,755 retail samples).

## Open questions

- The LE16 value at [2..3] looks like a counter or tick — but
  values don't form a clean monotonic sequence. Could be:
  - World tick (16-bit clock)
  - NPC entity ID delta
  - Animation frame counter
- Sub-action 0x0a at [5]: which event in the NPC-state
  state-machine? Per `synchronizing_overlay_root_cause.md`
  and `lstplayer_error_misattribution.md`, the 0x2d
  sub-action enum is partially mapped (sub-action 0x11 is
  the LSTPLAYER error culprit).

## Server-side handler

`server.gameserver.subtagrouter.SubtagRouter` recognises 0x2d
as NPC-data. The unreliable raw 0x2d variant is consumed
silently by the catch-all path.

For full retail parity: implement a 6B unreliable variant
emission alongside the reliable `0x03/0x2d` for high-frequency
NPC state events where loss is acceptable. **Low priority**
given the rarity (only 8 retail samples).

