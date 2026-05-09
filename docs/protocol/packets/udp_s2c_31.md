# `UDP S->C 0x31` — ?

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x31`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **1**
- Captures with this packet: **1/17**
- Size (bytes): min **1**, avg **1**, max **1**
- Per-capture counts:
  - `RETAIL_ODA_20260426_202428` × 1

Samples (first 32 bytes inner data):

```
#1: 31
```

<!-- /catalog-evidence -->

## Structure

Single byte:

```
offset  size  field
   0      1   0x31   tag
```

Only 1 hit in 1 capture (`RETAIL_ODA`). Insufficient evidence
to commit any structural detail beyond "one byte, value `0x31`".

## Variants

None observed.

## Observed contexts

1 of 17 captures, 1 hit. No top markers.

The C→S side already has `0x31 = RequestShortPlayerInfo`
(handled in the dispatcher's `0x03/0x31` route). The S→C raw
`0x31` here may be the unsolicited counterpart but the single
observation precludes confirmation.

## Open questions

- Is the S→C raw `0x31` related to the C→S
  `0x03/0x31 RequestShortPlayerInfo` channel, or unrelated?
- Why only one hit across 17 captures? Could be a corner-case
  state notifier (e.g. "no such player").

## Server-side handler

[`server.gameserver.packets.client_udp.Sub0x31Recognized`](../../../src/main/java/server/gameserver/packets/client_udp/Sub0x31Recognized.java)
— recognise-only. Single-capture evidence; needs more samples
before any field-level work.

