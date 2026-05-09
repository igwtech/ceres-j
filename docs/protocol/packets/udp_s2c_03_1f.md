# `UDP S->C 0x03/0x1f` — Reliable/GamePackets

**Transport:** UDP  
**Direction:** S->C  
**Identifier:** `0x03/0x1f`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **36537**
- Captures with this packet: **17/17**
- Size (bytes): min **4**, avg **5**, max **821**
- Top markers (within ±2s):
  - BASELINE_HUD × 36
  - KILL_MOB × 36
  - KILL_MOB2 × 36
  - POKE_START × 36
  - IN_SUBWAY_CAR × 29
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 10070
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 7308
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 5925
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 5867
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 2156
  - `RETAIL_NORMAN_20260426_200458` × 946
  - `RETAIL_ODA_20260426_202428` × 813
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 759
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 519
  - `RETAIL_DRSTONE_20260501_175315` × 479
  - `RETAIL_HANNIBAL_20260426_201501` × 466
  - `RETAIL_AUGUSTO_20260426_201952` × 443
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 310
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 178
  - `RETAIL_DRSTONE4_20260501_193336` × 135
  - `RETAIL_DRSTONE3_20260501_181349` × 82
  - `RETAIL_DRSTONE_20260501_172522` × 81

Samples (first 32 bytes inner data):

```
#1: 0400252333
```
```
#2: 0400252333
```
```
#3: 0400252333
```

<!-- /catalog-evidence -->

## Structure

S→C side of the GamePackets multiplexer. Verified 2026-05-09
against 48 samples drawn from 6 retail captures (AUGUSTO, CASH,
CREATION, DRSTONE, DRSTONE3, DRSTONE4).

Compared to the C→S side (188K obs, heartbeat-dominated), the S→C
side is much more focused — **almost all traffic is `tag 0x25`
"object/inventory event" responses** (48/48 samples).

**Body layout (post {@code 0x1f} sub-op):**

```
[0]      0x1f                    sub-opcode (constant)
[1..2]   LE16 prefix             0x0001 dominant; 0x0002 in DRSTONE —
                                  appears to be a session counter
[3]      tag 0x25                "object/inventory event" — 48/48 samples
[4]      sub-action              0x23 (44×), 0x22 (3×), 0x1e (1×)
[5..N]   payload                 sub-action-specific
```

### 6-byte form — `tag 0x25 / sub-action 0x23` (92% of S→C traffic)

```
1f [01/02 00] 25 23 [trailer-byte]
```

The trailing byte is capture-stable but varies per session:
AUGUSTO=0x32, CASH=0x25, CREATION=0x2b, DRSTONE3=0x29, DRSTONE4=0x29.
Likely a session-state ack/counter the client uses to confirm a
prior 0x25-class C→S event was processed.

### 19-byte variant — `tag 0x25 / sub-action 0x22`

Long-form item-grant or vendor-receipt payload:

```
1f 01 00 25 22 5d 06 9a 03 9a 03 90 01 f8 02 e9 02 ff 00
```

Body decode (post-`25 22`):
- `5d 06` LE16 = 0x065d = 1629 — item ID or transaction ref
- `9a 03 9a 03 90 01 f8 02 e9 02` 5×LE16 = item attribute pairs?
- `ff 00` trailer

Verified 2026-05-09 in AUGUSTO (3 occurrences, all identical hex —
likely an inventory snapshot replay).

### 10-byte variant — `tag 0x25 / sub-action 0x1e` (InventoryMove echo)

```
1f 01 00 25 1e b6 cc 01 00 0f
```

Server echo of the C→S `1e` InventoryMove — the body is the
moved-item's container reference + slot.

## Variants

| size | sub-action | count | meaning                                  |
|-----:|-----------:|------:|------------------------------------------|
| 6 B  | 0x25/0x23  |    44 | session-state ack (capture-stable byte) |
| 19 B | 0x25/0x22  |     3 | item-grant / inventory snapshot          |
| 10 B | 0x25/0x1e  |     1 | InventoryMove server echo                |

Cross-reference (memory {@code cash_and_falldamage_subops.md}):
- 0x25/0x13 — cash update carrier (11B form, body = {@code [txn
  LE2][04][cash LE32]}). Not in this 48-sample subset (cash
  events are rare per capture) but verified separately.
- 0x25/0x22 — item-receipt / inventory snapshot
- 0x25/0x1e — InventoryMove server echo
- 0x25/0x23 — generic ack/state-update (most common)

## Observed contexts

Same broad gameplay-event context as the C→S side, but specifically
the S→C ack/result side. The `0x25/0x23` ack pattern correlates
with most player-action steps (use object, move inventory, vendor
buy, etc.) — server confirms the action was processed.

Per-capture concentration: RETAIL_VEHICLE_DRONE × ?, CREATION ×
many — all combat/PvP heavy captures. Smaller captures (DRSTONE3,
DRSTONE4, CASH_VENDOR) emit fewer.

## Open questions

- Trailing byte of 0x25/0x23: 0x29/0x2b/0x32/0x25 — what does this
  encode? Looks session-stable but varies per session, suggesting
  a session token, character class flag, or last-action ack code.
- 19-byte 0x25/0x22 sample appears IDENTICAL across 3 AUGUSTO
  occurrences — is this server idempotently replaying the same
  inventory snapshot, or was the test capture exercising a single
  item interaction?
- Tag space beyond 0x25: in the catalog, S→C 0x03/0x1f has 36K obs
  but our 48-sample subset shows ONLY 0x25. Other tags exist (0x13
  cash, 0x29 mission, etc. per memory) — under-sampled here.

## Server-side handler

Ceres-J **emits** S→C 0x03/0x1f via several event-specific
builders:
- `server.gameserver.packets.server_udp.CashUpdate` — 0x25/0x13
  cash carrier (verified bytes per memory)
- `server.gameserver.packets.server_udp.OpenDoor` — 0x1f-class
  door interactions
- Various other GamePacket sub-tag emitters

Ceres-J **decodes** C→S 0x03/0x1f via
`GamePacketReaderUDP.decodesub13` `case 0x1f:` switch (see
`udp_c2s_03_1f.md`). The S→C side has no decode handler — these
are server emits driven by gameplay events.

