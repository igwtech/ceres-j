# `UDP C->S 0x2a` — RequestPos

**Transport:** UDP  
**Direction:** C->S  
**Identifier:** `0x2a`  
**Status:** verified  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **87**
- Captures with this packet: **17/17**
- Size (bytes): min **3**, avg **14**, max **16**
- Top markers (within ±2s):
  - AFTER_ENTER_SEWER × 1
  - IN_WORLD × 1
- Per-capture counts:
  - `RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715` × 34
  - `RETAIL_CREATION_LEVELING_LONG_20260502_160841` × 8
  - `RETAIL_RETAIL_LONG_PARTY_A_20260503_130137` × 7
  - `RETAIL_RETAIL_LONG_PARTY_B_20260503_130343` × 6
  - `RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639` × 5
  - `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` × 5
  - `RETAIL_ODA_20260426_202428` × 4
  - `RETAIL_HANNIBAL_20260426_201501` × 4
  - `RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513` × 3
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 3
  - `RETAIL_NORMAN_20260426_200458` × 2
  - `RETAIL_AUGUSTO_20260426_201952` × 1
  - `RETAIL_DRSTONE4_20260501_193336` × 1
  - `RETAIL_DRSTONE3_20260501_181349` × 1
  - `RETAIL_DRSTONE_20260501_172522` × 1
  - `RETAIL_DRSTONE_20260501_175315` × 1
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 1

Samples (first 32 bytes inner data):

```
#1: 2ab0a200007f763b69f12063ed2c0900
```
```
#2: 2ab0a200001eef3bcebab302f6120a00
```
```
#3: 2ab0a200005cd536e6e0da776f8d0b00
```

<!-- /catalog-evidence -->

## Structure

Byte-pinned task #169 from **98 C→S bare-outer 0x2a sub-packets**
swept across all 18 retail pcaps in `strace/` (LFSR-decrypted,
0x13-burst split via `tools/parse-burst.py`; reproducible with the
sweep methodology in `tools/catalog_extract.py`). 0x2a never
appears as a reliable `0x03→0x2a` inner — it is **always a bare
outer sub-packet** (0 reliable observations).

```
[0]       0x2a                  sub-opcode (all 98 samples)
[1..4]    character_uid  LE32   session-stable per character
[5..15]   request_token  11 B   opaque enumerated request id
                                 (16-byte form only)
```

### Length distribution (98 obs, exact)

| len | count | form                                   |
|-----|-------|----------------------------------------|
| 3   | 1     | framing artifact (`2a 43 1f`)          |
| 5   | 11    | header-only: `0x2a` + UID, no token    |
| 16  | 86    | header + 11-byte request token         |

This **disproves** the prior "fixed 16-byte, no size variation"
note: three lengths are observed.

- **16-byte form** (×86) — e.g.
  `2abd7e010005ae73323a76d49d0e0900`
  (RETAIL_NORMAN_20260426_200458),
  `2ab0a200009484f3511dd2a1fa150c00`
  (RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715),
  `2a1a7f0100fce02ddbff6dd90eb90a00`
  (RETAIL_CREATION_LEVELING_LONG_20260502_160841).
- **5-byte header-only form** (×11) — e.g. `2a1a7f0100`
  (RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639), `2ab0a20000`,
  `2abd7e0100`.
- **3-byte outlier** — a single `2a431f` in
  RETAIL_CREATION_LEVELING_LONG_20260502_160841. `0x1f` is the
  next sub-packet's opcode (GamePackets) bleeding in on a
  sub-packet length desync — **not** a real 3-byte variant. The
  decoder ignores it (uid = -1, token = null).

### character_uid is session-stable (LE32)

Exactly **3 distinct UIDs** across all 98 obs, each constant
within its sessions:

| UID          | obs | captures (examples)                 |
|--------------|-----|-------------------------------------|
| `0x0000a2b0` | 50  | VEHICLE_DRONE, ODA, PLAZA, PARTY_A  |
| `0x00017ebd` | 31  | AUGUSTO, NORMAN, CASH, FULL, HANNIBAL|
| `0x00017f1a` | 16  | CREATION, DRSTONE/3/4, CHARDEL_SUBWAY|

Byte order is LE32: `bd 7e 01 00` → `0x00017ebd` (not
`0xbd7e0100`).

