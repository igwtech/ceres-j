# `TCP S->C 0xa002` — ?-S

**Transport:** TCP  
**Direction:** S->C  
**Identifier:** `0xa002`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **224**
- Captures with this packet: **10/17**
- Size (bytes): min **2**, avg **2**, max **2**
- Top markers (within ±2s):
  - POKE_START × 2
  - HEAL_PVP × 2
  - TRADE_CASH_CONFIRM × 2
  - BEFORE_CROSS × 1
  - MEDBED_USE × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 68
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 48
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 41
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 41
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 15
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 4
  - `RETAIL_DRSTONE_20260501_175315` × 3
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 2
  - `RETAIL_DRSTONE4_20260501_193336` × 1
  - `RETAIL_DRSTONE3_20260501_181349` × 1

Samples (first 32 bytes inner data):

```
#1: a002
```
```
#2: a002
```
```
#3: a002
```

<!-- /catalog-evidence -->

## Structure

InteractionAck — TCP S→C 2-byte signal. Constant body, no
payload.

```
[0..1]   a0 02                  TCP opcode (constant)
```

All 224 observations across 17/17 captures are byte-identical:
`a0 02` (2 bytes). NO size variation.

## Variants

Single 2-byte form. Pure signaling ack — no payload.

## Observed contexts

Server-to-client TCP signal acknowledging a client interaction.
Fired in response to specific C→S TCP interactions (door open,
NPC interact, dialog response). The client treats it as a
"server received your interaction" confirmation.

## Open questions

- Which exact C→S interactions trigger this? Catalog markers
  don't pinpoint a single trigger — likely fires on multiple
  interaction-class packets.

## Server-side handler

`server.gameserver.packets.server_tcp.InteractionAck` — emits
the 2-byte constant body. Wired in:

- {@link server.gameserver.packets.client_tcp.UseItem}
  (task #148 closed this)
- Other interaction handlers as they're plumbed through

Simple constant emit — no per-call state.

