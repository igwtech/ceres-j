# Retail Login Burst Analysis

**Date**: 2026-04-12
**Source**: `strace/nc2_strace_RETAIL_ACC1_CHAR1.log` (88 S→C packets)
**Zone**: pepper/pepper_p3 (retail server 157.90.195.74)
**Tools**: `tools/decrypt-retail.py`, `tools/parse-burst.py`

## Key findings

### 1. Retail does NOT send ZoningEnd

Searched all 88 packets for `0x03→0x08` sub-packet: **none found**.
Ceres-J sends ZoningEnd as the terminator of its burst. Retail doesn't
use it during login. The client must transition state based on
receiving sufficient data, not a terminator packet.

### 2. Sub-packet length is 2 bytes LE in retail, 1 byte in Ceres-J

PROTOCOL.md documented 1-byte sub-packet lengths. Retail uses 2-byte
LE lengths inside the `0x13` gamedata payload. Ceres-J's
`PacketBuilderUDP13` writes 1-byte. The modern client appears to accept
both, but this difference matters for large packets (> 255 bytes).

### 3. No 0x19/0x04, 0x19/0x07, or 0x03 SyncResponse in retail burst

All three packet types we experimented with (WorldInfoSrv,
CommandIdAck, SyncResponse) are **absent** from the retail burst.
The state machine advances through a different mechanism.

### 4. Sub-type 0x02 is an undocumented reliable wrapper variant

Retail sends sub-type `0x02` sub-packets that appear to be a simplified
reliable wrapper (same structure as `0x03` but different type byte):

```
byte 0:    0x02
bytes 1-2: sequence counter (LE)
byte 3:    reliable sub-type (same table as 0x03 sub-types)
bytes 4+:  inner data
```

Found in packet #16 (cnt=35) with inner sub-types: 0x23 (InfoResp),
0x2e (Weather), 0x2f (UpdateModel), 0x1f (GamePkt/TimeSync).
Repeated in packet #22 (cnt=68) with identical structure.
Appears to be the server's primary data delivery mechanism for
non-bulk sub-packets.

### 5. Retail CharInfo is 10 multipart fragments (4310B), Ceres-J sends 3 (472B)

Retail sends 10 × `0x03→0x07 Multipart` sub-packets of 431B each,
totaling 4310 bytes of CharInfo data. Ceres-J sends 3 multipart
fragments totaling only ~472B. If the client's CharInfo parser expects
more data, the incomplete parse could prevent sync bits from setting.

### 6. All 38 GamePacket (0x1f) inner opcodes are TimeSync (0x01)

None contain the state-advancing opcodes (0x19, 0x03, etc.) we were
looking for. The 0x1f GamePackets are just regular time sync keep-alives.

## Retail initial burst sequence (first 12 packets)

```
pkt# 1  cnt= 14  Multipart(431B)     CharInfo fragment 0
pkt# 2  cnt= 15  Multipart(431B)     CharInfo fragment 1
pkt# 3  cnt= 16  Multipart(431B)     CharInfo fragment 2
pkt# 4  cnt= 17  Multipart(431B)     CharInfo fragment 3
pkt# 5  cnt= 18  Multipart(431B)     CharInfo fragment 4
pkt# 6  cnt= 19  Multipart(431B)     CharInfo fragment 5
pkt# 7  cnt= 20  Multipart(431B)     CharInfo fragment 6
pkt# 8  cnt= 21  Multipart(431B)     CharInfo fragment 7
pkt# 9  cnt= 22  Multipart(431B)     CharInfo fragment 8
pkt#10  cnt= 23  Multipart(361B)     CharInfo fragment 9 (final, shorter)
pkt#11  cnt= 25  InfoResp(6B) + TimeSync(12B)
pkt#12  cnt= 33  ChatList(2B) + InfoResp(10B) + GamePkt/TimeSync(5B)
                  + 3× Group1B + 2× NPCData + 15× position broadcasts
```

After the initial burst: WorldInfo × 36, NPCData × 52, position
broadcasts × 122, CPing × 5, ongoing TimeSyncs × 38.

## Sub-packet type histogram (retail)

