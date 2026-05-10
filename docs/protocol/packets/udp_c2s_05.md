# `UDP C->S 0x05` — ?

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x05`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **2**
- Captures with this packet: **2/17**
- Size (bytes): min **3**, avg **3**, max **3**
- Per-capture counts:
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 1
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 1

Samples (first 32 bytes inner data):

```
#1: 05001f
```
```
#2: 05001f
```

<!-- /catalog-evidence -->

## Structure

UDP C→S raw 0x05 — 3-byte rare emission. 2 retail samples,
both byte-identical: `05 00 1f`.

```
[0]   0x05                  sub-opcode
[1]   0x00                  CONSTANT
[2]   0x1f                  CONSTANT (= GamePackets sub-tag id)
```

## Variants

Single 3-byte form across 2 retail samples (PARTY_A/B
captures). Pure constant.

## Observed contexts

Only 2 emissions in retail, both in party-PvP captures. The
trailing `0x1f` byte (= GamePackets sub-tag) suggests this is
some kind of "request for GamePackets channel" or
session-state-related signal.

## Open questions

- Without more samples, the trigger semantics are unclear.
  The byte-stable pattern across 2 captures rules out
  session-derived data.

## Server-side handler

Not currently handled in `GamePacketReaderUDP`. Falls to
`UnknownClientUDPPacket` catch-all. **Low priority** (2 retail
samples).

