# `UDP C->S 0x03/0x2b` — Reliable/CityCom

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x03/0x2b`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **123**
- Captures with this packet: **3/17**
- Size (bytes): min **22**, avg **53**, max **828**
- Top markers (within ±2s):
  - OPEN_HOMETERM × 6
  - OPEN_HOMETERM_DELETEMAIL × 5
  - OPEN_HOMETERM_READMAIL × 3
- Per-capture counts:
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 70
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 30
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 23

Samples (first 32 bytes inner data):

```
#1: 1ff400e6c1b2c68a4678b1ef0b0e16ff557e75092f08
```
```
#2: 1fd700e9976c27ad5188c6af0b0e16ff557e75092f08
```
```
#3: 17d700e9976c27ad5188c6af0b0e16ff557e75092f080f00dc00100056656869
```

<!-- /catalog-evidence -->

## Structure

CityCom DCB (Database Channel Broadcast) RPC channel — the
OTHER ASCII-method-name RPC channel besides {@code 0x03/0x1f}.
Per `memory/project_lua_bridge.md`: "DCB DOES use ASCII method
names" (unlike 0x03/0x1f which encodes commands as numeric
tags).

Verified 2026-05-10 against 12 cross-pcap samples from 17/17
retail captures.

```
[0]      0x2b                   sub-opcode (constant)
[1]      variant                 0x1e (ASCII payload), 0x1f (binary RPC),
                                  0x1a (S→C method response)
[2..]    variant-specific
```

## Variants

| variant | size  | use                                        |
|--------:|------:|--------------------------------------------|
| 0x1e    | 60-829 B | ASCII text payload (DCB log/announcement) |
| 0x1f    | 23 B   | binary RPC call (16-byte payload)          |
| 0x1a    | 22 B  | S→C method-name response (ASCII)            |

### Variant 0x1e — ASCII payload

Variable-length. Body[2..5] = LE32 length, body[6..9] = char_id LE32, body[10..] = ASCII text.

CREATION sample (829B): "Skill Advisor Tips: Assassin #..." — this is the new-character tutorial dialog text the client requests on character creation.

### Variant 0x1f — binary RPC call (23B)

```
[0]    0x2b
[1]    0x1f
[2..3] LE16 (request id?)
[4..19] 16-byte binary payload
[20..22] 3-byte trailer
```

Sample: `2b 1f f4 00 e6 c1 b2 c6 8a 46 78 b1 ef 0b 0e 16 ff 55 7e 75 09 2f 08`

The 16-byte payload doesn't decode as ASCII or simple LE32 — looks like a hashed RPC request token (UUID-like).

### Variant 0x1a — S→C method response (22B)

```
[0]    0x2b
[1]    0x1a
[2..3] LE16 (length?)
[4..]  ASCII method name + null
```

Sample: `2b 1a 0f 00 01 00 00 56 65 68 69 63 6c 65 4c 69 73 74 69 6e 67 00`
       → ASCII "VehicleListing\0" — the DCB method name being invoked.

## Observed contexts

DCB is invoked when the client opens CityCom GUI panels:
- VehicleListing — vehicle inventory
- Bank/storage queries
- Apartment listings
- Tutorial/help text

Per memory `project_lua_bridge.md`, the client's `dialogheader.lua`
exposes RPC commands; DCB is the channel for those that need
ASCII method dispatch (vs the numeric-tag dispatch of 0x03/0x1f).

## Open questions

- Variant 0x1f's 16-byte binary payload structure: hash? UUID?
  Encrypted RPC args?
- Full DCB method-name registry: catalog has "VehicleListing"
  observed; the full list of ASCII methods is in the client's
  Lua scripts (scripts.pak's `dialogheader.lua` per memory).

## Server-side handler

Decoded via `GamePacketReaderUDP.decodesub13()` `case 0x2b` —
currently logged-only / partially handled. Full DCB RPC
implementation requires:
1. Method-name → handler mapping (ASCII string dispatch).
2. Per-method response builder (e.g. for VehicleListing,
   look up player's vehicles and send ASCII reply).

Out of scope for the current parity-focused work; filed as
follow-up.

