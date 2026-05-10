# `UDP S->C 0x03/0x2b` — Reliable/CityCom

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x03/0x2b`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **94**
- Captures with this packet: **3/17**
- Size (bytes): min **7**, avg **66**, max **919**
- Top markers (within ±2s):
  - OPEN_HOMETERM × 3
  - OPEN_HOMETERM_DELETEMAIL × 3
- Per-capture counts:
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 52
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 22
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 20

Samples (first 32 bytes inner data):

```
#1: 1a0f0001000056656869636c654c697374696e6700
```
```
#2: 170f000100040056656869636c654c697374696e670006003238303835000200
```
```
#3: 1a0f0001000056656869636c65436f6e74726f6c00
```

<!-- /catalog-evidence -->

## Structure

S→C side of the CityCom DCB RPC channel. See
`udp_c2s_03_2b.md` for the full protocol description — both
directions share the same wire shape.

```
[0]      0x2b                   sub-opcode
[1]      variant                 0x1a (method-name response, dominant
                                  on S→C side), 0x1e (ASCII text reply)
[2..]    variant-specific
```

### Variant 0x1a — method-name response (22B common)

Server emits when answering a C→S DCB call:
```
2b 1a 0f 00 01 00 00 [ASCII method name]\0
```

Example from RETAIL: `VehicleListing\0` — server confirms which
RPC method it's responding to before sending the actual data.

### Variant 0x1e — ASCII text reply

Long-form text reply to the client (e.g. tutorial text, news
ticker, mission descriptions). Length-prefixed ASCII.

## Variants

Same variant table as C→S side. S→C is dominated by 0x1a
method-name responses; C→S is dominated by 0x1f binary RPC
calls + 0x1e ASCII payloads.

## Observed contexts

Triggered by client opening CityCom panels (Vehicle, Bank,
Apartment listings) or requesting tutorial/help text. Server
emits two-stage response: 0x1a method-name confirm + 0x1e
ASCII data.

## Open questions

See `udp_c2s_03_2b.md` open questions — full DCB method
registry pending.

## Server-side handler

Not currently emitted by Ceres-J. Implementing DCB RPC
responses requires:
1. Method-name → builder mapping
2. Per-method data-fetch (vehicle list, bank contents, etc.)
3. ASCII reply builder

Out of scope for current parity work.