| Count | Sub-type | Name |
|---|---|---|
| 122 | 0x1b raw | NPC/item position broadcasts |
| 52 | 0x03→0x2d | NPCData |
| 38 | 0x03→0x1f | GamePkt (all TimeSync) |
| 36 | 0x03→0x28 | WorldInfo |
| 10 | 0x03→0x07 | Multipart (CharInfo) |
| 5 | 0x0b | CPing |
| 3 | 0x03→0x1b | Group1B |
| 2 | 0x03→0x23 | InfoResponse |
| 1 | 0x03→0x0d | TimeSync |
| 1 | 0x03→0x25 | PlayerInfo |
| 1 | 0x03→0x2e | Weather |
| 1 | 0x03→0x33 | ChatList |
| 24 | 0x02 raw | Simplified reliable (undocumented) |
| 21 | misc raw | Various (0x00, 0x01, 0x06, 0x13, 0x22, etc.) |

## Ceres-J burst (for comparison)

| Count | Sub-type | Name |
|---|---|---|
| 7 | 0x04 | UDPAlive |
| 3 | 0x03→0x07 | Multipart (CharInfo) |
| 1 | 0x03→0x2f | UpdateModel |
| 1 | 0x03→0x0d | TimeSync |
| 1 | 0x03→0x2c | StartPos |
| 1 | 0x03→0x2e | Weather |
| 1 | 0x03→0x25 | PlayerInfo |
| 1 | 0x03→0x30 | ShortPlayerInfo |
| 1 | 0x03→0x1b | PosUpdate |
| 1 | 0x03→0x08 | ZoningEnd |

## What Ceres-J is missing (most likely to matter)

1. **InfoResponse (0x03→0x23)** — retail sends 2 of these in the
   initial burst. One contains `20 00 10 00 00 00` (6B), the other
   `0e 00 00 00 00 00 00 00 01 00` (10B). These may set session
   flags the state machine needs.

2. **0x02 reliable wrapper sub-packets** — 24 total, containing
   UpdateModel, Weather, InfoResponse, TimeSync. The client may
   expect data via 0x02 sub-type, not 0x03.

3. **Sufficient CharInfo data** — 10 fragments vs our 3. If CharInfo
   is incomplete, sync bit 1 (CharsysInfo) might not set.

4. **NO ZoningEnd** — our explicit terminator may confuse the state
   machine if the client doesn't expect it.

5. **ChatList (0x03→0x33)** — retail sends `ff 00` (2 bytes). We don't.

## Recommended next experiments

1. ~~**Remove ZoningEnd** from WorldEntryEvent and test.~~ **DONE 2026-04-12.**
2. ~~**Add InfoResponse (0x23)**~~ **DONE** — `InfoResponse.zoneInfo()` (6B)
   and `InfoResponse.sessionInfo()` (10B) now wired into `WorldEntryEvent`.
3. **Test 0x02 wrapper** — send UpdateModel/Weather/TimeSync inside
   0x02 sub-packets instead of (or in addition to) 0x03. **NOT YET DONE** —
   needs a new `PacketBuilderUDP1302` (unreliable variant) and per-packet
   wrapper selection. Deferred until after confirming 0x03 works.
4. **Increase CharInfo fragment count** to match retail's 10 fragments.
   **NOT YET DONE** — our CharInfo multipart produces 3 fragments because
   our character data is smaller than retail's. May need padding or a
   minimum-data requirement if the client doesn't accept fewer fragments.
5. ~~**Add ChatList (0x33)**~~ **DONE** — `ChatList` wired into
   `WorldEntryEvent`.

## Additional changes implemented (2026-04-12)

6. **Cipher implemented server-side** — `WireEncrypt.java` and
   `GameServerUDPConnection.sendPacket()` now encrypt every outgoing
   UDP datagram with the 4-byte header. This was not on the original
   list but turned out to be critical: plaintext bytes were misrouted
   by the WinSockMGR session layer's `-0x0F` offset on byte 0.

## Still pending

- ~~2-byte sub-packet lengths in `PacketBuilderUDP13`~~ **DONE 2026-04-12**.
  After enabling encryption, the client reported
  `GAMENETMGR [NetUpdate]: Corrupted packet. Stated size of 843 bytes
  is larger than message size 76 bytes` — confirming the client expects
  2-byte LE length. `PacketBuilderUDP13` now writes 2-byte LE lengths;
  all 111 tests updated.
- 0x02 unreliable wrapper (see #3 above).
- Padding CharInfo to match retail's 10-fragment size.

## Files

- `tools/decrypt-retail.py` — cipher implementation + decryptor
- `tools/parse-burst.py` — sub-packet parser + comparator
- `docs/retail_decoded_burst.txt` — full 88-packet decode
- `strace/nc2_strace_RETAIL_ACC1_CHAR1.log` — source capture