### request_token is an enumerated id, NOT session state

Of the 34 distinct 11-byte tokens in the 16-byte form, **9 recur
verbatim under two different UIDs** (different characters /
unrelated sessions), e.g.:

| token (hex)                | UIDs                       |
|----------------------------|----------------------------|
| `9484f3511dd2a1fa150c00`   | `0xa2b0`, `0x17ebd`        |
| `05ae73323a76d49d0e0900`   | `0xa2b0`, `0x17ebd`        |
| `3b08cbf9f3169873fb0a00`   | `0x17f1a`, `0x17ebd`       |
| `1eef3bcebab302f6120a00`   | `0xa2b0`, `0x17ebd`        |

A token that appears byte-identical for two different characters
**cannot** encode that character's state — it is drawn from a
fixed client-side enumeration. (Near-pair
`1eef3bcebab302f6120a00` vs `…0a01`, same UID `0xa2b0`,
RETAIL_RETAIL_LONG_PARTY_A, differ only in the final byte —
consistent with an enumerated/sequence-tagged token, not a hash
of mutable state.) The exact per-token semantic is not
recoverable from these captures (the bytes are opaque).

## Variants

Three observed body lengths (3 / 5 / 16 B) — see table above. The
5-byte and 16-byte forms are both valid; the 3-byte case is a
framing artifact.

## Observed contexts

The S→C traffic following a C→S 0x2a was swept in NORMAN, AUGUSTO,
DRSTONE3 and PLAZA (S→C sub-ops within 1.5 s of each 0x2a). In
every capture it is the **normal ambient world stream** —
`0x1b`/`0x03→0x1b` position ticks, `0x03→0x28` WorldInfo,
`0x03→0x2d` NPCData, `0x02` reliable retransmits, `0x0b` ping,
`0x03→0x0d`/`0x03→0x33` timesync/keepalive. There is **no
deterministic CharInfo (`0x03→0x07`/`0x03→0x2c`) burst keyed to
0x2a**: `0x03→0x2c` appears at most 0–2× and `0x03→0x07` is
sporadic and present even where 0x2a is not the trigger.

Conclusion: 0x2a is best modelled as a **client liveness / resync
poll that the server decodes and consumes**; retail does NOT
re-deliver CharInfo on every 0x2a. The earlier "full refresh vs
init-burst per session" hypothesis is not supported by the
post-0x2a S→C evidence — the stream is the same regardless of
session.

## Open questions

- Per-token semantic of `request_token[5..15]`: which client
  resync trigger each enumerated value corresponds to. Not
  recoverable from opaque token bytes in the current corpus.
- Frequency: ~1 emit per session in most captures, multiple in
  long-session captures — consistent with a periodic / on-resync
  client poll.

## Server-side handler

Decoded via {@link
server.gameserver.packets.client_udp.RequestPositionUpdate}
(routed in `GamePacketReaderUDP.decodesub13` `case 0x2a`, mirroring
the sibling `case 0x31 → RequestShortPlayerInfo`; the decoded event
is queued via `pl.addEvent(ev)` and `execute(Player)` runs it):

- `skip(1)` past `0x2a`, then `character_uid = readInt()` LE32
  (body ≥ 5 B), then 11-byte `request_token` (body == 16 B). The
  3-byte framing artifact falls through with uid = -1, token =
  null and does not throw.
- `execute()` emits the established NORMAN-pcap-replay-validated
  triple — {@link
  server.gameserver.packets.server_udp.PositionUpdate} + {@link
  server.gameserver.packets.server_udp.CharInfo} + {@link
  server.gameserver.packets.server_udp.InfoResponse#zoneInfo}.

The `InfoResponse#zoneInfo` emit here is retail-attested at NORMAN
pcap-replay step 9 (task #166) and is the reason `GetTimeSync`
deliberately does NOT emit zoneInfo. The CharInfo re-send is kept
(and self-flagged `TODO workaround` in source) because the
existing replay-harness and functional tests pin this emit order;
the post-0x2a S→C sweep above shows retail does not actually
require it, but altering the emit path risks regressing the
NORMAN/zone-handoff replay ground truth (tasks #166/#172) and is
explicitly out of scope for the #169 *decode* deliverable.

