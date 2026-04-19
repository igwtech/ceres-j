# Retail Long Session Analysis (60 seconds)

**Capture**: `strace/nc2_strace_RETAIL_ACC2_CHAR2_LONGCAPTURE.log`
**Date**: 2026-04-18
**Duration**: ~60 seconds of active gameplay (walk + look around)
**Packets**: 2415 UDP (S→C: 673, C→S: 1742)

## S→C Traffic Summary (server sends to client)

| Sub-type | Count | Rate | Bytes | Purpose |
|---|---:|---:|---:|---|
| `0x1b raw` | 2862 | 47.7/s | 54378 | NPC/object position broadcasts (19B each) |
| `0x03→0x2d NPCData` | 681 | 11.4/s | 34278 | NPC state updates (reliable) |
| `0x03→0x1f GamePackets` | 284 | 4.7/s | 2559 | TimeSync heartbeat (opc=0x25) |
| **`0x32 raw`** | **182** | **3.0/s** | **1638** | **NPC attribute updates (9B each)** |
| `0x03→0x28 WorldInfo` | 112 | 1.9/s | 4401 | World object info (reliable) |
| **`0x20 Movement raw`** | **78** | **1.3/s** | **1014** | **NPC movement echo (13B each)** |
| **`0x1f raw`** | **54** | **0.9/s** | **570** | **Pool status (HP/PSI/STA) broadcast** |
| `0x02→0x1f GamePackets` | 45 | 0.8/s | 414 | 0x02 wrapper TimeSync |
| `0x03→0x32` | 41 | 0.7/s | 492 | NPC attribute (reliable variant) |
| `0x0b CPing` | 40 | 0.7/s | 368 | Ping reply |

## Decoded Sub-packet Formats

### 0x32 raw (NPC attribute, 9 bytes)
```
32 [npc_id] [03/05] [04/05] [00 00] [float LE 3B]
```
- Constant size: 9 bytes
- npc_id: 0xf8-0xfc range (5 distinct NPCs)
- Sub-types 0x03/0x04/0x05: attribute selectors
- Float value: NPC health bar or status (22.0, 40.5 observed)

### 0x20 Movement S→C (NPC movement, 13 bytes)
```
20 [type_flags] [mapId_lo] [Y LE2] [Z LE2] [X LE2] [orient] [status] [00]
```
- Same format as client C→S 0x20 movement
- type_flags: 0x01 (Y only) or 0x21 (Y + status)
- Position: 2B each for Y/Z/X (signed short + 32000)
- Appears to be NPC movement, not player self-echo

### 0x1f raw (pool status, 8-14 bytes)
Two variants:
```
14B: 1f 01 00 30 8a [00 b2] [00 4f] [00 8c 01] [8c 01]
     sub=0x1f variant=0x01 0x00 opcode=0x308a
     Possibly: HP=178, PSI=79, STA=396

 8B: 1f [e9 01] 56 00 00 00 00
     sub=0x1f [counter LE2] opcode=0x56 + zeros
```

## Key Finding for Ceres-J

Retail's ongoing traffic during gameplay consists of:
1. High-frequency NPC broadcasts (0x1b + 0x2d) — ~60/s combined
2. Medium-frequency NPC movement/attribute updates (0x20 + 0x32) — ~4/s combined
3. Low-frequency player pool status updates (0x1f raw) — ~1/s
4. Heartbeats (0x03→0x1f opc=0x25) — ~5/s

Ceres-J currently sends only heartbeats + compound 0x1b/0x2d/0x28 at 2 Hz.
The **0x1f raw pool status** and **0x32 NPC attribute** updates are entirely
missing. The **0x20 S→C movement** (NPC positions) is also missing.

The 0x1f raw pool status is likely the most impactful missing piece —
the client may require periodic confirmation that its own HP/PSI/STA
values are valid.
