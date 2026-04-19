# Neocron 2 Packet Reference

Comprehensive byte-level reference for every packet type observed in retail
NCE 2.5.766 captures. All data sourced from strace captures of the retail
client communicating with retail server `157.90.195.74:5005`.

**Captures used:**

| Capture | File | Packets | Duration |
|---------|------|---------|----------|
| Long session | `nc2_strace_RETAIL_ACC2_CHAR2_LONGCAPTURE.log` | 2415 S2C, 1742 C2S | ~3 min |
| Death session | `nc2_strace_RETAIL_DEATH.log` | 1762 S2C | ~2 min |
| ACC1_CHAR1 | `nc2_strace_RETAIL_ACC1_CHAR1.log` | 160 S2C | ~20s login |
| ACC1_CHAR2 | `nc2_strace_RETAIL_ACC1_CHAR2.log` | short | login only |
| ACC2_CHAR1 | `nc2_strace_RETAIL_ACC2_CHAR1.log` | short | login only |
| ACC2_CHAR2 | `nc2_strace_RETAIL_ACC2_CHAR2.log` | short | login only |

---

## Table of Contents

1. [Wire Format](#1-wire-format)
2. [TCP Packets](#2-tcp-packets)
3. [UDP Outer Packet Types](#3-udp-outer-packet-types)
4. [Gamedata (0x13) Container](#4-gamedata-0x13-container)
5. [S2C Raw Sub-packets](#5-s2c-raw-sub-packets)
6. [S2C Reliable (0x03) Sub-packets](#6-s2c-reliable-0x03-sub-packets)
7. [S2C Simplified Reliable (0x02) Sub-packets](#7-s2c-simplified-reliable-0x02-sub-packets)
8. [C2S Raw Sub-packets](#8-c2s-raw-sub-packets)
9. [C2S Reliable (0x03) Sub-packets](#9-c2s-reliable-0x03-sub-packets)
10. [Death Sequence](#10-death-sequence)

---

## 1. Wire Format

Every UDP datagram is encrypted with a per-packet LFSR+CFB cipher.

```
Wire:     [seed_lo][seed_hi][enc_len_lo][enc_len_hi][enc_data...]
           ^^^^^^^ clear     ^^^^^^^^^^^^^^^^^^^^^^^^ encrypted
Plaintext: [data_len_lo][data_len_hi][plaintext_data...]
```

Total wire size = plaintext_data_length + 4 bytes.

See `docs/PROTOCOL.md` for the full LFSR PRNG specification.

---

## 2. TCP Packets

All TCP packets use the FE framing: `[0xFE][len_lo][len_hi][pkt_id_hi][pkt_id_lo][payload...]`.
Length field includes the 2-byte packet ID.

### 2.1 HandshakeA (0x8001) S2C

```
Offset  Size  Field              Example
0x00    2     Packet ID          83 01  (swapped in frame as 80 01)
0x02    1     Unknown            66
```

Retail sample: `fe 03 00 80 01 66`

### 2.2 HandshakeB (0x8000) C2S

```
Offset  Size  Field              Example
0x00    2     Packet ID          80 00
0x02    1     Unknown            78
```

Retail sample: `fe 03 00 80 00 78`

### 2.3 HandshakeC (0x8003) S2C

```
Offset  Size  Field              Example
0x00    2     Packet ID          80 03
0x02    1     Unknown            68
```

Retail sample: `fe 03 00 80 03 68`

### 2.4 Auth (0x8480) C2S

```
Offset  Size  Field              Example
0x00    2     Packet ID          84 80
0x02    1     Encryption key     29
0x03    30    Unknown block      23 be 84 e1 6c d6 ae ...
0x21    2     Username length    12 00 (18, includes null)
0x23    2     Password length    09 00
0x25    N     Username (C-str)   "msn3wolf\0"
0x25+N  M     Encrypted password (shorts: ((char+key)<<4))
```

Retail sample (65B payload):
`29 23 be 84 e1 6c d6 ae fe 02 01 7d 5c 85 98 06 b7 47 5c 00 00 d0 00 a5 ac 54 43 81 89 0d 0d 09 00 12 00 ... 6d 73 6e 33 77 6f 6c 66 00`

### 2.5 AuthAck (0x8381) S2C

```
Offset  Size  Field              Example
0x00    2     Packet ID          83 81
0x02    4     Account ID (LE)    bd 7e 01 00  (97981)
0x06    4     Unknown            00 00 00 00
0x0A    2     Password len echo  12 00
0x0C    18    Session token      c2 99 99 f9 b1 b9 59 e9 73 a9 4b 39 b7 05 4e 99 44 59
```

Retail sample (32B payload):
`bd 7e 01 00 00 00 00 00 12 00 c2 99 99 f9 b1 b9 59 e9 73 a9 4b 39 b7 05 4e 99 44 59 ed`

### 2.6 GetCharList (0x8482) C2S

```
Offset  Size  Field              Example
0x00    2     Packet ID          84 82
0x02    4     Account ID (LE)    bd 7e 01 00
0x06    2     Slot count         01 00
0x08    2     Unknown            00 00
0x0A    2     Password length    1e 00
0x0C    N     Password (enc)     ...
```

Retail sample (31B payload).

### 2.7 CharList (0x8385) S2C

```
Offset  Size  Field              Example
0x00    2     Packet ID          83 85
0x02    2     Unknown            00 00  or  01 00
0x04    2     Slot count         04 00  (4 character slots)
0x06    N     Character data     Per-slot: [name_len(1)][name][char_data...]
```

Character slot fields (repeated `slot_count` times):
```
0x00    2     Data length        29 00 (41 bytes)
0x02    4     Character ID (LE)  5a 86 01 00 (99930)
0x06    2     Unknown            d0 02
0x08    2     Profession         34 03
0x0A    2     Unknown            98 03
0x0C    1     Gender             05
0x0D    1     Unknown            04
0x0E    2     Unknown            60 04
0x10    2     Unknown            8c 0a
0x12    2     Unknown            be 0a
0x14    2     Unknown            f0 0a
0x16    2     Zone ID            91 01
0x18    2     Unknown            00 00
0x1A    1     Flags              08
0x1B    4     Unknown            01 01 01 01
... followed by character name data, faction info, etc.
```

Strings found in payload: character names ("Krafteo", "Norman Gates", "Hannibal Lecture", "Father Augusto").

Retail sample (225B payload, 4 character slots).

### 2.8 AuthB (0x8301) C2S -- Character Selection

```
Offset  Size  Field              Example
0x00    2     Packet ID          83 01
0x02    4     Unknown (IP?)      ac 1b e9 a0
0x06    4     Client port        00 00 00 00
0x0A    1     Encryption key     e5
0x0B    7     Unknown            87 4a 3b 32 9b 61 40
0x12    4     Char slot (LE)     00 00 00 00
0x16    2     Password length    12 00
0x18    2     Username length    09 00
0x1A    N     Username           "msn3wolf\0"
0x1A+N  M     Encrypted password ...
```

Retail sample (54B payload).

### 2.9 UDPServerData (0x8305) S2C

```
Offset  Size  Field              Example           Decoded
0x00    2     Packet ID          83 05
0x02    4     Account ID (LE)    bd 7e 01 00       97981
0x06    4     Character ID (LE)  5a 86 01 00       99930
0x0A    4     Server IP (net)    9d 5a c3 4a       157.90.195.74
0x0E    2     UDP port (LE)      90 13             5008
0x10    4     Flags              00 00 00 04
0x14    6     Session ID         1a 54 d7 d2 d8 d2
```

Note: The session ID bytes are transformed by the client: `127 - byte` for each.

Retail sample: `bd7e01005a8601009d5ac34a9013000000041a54d7d2d8d2`

### 2.10 GetGamedata (0x8737) C2S

```
Offset  Size  Field              Example
0x00    2     Packet ID          87 37
0x02    4     Unknown            00 00 00 00
```

Retail sample (4B payload): `00 00 00 00`

### 2.11 Gamedata Response (0x873a) S2C

```
Offset  Size  Field              Example
0x00    2     Packet ID          87 3a
```

Empty payload observed in retail.

### 2.12 Location (0x830c) S2C

```
Offset  Size  Field              Example           Decoded
0x00    2     Packet ID          83 0c
0x02    4     Zone ID (LE)       91 01 00 00       401
0x06    4     Unknown            00 00 00 00       (0 normally)
0x0A    4     Unknown            00 00 00 00
0x0E    N     World path (C-str) "viarosso/viarosso_p3\0"
```

Retail samples:
- Zone 401 (Viarosso P3): `91010000 00000000 00000000 766961726f73736f2f766961726f73736f5f703300`
- Zone 4 (Viarosso P2): `04000000 00000000 00000000 766961726f73736f2f766961726f73736f5f703200`
- Zone 348213 (VR app): `37744c05 01000000 00000000 617070732f76725f6170705f3100`

### 2.13 GameinfoReady (0x830d) S2C

```
Offset  Size  Field              Example
0x00    2     Packet ID          83 0d
0x02    2     Unknown            00 00
```

Retail sample (2B payload): `00 00`. Sent after zone data load is complete.
In the death capture, this was sent at `02:29:07` (after respawn zone change to VR app,
then again for the new zone).

### 2.14 TCP Keepalive (0x838f) S2C

```
Offset  Size  Field              Example
0x00    2     Packet ID          83 8f
0x02    5     Padding            00 00 00 00 00
```

Retail: sent every ~11 seconds. Observed 13 keepalives in the long session.

### 2.15 GetUDPConnection (0x873c) C2S

```
Offset  Size  Field              Example
0x00    2     Packet ID          87 3c
0x02    4     Unknown            00 00 00 00
```

Sent after UDPServerData received. No-op (server does not send a second UDPServerData).

---

## 3. UDP Outer Packet Types

After decryption, the first byte determines the packet type:

| Byte 0 | Name | Direction | Description |
|--------|------|-----------|-------------|
| 0x01 | Handshake | C2S | UDP session establishment (10B) |
| 0x03 | Sync | C2S | Synchronization / reliable delivery trigger (8B) |
| 0x04 | UDPAlive | S2C | Keepalive (7B with sub-packet length prefix) |
| 0x08 | Abort | C2S | Session termination (1B) |
| 0x13 | Gamedata | Both | Multiplexed game data container |

### 3.1 Handshake (0x01) C2S -- 10B

```
Offset  Size  Field              Example
0x00    1     Type               01
0x01    8     Session data       3e f4 8e 52 8e 52 9a 3e
0x09    1     Interface ID       00
```

Client sends 3 identical handshake packets immediately after receiving UDPServerData.
The 8-byte session data is derived from the session ID in UDPServerData (transformed
with `127 - byte`).

Retail samples:
```
013ef48e528e529a3e00    (ACC1 session)
01652ba8ada7ad0a8400    (ACC2 session)
```

### 3.2 Sync (0x03) C2S -- 8B

```
Offset  Size  Field              Example
0x00    1     Type               03
0x01    4     Session ID (LE)    bd 7e 01 00
0x05    1     Sequence           01
0x06    2     Checksum           72 b7
```

Retail samples:
```
03b0a20000015cca
03bd7e01000172b7
```

### 3.3 Abort (0x08) C2S -- 1B

```
Offset  Size  Field
0x00    1     Type               08
```

Sent when client disconnects.

---

## 4. Gamedata (0x13) Container

All real-time game data is multiplexed inside 0x13 packets.

```
Offset  Size  Field              Description
0x00    1     Type               0x13
0x01    2     Counter (LE)       Incrementing packet counter
0x03    2     Counter+Key (LE)   Counter + session key
0x05    ...   Sub-packets        Length-prefixed sub-packets
```

### Sub-packet framing (retail)

Each sub-packet is preceded by a 2-byte LE length:

```
[len_lo][len_hi][sub_packet_data...]
```

The first byte of `sub_packet_data` is the sub-packet type.

---

## 5. S2C Raw Sub-packets

These sub-packets appear directly inside the 0x13 container without a 0x03
reliable wrapper.

### 5.1 raw 0x1b -- Position Broadcast (19B)

**Frequency:** 2862x in long session (most common S2C sub-packet).
**Direction:** S2C. **Wrapper:** raw.

Broadcasts the position of every NPC and world item in the zone. Sent in
batches of 15-20 every ~1.2 seconds.

```
Offset  Size  Field              Example        Notes
0x00    1     Type               1b
0x01    1     World ID (lo)      1b             NPC/item world ID low byte
0x02    2     Map ID (LE)        01 00          Map ID = 1
0x04    2     Unknown/flags      00 1f          Constant 0x001f in all samples
0x06    2     X position         ba 89          16-bit packed X
0x08    1     X flags/hi bits    01             Position modifier
0x09    1     Y position (hi)    7f             16-bit packed Y high byte
0x0A    2     Y position (lo+?)  3b 81          Y position continuation
0x0C    1     Z flags            02             Z height modifier (02=ground, 40=air, 20=?)
0x0D    2     Z position         82 22          16-bit packed Z
0x0F    2     Padding            00 00          Always 0x00 0x00
0x11    1     Heading            3c             Heading angle (0-255)
0x12    1     State/flags        12             Entity state flags
```

The position values use 16-bit compressed coordinates. Bytes 6-14 encode
X, Y, Z in a packed format where the MSBs in bytes 8, 9, and 12 act as
range/precision modifiers.

Byte-per-offset analysis (271 samples from ACC1_CHAR1):
- Offset 0: CONSTANT `0x1b`
- Offset 1: 21 unique values (world IDs of entities in zone)
- Offset 2: CONSTANT `0x01` (map ID low)
- Offset 3: CONSTANT `0x00` (map ID high)
- Offset 4: CONSTANT `0x00`
- Offset 5: CONSTANT `0x1f`
- Offset 6-8: X position (variable)
- Offset 9: 2 unique values (`0x7f`, `0x7e` -- Y high byte)
- Offset 10-11: Y position
- Offset 12: 3 unique values (`0x40`, `0x02`, `0x20` -- Z modifier)
- Offset 13-14: Z position
- Offset 15-16: CONSTANT `0x00 0x00`
- Offset 17: heading (39 unique values)
- Offset 18: state flags (4 unique: `0x0f`, `0x11`, `0x12`, `0x13`)

Retail samples:
```
1b 1b 01 00 00 1f ba 89 01 7f 3b 81 02 82 22 00 00 3c 12
1b 1a 01 00 00 1f 2d 88 00 7f 80 83 40 37 23 00 00 ff 11
1b 0e 01 00 00 1f 18 89 00 7f c3 82 40 e3 7c 00 00 28 0f
1b 08 01 00 00 1f 03 89 1c 7f 81 81 02 14 22 00 00 31 0f
```

### 5.2 raw 0x32 -- NPC Attribute Update (9B)

**Frequency:** 182x in long session.
**Direction:** S2C (also sent C2S as echo). **Wrapper:** raw.

Updates a single attribute value for a specific NPC.

```
Offset  Size  Field              Example        Notes
0x00    1     Type               32
0x01    1     NPC world ID (lo)  fb             Same ID space as 0x1b
0x02    1     NPC world ID (hi)  03             Extended world ID
0x03    1     Attribute type     04             Attribute index (04=type A, 05=type B)
0x04    2     Unknown            00 00
0x06    1     Value (lo)         b0
0x07    1     Value (hi)         41
0x08    1     Flags              00             0x00 or 0x01 (changed flag)
```

Attribute type 0x04 and 0x05 are the two most common. The 2-byte value at
offset 6-7 appears to be a float16 or fixed-point value (e.g., `0x4120` =
10.0 as IEEE 754 half).

Retail samples:
```
32 fb 03 04 00 00 b0 41 00
32 fa 03 05 00 00 00 00 00    (value = 0)
32 f9 03 05 00 00 a0 41 00    (value = 0x41A0)
32 fc 03 04 00 00 00 00 00    (value = 0)
32 f8 03 05 00 00 20 42 00    (value = 0x4220 = 40.0f)
```

### 5.3 raw 0x20 -- NPC Movement (13B)

**Frequency:** 78x in long session.
**Direction:** S2C. **Wrapper:** raw.

Updates the position of a moving NPC/entity in the zone.

```
Offset  Size  Field              Example        Notes
0x00    1     Type               20
0x01    1     Move flags         01 or 21       0x01=walk, 0x21=stationary
0x02    1     NPC world ID       01             Which NPC
0x03    2     X position         fa 7e          Compressed X (same format as 0x1b)
0x04    2     Y position         20 7d          Compressed Y
0x06    2     Z position         bd 7b          Compressed Z
0x08    2     Heading/speed      c2 41          Heading + speed packed
0x0A    2     Unknown            00 00
0x0C    1     Padding            (end of packet)
```

Move flags `0x21` indicates a stationary NPC update (X/Y/Z stay constant
across samples). Move flags `0x01` indicates actual movement (position
values change between packets).

Retail samples:
```
20 21 01 d0 79 c0 7c 3b 7c 7b 07 00 00   (stationary, NPC 0x21, world_id=1)
20 01 01 fa 7e 20 7d bd 7b c2 41 00 00   (walking, NPC 0x01)
20 01 01 db 7e 20 7d dd 7b c2 41 00 00   (walking, NPC 0x01, X changed)
20 01 01 7d 7e 20 7d 27 7c c2 41 00 00   (walking, NPC 0x01)
```

### 5.4 raw 0x1f -- Pool Status (8B and 14B)

**Frequency:** 54x total in long session (31x 8B, 23x 14B).
**Direction:** S2C. **Wrapper:** raw.

#### 5.4.1 raw 0x1f 14B -- Full Pool Status

Reports current pool values (HP, PSI, stamina, etc.) for the player character.

```
Offset  Size  Field              Example        Notes
0x00    1     Type               1f
0x01    2     Map ID (LE)        01 00          Map ID = 1
0x03    1     Sub-opcode         30             Pool status indicator
0x04    2     HP (LE)            8a 00          Current HP = 138
0x06    2     PSI (LE)           b2 00          Current PSI = 178
0x08    2     Stamina (LE)       4f 00          Current Stamina = 79
0x0A    2     Max HP (LE)        8c 01          Max HP = 396
0x0C    2     Max PSI (LE)       8c 01          Max PSI = 396 (same as max HP here)
```

**Important:** Byte offset 3 is sub-opcode `0x30`, NOT two separate bytes
`0x30 0x8a`. The `0x8a` at offset 4 is the HP low byte. Prior documentation
that read this as "0x30 0x8a" was a misparse.

The 14B variant is sent every ~5 seconds during normal gameplay. After death,
all pool values are zero: `1f 01 00 30 00 00 00 00 00 00 00 00 8c 01`.

Retail samples (alive):
```
1f 01 00 30 8a 00 b2 00 4f 00 8c 01 8c 01    (HP=138, PSI=178, STA=79, maxHP=396)
```

Retail samples (dead):
```
1f 01 00 30 00 00 00 00 00 00 00 00 8c 01    (all pools zero, maxPSI=396)
```

#### 5.4.2 raw 0x1f 8B -- Entity Status Echo

Updates a remote entity's status.

```
Offset  Size  Field              Example        Notes
0x00    1     Type               1f
0x01    1     Entity world ID    e9             Target entity
0x02    1     Unknown            01
0x03    1     Sub-opcode         56             (or 55 in C2S echo)
0x04    4     Unknown            00 00 00 00
```

Retail samples:
```
1f e9 01 56 00 00 00 00    (S2C entity 0xE9, opcode 0x56)
1f e8 01 56 00 00 00 00    (S2C entity 0xE8, opcode 0x56)
1f 21 01 56 00 00 00 00    (S2C entity 0x21, opcode 0x56)
```

C2S echo uses opcode 0x55 instead of 0x56:
```
1f e8 01 55                (C2S, 4B)
1f e9 01 55                (C2S, 4B)
```

### 5.5 raw 0x1f 16B -- Pool Delta (combat only)

**Frequency:** Only during combat (2x observed at death moment).
**Direction:** S2C. **Wrapper:** raw.

Reports a change in a pool value (damage taken).

```
Offset  Size  Field              Example        Notes
0x00    1     Type               1f
0x01    2     Map ID (LE)        01 00
0x03    1     Sub-opcode         50             Pool delta indicator
0x04    4     Delta value (LE)   ea fe ff ff    Signed: -278 (damage)
0x08    4     Unknown            00 00 00 04
0x0C    2     Max pool (LE)      8c 01          Max pool value = 396
0x0E    2     Unknown            00 00
```

Retail samples (at death +331ms):
```
1f 01 00 50 ea fe ff ff 00 00 00 04 8c 01 00 00   (delta = -278, pool 4)
1f 01 00 50 37 ff ff ff 00 00 00 05 8c 01 00 00   (delta = -201, pool 5)
```

### 5.6 raw 0x0b -- CPing (S2C: 5B or 9B)

**Frequency:** 40-45x per session.
**Direction:** Both. **Wrapper:** raw.

Server sends 5B ping probes; client responds with 9B (echo + counter).

```
S2C (5B):
Offset  Size  Field              Example
0x00    1     Type               0b
0x01    4     Server counter     16 ac e8 11

C2S (9B):
0x00    1     Type               0b
0x01    4     Client counter     a1 a4 35 00
0x05    4     Server echo        16 ac e8 11    (echoed from S2C)
```

Retail S2C: `0b 16 ac e8 11`
Retail C2S: `0b a1 a4 35 00 16 ac e8 11`

### 5.7 raw 0x04 -- UDPAlive (7B)

**Frequency:** 4-6x per session.
**Direction:** S2C. **Wrapper:** raw (inside 0x13 container).

```
Offset  Size  Field              Example
0x00    1     Type               04
0x01    6     Unknown            (zeros or padding)
```

### 5.8 raw 0x3c -- World Update (12B)

**Frequency:** 2-4x per session.
**Direction:** Both (S2C and C2S). **Wrapper:** raw.

```
Offset  Size  Field              Example        Notes
0x00    1     Type               3c
0x01    2     Map ID (LE)        01 00
0x03    1     Sub-type           00 or 05       Update type
0x04    4     Value A (LE)       37 54 00 00
0x08    4     Value B (LE)       c2 0d 00 00
```

Retail samples:
```
3c 01 00 00 37 54 00 00 c2 0d 00 00
3c 01 00 05 30 24 00 00 93 40 00 00
3c 01 00 01 a2 34 00 00 e2 38 00 00
```

### 5.9 raw 0x00 -- Unknown (12B or 5B)

**Frequency:** 2-3x per session.
**Direction:** S2C/C2S. **Wrapper:** raw.

C2S samples:
```
00 27 fb 03 00          (5B)
00 3c 01 00 01 ff ff ff ff 00 78 13    (12B)
00 3c 01 00 01 ff ff ff ff 00 52 cf    (12B)
```

### 5.10 raw 0x11 -- Unknown (10B)

**Frequency:** 1x in death session.
**Direction:** S2C. **Wrapper:** raw.

Rare packet, only observed once in the death capture.

---

## 6. S2C Reliable (0x03) Sub-packets

All 0x03 sub-packets have a 4-byte header:

```
Offset  Size  Field              Description
0x00    1     Type               0x03
0x01    2     Sequence (LE)      Reliable sequence counter
0x03    1     Sub-type           See table below
0x04    N     Inner data         Sub-type specific
```

### Sub-type Frequency Table (Long Session)

| Sub-type | Name | Count | Total Bytes | Sizes |
|----------|------|-------|-------------|-------|
| 0x2d | NPCData | 681 | 34278 | 58B x565, 13B x116 |
| 0x1f | GamePackets | 284 | 2559 | 9B x283, 13B x1 |
| 0x28 | WorldInfo | 112 | 4401 | 17B, 48B, 52B |
| 0x32 | NPCAttribute | 41 | 492 | 12B |
| 0x1b | PosUpdate | 22 | 352 | 15B |
| 0x2e | Weather | 6 | 102 | 17B |
| 0x07 | Multipart | 5 | 2169 | ~435B per fragment |
| 0x2f | UpdateModel | (via 0x02) | | 20-77B |
| 0x23 | InfoResponse | 2 | 24 | 10B, 14B |
| 0x0d | TimeSync | 1 | 16 | 16B |
| 0x33 | ChatList | 1 | 6 | 6B |
| 0x25 | PlayerInfo | 1 | 68 | 68B |
| 0x2c | StartPos | 1 | 75 | (death only) |
| 0x09 | Unknown | 2 | 16 | 8B (death only) |

### 6.1 0x03->0x2d NPCData (13B and 58B)

**Frequency:** 681x (most common reliable sub-packet).

#### 6.1.1 NPCData 13B -- NPC Despawn/Spawn Stub

```
Offset  Size  Field              Example        Notes (inner data, after 0x03 header)
0x00    2     NPC World ID (LE)  f8 03          World ID = 1016
0x02    2     Map ID (LE)        00 00          Map ID = 0 (removed from zone)
0x04    1     NPC type           0a             Type indicator
0x05    4     Unknown            00 00 00 00
```

Used for despawning (map_id=0) or lightweight NPC references (e.g., door
entities with world IDs in the 0x03f8-0x03fc range).

Retail samples:
```
f8 03 00 00 0a 00 00 00 00    (world_id=1016, map=0, type=0x0a)
00 78 03 00 08 00 00 00 00    (world_id=30720, map=3, type=0x08)
```

#### 6.1.2 NPCData 58B -- Full NPC Spawn

```
Offset  Size  Field              Example        Notes (inner data)
0x00    2     NPC World ID (LE)  21 01          World ID = 289
0x02    2     Unknown            00 00
0x04    1     Flags byte 1       75             NPC type flags
0x05-0x08  4  Position/hash A    5c 0f 24 00    Unknown (varies per NPC)
0x09-0x0C  4  Position/hash B    00 00 8c f6    Unknown
0x0D    1     Separator          d4
0x0E-0x11  4  Value A            00 3d 5a 41    Possibly float (3d5a4100 = 0.053)
0x12    1     Unknown            01
0x13-0x16  4  Value B            c0 84 5c 0f    Changes per update cycle
0x17-0x1A  4  Value C            90 29 e8 0a    Changes
0x1B-0x1E  4  Value D            98 f6 d4 00    Constant within NPC
0x1F-0x22  4  Value E            de db 4e 01    Constant within NPC
0x23-0x26  4  Value F            48 46 6e 0f    Changes slowly
0x27-0x2A  4  Value G            ff ff ff ff    Often 0xFFFFFFFF
0x2B-0x2E  4  Value H            d4 f6 d4 00    Matches Value D
0x2F-0x32  4  Value I            79 3c 4a 01    Constant within NPC
0x33-0x35  3  Tail               c4 f6 d4       Truncated at end
```

NPCs with world IDs 0x01E5-0x01EB appear to be hostile mobs (these are the
ones whose positions change during combat). NPC 0x0121 (289) appears to be
a fixed world entity.

Retail samples (first 20 hex bytes shown):
```
NPC 289:  21 01 00 00 75 5c 0f 24 00 00 00 8c f6 d4 00 3d 5a 41 01 c0
NPC 485:  e5 01 00 00 71 40 66 9a a6 44 ff ff ff ff 01 d8 f0 d4 00 88
NPC 486:  e6 01 00 00 71 40 66 9a a6 44 ff ff ff ff 01 d8 f0 d4 00 88
```

### 6.2 0x03->0x1f GamePackets

**Frequency:** 284x in long session.
**Direction:** S2C. **Wrapper:** 0x03 reliable.

The inner data has the format:
```
Offset  Size  Field              Description
0x00    2     Map ID (LE)        01 00
0x02    1     Opcode             Game sub-opcode
0x03    1     Sub-opcode         Further classification
0x04    N     Payload            Opcode-specific
```

#### S2C Inner Opcode Frequency

| Opcode | Sub-opcode | Count | Inner Size | Description |
|--------|------------|-------|------------|-------------|
| 0x25 | 0x23 | 283 | 5B | Heartbeat/sync ("I'm alive") |
| 0x25 | 0x13 | 1 | 8-10B | Skill/confirmation update |
| 0x25 | 0x22 | rare | 18B | Player attribute update |
| 0x25 | 0x06 | rare | 27B | Damage event (combat) |
| 0x16 | 0xe8 | rare | 7B | Death notification |
| 0x01 | 0x22 | rare | 19B | Shoot/attack event |

#### 6.2.1 0x25 0x23 -- Heartbeat (5B inner)

The dominant reliable GamePacket in steady state. Sent 283 times in 3 minutes.

```
Offset  Size  Field              Example
0x00    2     Map ID (LE)        01 00
0x02    1     Opcode             25
0x03    1     Sub-opcode         23
0x04    1     Zone/state byte    30 or 40 or 28
```

Retail: `01 00 25 23 30` (5B inner, 9B total sub-packet).

The trailing byte appears to be a zone state flag: `0x30` in Viarosso P3,
`0x40` in ACC1_CHAR1, `0x28` at moment of death, `0x02` post-death.

#### 6.2.2 0x25 0x13 -- Skill/Confirmation (8-10B inner)

```
Offset  Size  Field              Example        Notes
0x00    2     Map ID (LE)        01 00
0x02    1     Opcode             25
0x03    1     Sub-opcode         13
0x04    1     Unknown            bc or c0 or c1
0x05    1     Unknown            2f
0x06    1-3   Values             0e 02 / 0b 00 00 / 02 03 0c 00
```

Retail samples:
```
01 00 25 13 bc 2f 0e 02        (8B, pre-combat)
01 00 25 13 c0 2f 0b 00 00     (9B, pre-death)
01 00 25 13 c1 2f 02 03 0c 00  (10B, post-death -- respawn skills?)
```

#### 6.2.3 0x25 0x22 -- Player Attribute Update (18B inner)

```
Offset  Size  Field              Example        Notes
0x00    2     Map ID (LE)        01 00
0x02    1     Opcode             25
0x03    1     Sub-opcode         22
0x04    1     Unknown            3d
0x05    1     Attribute index    08
0x06    2     Value A (LE)       95 03          = 917
0x08    2     Value B (LE)       95 03          = 917
0x0A    2     Value C (LE)       90 01          = 400
0x0C    2     Value D (LE)       ae 02          = 686
0x0E    2     Value E (LE)       1e 03          = 798
0x10    2     Padding            00 00
```

Retail: `01 00 25 22 3d 08 95 03 95 03 90 01 ae 02 1e 03 00 00` (18B inner).
Sent when player attributes change (e.g., entering combat, buffing).

#### 6.2.4 0x25 0x06 -- Damage Event (27B inner)

```
Offset  Size  Field              Example        Notes
0x00    2     Map ID (LE)        01 00
0x02    1     Opcode             25
0x03    1     Sub-opcode         06
0x04    1     Damage type        01
0x05    1     Source type        05
0x06    1     Damage subtype     0a or 00
0x07    1     Unknown            00
0x08    2     Source entity (LE) a2 0f          Source entity ID
0x0A    1     Unknown            01
0x0B    4     Damage value (f32) c9 f6 98 42    = 76.48 damage
0x0F    2     Target entity (LE) a2 0f
0x11    2     Target HP (LE)?    e8 01          488?
0x13    2     Unknown            00 00
0x15    2     Attack ID (LE)     73 06          Attack sequence ID
0x17    2     Unknown            00 00
0x19    2     Float/multiplier   20 41          = 10.0f (half of IEEE 754)
```

Retail samples (at death moment +331ms):
```
01 00 25 06 01 05 0a 00 a2 0f 01 c9 f6 98 42 a2 0f e8 01 00 00 73 06 00 00 20 41
01 00 25 06 01 05 00 00 74 10 01 51 da 8b 3f 74 10 e8 01 00 00 73 06 00 00 80 3e
```

Two damage events from different sources (0x0FA2 and 0x1074) hit at the
same tick, with damages of ~76.48 and ~1.09 respectively.

#### 6.2.5 0x16 -- Death Notification (7B inner)

```
Offset  Size  Field              Example        Notes
0x00    2     Map ID (LE)        01 00
0x02    1     Opcode             16
0x03    2     Target entity (LE) e8 01          Player entity ID = 488
0x05    2     Unknown            00 00
```

Retail: `01 00 16 e8 01 00 00` (7B inner, 11B total sub-packet).
Sent immediately after the lethal damage events.

#### 6.2.6 0x01 0x22 -- Attack/Shoot (19B inner)

```
Offset  Size  Field              Example        Notes
0x00    2     Map ID (LE)        01 00
0x02    1     Opcode             01
0x03    1     Sub-opcode         22
0x04    1     Unknown            01
0x05    2     Attacker (LE)      e8 01          488
0x07    2     Unknown            00 00
0x09    8     Position/angles    31 19 b2 2a e9 11 81 1d
0x11    2     Target zone        5c 45
```

Retail: `01 00 01 22 01 e8 01 00 00 31 19 b2 2a e9 11 81 1d 5c 45`
Observed at -1878ms and -486ms before death (C2S attack callbacks from NPCs).

### 6.3 0x03->0x28 WorldInfo

**Frequency:** 112x in long session.
**Direction:** S2C. **Wrapper:** 0x03 reliable.

Two variants: short (13B inner) for attribute responses and long (44-48B
inner) for full NPC/item info.

#### 6.3.1 WorldInfo Short (13B inner, 17B total)

Response to C2S `0x27 RequestWorldInfo` for attribute queries.

```
Offset  Size  Field              Example        Notes (inner data)
0x00    1     Response type      27             Echo of request type
0x01    1     Unknown            00
0x02    2     World ID (LE)      fb 03          World ID = 1019
0x03    2     Unknown            00 00
0x06    1     Attribute index    04 or 05
0x07    2     Unknown            00 00
0x09    2     Value (LE)         b0 41          Value (e.g. float16)
0x0B    1     Flags              01
0x0C    1     Unknown            00
```

Retail samples:
```
27 00 fb 03 00 00 04 00 00 b0 41 01 00
27 00 fa 03 00 00 05 00 00 00 00 01 00    (value=0)
27 00 f9 03 00 00 05 00 00 a0 41 01 00
27 00 f8 03 00 00 05 00 00 20 42 01 00    (value=40.0f)
```

#### 6.3.2 WorldInfo Long -- NPC Full Info (44-48B inner)

Full NPC information including name, position, and behavior parameters.

```
Offset  Size  Field              Example        Notes (inner data)
0x00    1     Response type      00
0x01    1     Unknown            01
0x02    2     World ID (LE)      e9 01          World ID = 489
0x04    2     Unknown            00 00
0x06    1     Flags              8f
0x07    1     Type               40
0x08    4     Hash/ID            e2 30 01 00
0x0C    1     Position flags     15
0x0D    6     Position data      7a 80 7b f1 80 00
0x13    2     Heading/speed      04 4b
0x15    2     Unknown            00 00 00
0x18    2     Visual A           bb bb
0x1A    2     Visual B           00 c6
0x1C    2     Visual C           c6 00
0x1E    4     Unknown            00 00 00 00
0x22    N     Name (C-str)       "TWNOWEAPON\0" or "WSK-179\0"
0x22+N  2     Tail               30 00
```

String examples found: "TWNOWEAPON" (weapon entity name), "WSK-179" (NPC name).

Retail samples:
```
00 01 e9 01 00 00 8f 40 ... "TWNOWEAPON"
00 01 73 01 00 00 f1 df 63 00 9e 11 ... "WSK-179"
00 01 71 01 00 00 33 f9 63 00 9d 11 ... "WSK-179"
00 01 19 01 00 00 d2 e0 63 00 bf 03 ... "WSK-179"
```

### 6.4 0x03->0x07 Multipart (CharInfo)

**Frequency:** 5-10 fragments per session (one reassembled payload).
**Direction:** S2C. **Wrapper:** 0x03 reliable.

Carries the large CharInfo payload split across multiple fragments.

```
Offset  Size  Field              Notes (inner data, after 0x03 header)
0x00    2     Fragment index LE  0-based, 0x0000 = first
0x02    2     Total frags LE     e.g. 0x000a = 10 fragments
0x04    1     Chain key          0x00 (identifies the reassembly chain)
0x05    N     Fragment payload   ~426B per fragment
```

Fragment header per the multipart framing protocol:
```
0x05    1     Discriminator?     00
0x06    2     Unknown            01 22
0x08    2     Total size ref     10 00
0x0A    2     Unknown            00 00
```

Retail assembly (ACC1_CHAR1):
- 10 fragments, chain_key=0x00
- Fragments 0-8: 426B payload each
- Fragment 9: 356B payload
- Total reassembled: 4190 bytes
- First 32 bytes of reassembled: `00 01 22 10 00 00 22 02 01 01 0a 00 fa 0e ...`

The reassembled payload starts with the CharInfo header `22 02 01` at offset 6,
matching the CharInfo section structure documented in PROTOCOL.md.

### 6.5 0x03->0x1b PosUpdate (15B total, 11B inner)

**Frequency:** 22x in long session, 54x in death session.
**Direction:** S2C. **Wrapper:** 0x03 reliable.

Player position update (reliable version of the raw 0x1b broadcast).

```
Offset  Size  Field              Example        Notes (inner data)
0x00    1     World ID (lo)      87 or 88       Player's world ID
0x01    2     Unknown            00 00 or 00 00
0x02    2     Unknown            00 20
0x04    1     Unknown            00
0x05    2     Unknown            00 00 00
0x08    1     Unknown            ff
0x09    2     Flags              14 / other
```

Retail samples:
```
87 00 00 00 20 00 00 00 00 ff 14
88 00 00 00 20 00 00 00 00 ff 14
01 0c 00 80 24 06 82 8b 7b dc 80 80 86 7f 62 00 01 00   (22B variant at death)
```

The 22B variant (seen at death) contains extended position data.

### 6.6 0x03->0x2e Weather (17B total, 13B inner)

**Frequency:** 6-15x per session.
**Direction:** S2C. **Wrapper:** 0x03 reliable (also via 0x02).

```
Offset  Size  Field              Example        Notes (inner data)
0x00    2     Map ID (LE)        01 64          Map + weather zone
0x02    2     Weather ID         00 64          Weather type + intensity
0x04    1     Unknown            01
0x05    4     Duration A (LE)    5f f2 06 00    Duration ticks = 455263
0x09    4     Duration B (LE)    5f f2 06 00    Same as Duration A
```

Weather values observed:
- `0x00` = clear, `0x64` = moderate (100%), `0x01` = light

Retail samples:
```
01 64 00 64 01 5f f2 06 00 5f f2 06 00    (map=1, moderate weather)
01 00 00 00 00 a0 05 00 00 a0 05 00 00    (map=1, clear weather)
01 01 00 64 01 b4 3e 05 00 b4 3e 05 00    (map=1, light weather)
```

### 6.7 0x03->0x25 PlayerInfo (68B total, 64B inner)

**Frequency:** 1x per zone entry.
**Direction:** S2C. **Wrapper:** 0x03 reliable.

Full player information for a character in the zone.

```
Offset  Size  Field              Example        Notes (inner data)
0x00    2     Map ID (LE)        01 00
0x02    2     Char ID (LE)       5a 86          Character ID = 34394
0x04    4     Unknown            01 00 00 30
0x08    2     Unknown            00 45
0x0A    4     Unknown            00 00 00 d6
0x0E    1     Level?             21
0x0F    1     Profession?        08
0x10    1     Unknown            04
0x11    1     Unknown            00
0x12    2     Unknown            22 01
0x14    2     Unknown            13 00
0x16    2     Unknown            ff ff
0x18    1     Gender?            05
0x19    1     Unknown            04
0x1A    2     Unknown            60 04
0x1C    6     Zeros              00 00 00 00 00 00
0x22    2     Unknown            00 00
0x24    1     Unknown            0f
0x25    2     Unknown            d0 02
0x27    8     Zeros              00 00 00 00 00 00 00 00
0x2F    2     Unknown            00 00
0x31    2     Unknown            34 03
0x33    2     Unknown            98 03
0x35    1     Name length        08
0x36    N     Name (C-str)       "Krafteo\0"
0x36+N  3     Tail               00 00 00
```

Character name "Krafteo" found at offset 54 (0x36) of inner data.

Retail sample (64B inner):
`01 00 5a 86 01 00 00 30 00 45 00 00 00 00 d6 21 08 04 00 22 01 13 00 ff ff 05 04 60 04 00 00 00 00 00 00 00 0f d0 02 00 00 00 00 00 00 00 00 00 00 34 03 98 03 08 4b 72 61 66 74 65 6f 00 00 00 00`

### 6.8 0x03->0x2f UpdateModel (20-77B inner)

**Frequency:** Sent via 0x02 wrapper (3x per session), also via 0x03 at
death.
**Direction:** S2C. **Wrapper:** 0x03 or 0x02.

Player appearance model data (body type, hair, textures, equipment visuals).

```
Offset  Size  Field              Example        Notes (inner data)
0x00    2     Map ID (LE)        01 00
0x02    1     Model flags        01 or 02       Base model type
0x03    1     Unknown            00
0x04    N     Slot data          02 01 50 02 05 bb ...  [slot_id][value]... pairs
```

Slot data appears as `[slot_id (u8)][value (u16 LE)]` pairs, defining
equipment appearance slots.

Retail samples:
```
01 00 01 00 40 02 00 40 00 02 01 50 02 05 bb 02 06 30 02 07 23 02 08 08 02 0a 9e 03 02 0b ...  (77B, ACC1)
01 00 02 00 30 00 02 01 45 02 05 d6 02 06 21 02 07 08 02 08 04 02 0a 22 01 02 0b 13 00 02 ...  (69B, ACC2)
01 00 02 02 22 01 02 03 87 02 04 33 02 0a ff ff   (20B, post-death simplified)
```

### 6.9 0x03->0x0d TimeSync (16B total, 12B inner)

**Frequency:** 1-3x per session.
**Direction:** S2C. **Wrapper:** 0x03 reliable.

```
Offset  Size  Field              Example        Notes (inner data)
0x00    4     Server time (LE)   67 50 14 00    Server tick = 1331303
0x04    2     Unknown            38 00
0x06    2     Rate               b8 0f
0x08    4     Unknown            d4 0a 3c 01    Secondary timer?
```

Retail: `67 50 14 00 38 00 b8 0f d4 0a 3c 01`

### 6.10 0x03->0x33 ChatList (6B total, 2B inner)

**Frequency:** 1-3x per session.
**Direction:** S2C. **Wrapper:** 0x03 reliable.

```
Offset  Size  Field              Example
0x00    1     Count              ff
0x01    1     Unknown            00
```

Retail: `ff 00` (2B inner). Appears to be an empty channel list (0xFF = no
channels or all channels).

### 6.11 0x03->0x23 InfoResponse (6-10B inner)

**Frequency:** 2-13x per session.
**Direction:** S2C. **Wrapper:** 0x03 or 0x02.

```
Variant 1 (6B inner):
0x00    1     Info type          20 or 0f
0x01    4     Response data      00 00 00 00 or 00 03 00 01
0x05    1     Unknown            00

Variant 2 (10B inner):
0x00    1     Info type          0e
0x01    8     Response data      00 00 00 00 00 00 01 00
0x09    1     Unknown
```

Retail samples:
```
20 00 00 00 00 00       (6B, type 0x20)
0f 00 03 00 01 00       (6B, type 0x0f)
0e 00 00 00 00 00 00 01 00   (10B, type 0x0e -- via 0x02 wrapper)
```

### 6.12 0x03->0x2c StartPos (75B total, death only)

**Frequency:** 1x in death session (respawn).
**Direction:** S2C. **Wrapper:** 0x03 reliable.

Sends the starting position for the character after respawn or zone change.
Only observed in the death capture after the death event.

### 6.13 0x03->0x30 ShortPlayerInfo

Not observed as a 0x03 reliable sub-packet in the analyzed captures. The
"0x30" sub-opcode seen in raw 0x1f packets at offset 3 is the pool status
indicator, not ShortPlayerInfo. See section 5.4.1 for the raw 0x1f 14B pool
status format.

### 6.14 0x03->0x32 NPCAttribute (12B total, 8B inner)

**Frequency:** 41x in long session.
**Direction:** S2C. **Wrapper:** 0x03 reliable.

Reliable version of the raw 0x32 NPC attribute update (section 5.2). Same
field layout as raw 0x32 minus the leading type byte:

```
Offset  Size  Field              Example        Notes (inner data)
0x00    1     NPC world ID (lo)  f9
0x01    1     NPC world ID (hi)  03
0x02    1     Attribute type     05
0x03    2     Unknown            00 00
0x05    2     Value (LE)         a0 41
0x07    1     Flags              00 or 01
```

Retail: `f9 03 05 00 00 a0 41 00` (8B inner, identical to raw 0x32 data).

### 6.15 0x03->0x09 Unknown (death only)

**Frequency:** 2x in death capture only.
**Direction:** S2C. **Wrapper:** 0x03 reliable.

Purpose unknown. Only observed during the death/respawn sequence.

---

## 7. S2C Simplified Reliable (0x02) Sub-packets

The 0x02 wrapper is a simplified reliable delivery without the full 0x03
header. Format:

```
Offset  Size  Field              Description
0x00    1     Type               0x02
0x01    2     Sequence (LE)      Reliable sequence counter
0x03    1     Inner sub-type     Same sub-types as 0x03
0x04    N     Inner data         Sub-type specific
```

### 0x02 Frequency (Long Session)

| Inner type | Name | Count |
|------------|------|-------|
| 0x1f | GamePackets | 45 |
| 0x2e | Weather | 9 |
| 0x23 | InfoResponse | 3 |
| 0x2f | UpdateModel | 3 |

The 0x02 wrapper is used for re-transmitted reliable data. The data content
is identical to the 0x03 variants -- see section 6 for field layouts. The
0x02 wrapper is sent when the client's SyncUDP re-requests reliable data,
acting as a resend mechanism using a simplified header.

Retail samples:
```
02 -> 0x23 InfoResponse seq=2 inner=6B:  0f 00 03 00 01 00
02 -> 0x2e Weather seq=1 inner=13B:      01 00 00 00 00 a0 05 00 00 a0 05 00 00
02 -> 0x2f UpdateModel seq=3 inner=77B:  01 00 01 00 40 02 00 40 00 02 01 ...
02 -> 0x1f GamePackets seq=5 inner=5B:   01 00 25 23 40  (heartbeat)
02 -> 0x1f GamePackets seq=6 inner=8B:   01 00 25 1f 00 00 c8 42
```

---

## 8. C2S Raw Sub-packets

### 8.1 0x20 -- Movement (5B, 17B, 29B)

**Frequency:** 1579x total (1365x 17B, 196x 29B, 18x 5B).
**Direction:** C2S. **Wrapper:** raw.

#### 8.1.1 C2S Movement 5B -- Heading Only

Minimal update when the player's position hasn't changed.

```
Offset  Size  Field              Example
0x00    1     Type               20
0x01    1     Flags              01
0x02    2     Unknown            00 20
0x04    1     Heading            20 or 60
```

Retail samples: `20 01 00 20 20`, `20 01 00 20 60`

#### 8.1.2 C2S Movement 17B -- Position Update

Standard movement update with position.

```
Offset  Size  Field              Example        Notes
0x00    1     Type               20
0x01    1     Flags              01 or 21
0x02    1     Unknown            00 or 30
0x03    4     X (f32 LE)         74 d9 a3 c0    = -5.12 (varies)
0x07    4     Y (f32 LE)         94 c9 98 43    = 305.57
0x0B    4     Z (f32 LE)         00 00 00 00    = 0.0
0x0F    1     Unknown            00
0x10    1     Heading            20
```

Byte 2 controls the movement type: `0x00` = simple position, `0x30` =
interpolated (with direction hint), `0x38` = running.

Retail samples:
```
20 01 00 30 74 d9 a3 c0 94 c9 98 43 00 00 00 00 20
20 01 00 30 96 4c 6d c1 c0 24 23 41 00 00 00 00 20
20 01 00 38 73 e9 aa c1 32 65 fc 42 00 00 00 00 20
```

#### 8.1.3 C2S Movement 29B -- Full Movement + Velocity

Full position with velocity vector, sent when player is moving.

```
Offset  Size  Field              Example        Notes
0x00    1     Type               20
0x01    1     Flags              01
0x02    1     Unknown            00
0x03    1     Format flags       7f or 37 or 2f
0x04    4     X (f32 LE)         ae 12 60 c3    = -224.07
0x08    4     Y (f32 LE)         eb df 8f c4    = -1150.99
0x0C    4     Z (f32 LE)         2b c9 37 43    = 183.79
0x10    4     VX (f32 LE)        a3 cc 34 c1    = -11.30 (velocity X)
0x14    4     VY (f32 LE)        62 75 8e 43    = 284.92 (velocity Y)
0x18    4     VZ (f32 LE)        00 00 00 00    = 0.0
0x1C    1     Heading            20
```

Format flags byte 3: `0x7f` = running with full velocity data, `0x37` =
jogging, `0x2f` = walking.

Retail samples:
```
20 01 00 7f ae 12 60 c3 eb df 8f c4 2b c9 37 43 a3 cc 34 c1 62 75 8e 43 00 00 00 00 20
20 01 00 7f ae 12 60 c3 cd fc 8f c4 2b c9 37 43 d3 bf c5 c1 0b fd 4c 42 00 00 00 00 20
```

### 8.2 0x01 -- Reliable ACK (3B)

**Frequency:** 60x in long session.
**Direction:** C2S. **Wrapper:** raw (inside 0x13).

Acknowledges receipt of a reliable (0x03) sub-packet.

```
Offset  Size  Field              Example
0x00    1     Type               01
0x01    2     Ack sequence (LE)  02 00          Acknowledges seq #2
```

Retail samples:
```
01 02 00   (ACK seq 2)
01 01 00   (ACK seq 1)
01 03 00   (ACK seq 3)
```

### 8.3 0x0b -- CPing (5B)

**Frequency:** 41x per session.
**Direction:** C2S (also S2C). **Wrapper:** raw.

See section 5.6. C2S 5B format: `[0x0b][counter_lo][counter_hi][rate_lo][rate_hi]`

### 8.4 0x0c -- TimeSync (5B)

**Frequency:** 1x per session.
**Direction:** C2S. **Wrapper:** raw.

```
Offset  Size  Field              Example
0x00    1     Type               0c
0x01    2     Unknown            38 00
0x02    2     Rate               b8 0f
```

Retail: `0c 38 00 b8 0f`

### 8.5 0x2a -- RequestPos (16B)

**Frequency:** 1x per session.
**Direction:** C2S. **Wrapper:** raw.

```
Offset  Size  Field              Example        Notes
0x00    1     Type               2a
0x01    4     Session ID (LE)    bd 7e 01 00
0x05    2     Unknown            00 38
0x07    1     Unknown            ec
0x08    4     Position?          3b 7e 35 93
0x0C    4     Unknown            a3 27 99 09 00
```

Retail: `2a bd 7e 01 00 38 ec 3b 7e 35 93 a3 27 99 09 00`

### 8.6 0x32 -- NPC Attribute Echo (9B)

**Frequency:** 70x in long session.
**Direction:** C2S. **Wrapper:** raw.

Client echo of S2C 0x32 NPC attribute values. Same format as section 5.2.

Retail: `32 f8 03 05 00 00 20 42 00` (mirrors S2C data).

### 8.7 0x27 -- RequestWorldInfo Echo (5B)

**Frequency:** 30x in long session.
**Direction:** C2S. **Wrapper:** raw.

```
Offset  Size  Field              Example
0x00    1     Type               27
0x01    2     World ID (LE)      fb 03
0x03    2     Unknown            00 00
```

Retail: `27 fb 03 00 00`, `27 f9 03 00 00`, `27 fc 03 00 00`

### 8.8 0x1f -- Entity Status Echo (4B)

**Frequency:** 30x in long session.
**Direction:** C2S. **Wrapper:** raw.

```
Offset  Size  Field              Example
0x00    1     Type               1f
0x01    1     Entity world ID    e9
0x02    1     Unknown            01
0x03    1     Sub-opcode         55             (vs 0x56 in S2C)
```

Retail: `1f e9 01 55`, `1f e8 01 55`

### 8.9 0x00 -- Unknown (5B, 12B)

**Frequency:** 3x in long session.
**Direction:** C2S. **Wrapper:** raw.

See section 5.9.

---

## 9. C2S Reliable (0x03) Sub-packets

### 9.1 0x03->0x1f GamePackets (C2S)

**Frequency:** 4109x in long session (dominant C2S sub-packet).
**Direction:** C2S. **Wrapper:** 0x03 reliable.

#### C2S Inner Opcode Frequency (all captures combined)

| Opcode | Sub-opcode | Count | Inner Size | Description |
|--------|------------|-------|------------|-------------|
| 0x3d | 0x11 | 836 | 12B | QuickCommand heartbeat |
| 0x4c | 0xff | 10 | 11B | ChangeChannels |
| 0x3d | 0x32 | 8 | 15B | QuickCommand variant |
| 0x3e | 0x01/0x02 | 4 | 12B | TradeSetting |
| 0x32 | 0x01/0x02 | 3 | 12B | Unknown |
| 0x17 | 0x00 | 1 | 12B | Use object |
| 0x02 | ? | 1 | 7B | Jump? |

#### 9.1.1 0x3d 0x11 -- QuickCommand Heartbeat (12B inner)

The most frequent C2S packet -- sent every ~60ms during normal gameplay.

```
Offset  Size  Field              Example        Notes (inner data)
0x00    2     Map ID (LE)        01 00
0x02    1     Opcode             3d
0x03    1     Sub-opcode         11
0x04    4     Unknown            00 00 00 00    Always zeros in steady state
```

Retail: `01 00 3d 11 00 00 00 00` (12B inner = 8B payload)

#### 9.1.2 0x3d 0x32 -- QuickCommand Variant (15B inner)

```
Offset  Size  Field              Example        Notes (inner data)
0x00    2     Map ID (LE)        01 00
0x02    1     Opcode             3d
0x03    1     Sub-opcode         32
0x04    1     Unknown            02 or 55
0x05    2     Unknown            86 01
0x06    5     Unknown            00 ...
```

Retail samples:
```
01 00 3d 32 02 55 86 01 00 00 00   (15B)
01 00 3d 32 02 fe 85 01 00 00 00   (15B)
```

#### 9.1.3 0x4c 0xff -- ChangeChannels (11B inner)

```
Offset  Size  Field              Example        Notes (inner data)
0x00    2     Map ID (LE)        01 00
0x02    1     Opcode             4c
0x03    1     Sub-opcode         ff
0x04    1     Unknown            ff
0x05    2     Channel flags      03 00
```

Retail: `01 00 4c ff ff 03 00` (7B inner)

#### 9.1.4 0x3e 0x01 -- TradeSetting (12B inner)

```
Offset  Size  Field              Example
0x00    2     Map ID (LE)        01 00
0x02    1     Opcode             3e
0x03    1     Sub-opcode         01
0x04    4     Trade data         00 00 00 01
```

Retail: `01 00 3e 01 00 00 00 01`

### 9.2 0x03->0x27 RequestWorldInfo (8B total, 4B inner)

**Frequency:** 36x in long session.
**Direction:** C2S. **Wrapper:** 0x03 reliable.

```
Offset  Size  Field              Example        Notes (inner data)
0x00    2     World ID (LE)      71 01          Request info for world ID 369
0x02    2     Unknown            00 00
```

Retail samples:
```
71 01 00 00    (world_id=369)
73 01 00 00    (world_id=371)
e8 01 00 00    (world_id=488)
```

### 9.3 0x03->0x24 ReadyForWorldState (6B total, 2B inner)

**Frequency:** 1x per session.
**Direction:** C2S. **Wrapper:** 0x03 reliable.

```
Offset  Size  Field              Example
0x00    2     Unknown            01 00
```

Retail: `01 00` (2B inner). Sent once after the initial world entry.

---

## 10. Death Sequence

Detailed byte-by-byte timeline from the retail death capture. Death occurred
at `02:28:21` (t=0). The player character (entity 0xE8, ID 488) was killed
by hostile NPCs (entities 0x0FA2 and 0x1074) in zone Viarosso.

### Pre-death Phase (-5s to 0)

Normal gameplay continues. Every ~60ms the client sends 0x3d 0x11 heartbeats
and 0x20 movement updates. Server sends periodic position broadcasts (0x1b),
NPC data updates (0x2d), and pool status (raw 0x1f 14B).

Key observations:
- Pool status at t-3166ms: `1f 01 00 30 8a 00 b2 00 4f 00 8c 01 8c 01`
  (HP=138, PSI=178, STA=79, maxHP=396)
- Player attribute update at t-3847ms:
  `01 00 25 22 3d 08 95 03 95 03 90 01 ae 02 1e 03 00 00`
- NPC position changes visible as 0x2d NPCData updates with changing position
  fields (NPCs approaching player)
- C2S attack events (`0x01 0x22`) at t-1878ms and t-486ms from NPCs

### Death Moment (t=0 to t+331ms)

At t+331ms, the server sends a single large burst containing:

1. **S2C NPC Movement** (0x20): `20 21 01 98 79 c4 7c 78 7c 82 07 00 00`
2. **S2C NPCData update** (0x2d, 58B): NPC 0xE8 position change
3. **S2C NPC Attribute** (0x32): `32 f9 03 05 00 00 a0 41 01` (changed flag set)
4. **Pool Delta #1** (raw 0x1f 16B): `1f 01 00 50 ea fe ff ff 00 00 00 04 8c 01 00 00`
   - Delta = -278 (0xFFFFFEEA signed), pool index 4 (HP)
5. **Pool Delta #2** (raw 0x1f 16B): `1f 01 00 50 37 ff ff ff 00 00 00 05 8c 01 00 00`
   - Delta = -201 (0xFFFFFF37 signed), pool index 5 (PSI or secondary)
6. **Damage Event #1** (0x25 0x06, 27B inner):
   `01 00 25 06 01 05 0a 00 a2 0f 01 c9 f6 98 42 a2 0f e8 01 00 00 73 06 00 00 20 41`
   - Source=0x0FA2, target=0xE8 (player), damage=76.48f, attack_id=0x0673
7. **Damage Event #2** (0x25 0x06, 27B inner):
   `01 00 25 06 01 05 00 00 74 10 01 51 da 8b 3f 74 10 e8 01 00 00 73 06 00 00 80 3e`
   - Source=0x1074, target=0xE8, damage=1.09f, attack_id=0x0673
8. **Position Update** (0x03->0x1b, 22B):
   `01 0c 00 80 24 06 82 8b 7b dc 80 80 86 7f 62 00 01 00`
9. **Heartbeat with death zone flag** (0x25 0x23):
   `01 00 25 23 28` (zone byte changed from 0x30 to 0x28)
10. **Death notification** (0x16, 7B inner):
    `01 00 16 e8 01 00 00` -- player entity 488 has died

### Post-death Phase (t+331ms to t+2s)

1. **t+407ms**: Client movement stops: `20 01 00 30 00 00 00 00 00 00 00 00 00 00 00 00 04`
   (all zeros, heading byte = 0x04 = dead state)
2. **t+471ms**: Client sends drift position: `20 01 00 30 b3 87 fe 3d ...`
3. **t+547ms**: Server sends post-death burst:
   - NPC attribute updates (0x32)
   - Skill update: `01 00 25 13 c1 2f 02 03 0c 00` (10B, respawn skills)
   - Post-death XP tally (raw 0x1f 25B):
     `1f 01 00 25 19 64 bf 04 00 d5 93 06 00 e1 4a 02 00 f0 26 07 00 73 45 02 00`
   - Pool status all zeros: `1f 01 00 30 00 00 00 00 00 00 00 00 8c 01`
   - UpdateModel change: `01 00 02 02 22 01 02 03 87 02 04 33 02 0a ff ff`
     (equipment removed -- dead body appearance)
   - Heartbeat zone flag changes to 0x02: `01 00 25 23 02`
4. **t+604ms**: Client adjusts heading: `20 01 00 20 04` (heading=0x04)
5. **t+796ms-t+1895ms**: Server continues sending NPC positions and data as
   normal. Post-death pool status shows all zeros.
6. **t+1260ms**: Reliable PosUpdate for dead player:
   `01 0c 00 80 24 09 82 80 7b d4 80 62 00 01 00 5a 86 01 00`
   (includes character ID 0x0001865A = 99930 in extended format)

### Zone Change (respawn)

The TCP layer handles respawn:
- `02:29:07` S2C: GameinfoReady (`0x830d`) -- server signals new zone ready
- `02:29:08` S2C: Location (`0x830c`) -- zone "apps/vr_app_1" (VR respawn room)
- `02:29:43` S2C: Location (`0x830c`) -- zone "viarosso/viarosso_p2" (respawn zone)

---

## Appendix A: Complete Sub-packet Type Registry

### S2C Sub-packet Types

| Type | Wrapper | Name | Size(s) | Freq (long) |
|------|---------|------|---------|-------------|
| 0x1b | raw | PositionBroadcast | 19B | 2862 |
| 0x03->0x2d | reliable | NPCData | 13B, 58B | 681 |
| 0x03->0x1f | reliable | GamePackets | 9-31B | 284 |
| 0x32 | raw | NPCAttribute | 9B | 182 |
| 0x03->0x28 | reliable | WorldInfo | 17B, 48-52B | 112 |
| 0x20 | raw | NPCMovement | 13B | 78 |
| 0x1f | raw | PoolStatus | 8B, 14B, 16B | 54 |
| 0x02->0x1f | simplified | GamePackets (resend) | 9-12B | 45 |
| 0x03->0x32 | reliable | NPCAttribute (reliable) | 12B | 41 |
| 0x0b | raw | CPing | 5B, 9B | 40 |
| 0x03->0x1b | reliable | PosUpdate | 15B, 22-23B | 22 |
| 0x02->0x2e | simplified | Weather (resend) | 17B | 9 |
| 0x03->0x2e | reliable | Weather | 17B | 6 |
| 0x03->0x07 | reliable | Multipart (CharInfo) | ~435B/frag | 5-10 |
| 0x04 | raw | UDPAlive | 7B | 4-6 |
| 0x02->0x23 | simplified | InfoResponse (resend) | 10B | 3 |
| 0x02->0x2f | simplified | UpdateModel (resend) | 73-81B | 3 |
| 0x3c | raw | WorldUpdate | 12B | 2-4 |
| 0x00 | raw | Unknown | 5-12B | 2 |
| 0x03->0x23 | reliable | InfoResponse | 10-14B | 2-13 |
| 0x03->0x0d | reliable | TimeSync | 16B | 1-3 |
| 0x03->0x33 | reliable | ChatList | 6B | 1-3 |
| 0x03->0x25 | reliable | PlayerInfo | 68B | 1 |
| 0x03->0x2c | reliable | StartPos | 75B | 1 (death) |
| 0x03->0x2f | reliable | UpdateModel | 20-77B | (via 0x02) |
| 0x03->0x09 | reliable | Unknown | 8B | 2 (death) |
| 0x11 | raw | Unknown | 10B | 1 (death) |

### C2S Sub-packet Types

| Type | Wrapper | Name | Size(s) | Freq (long) |
|------|---------|------|---------|-------------|
| 0x03->0x1f | reliable | GamePackets | 12B, 11B, 15B | 4109 |
| 0x20 | raw | Movement | 5B, 17B, 29B | 1579 |
| 0x32 | raw | NPCAttribute echo | 9B | 70 |
| 0x01 | raw | ReliableACK | 3B | 60 |
| 0x0b | raw | CPing | 5B | 41 |
| 0x03->0x27 | reliable | RequestWorldInfo | 8B | 36 |
| 0x27 | raw | RequestWorldInfo echo | 5B | 30 |
| 0x1f | raw | EntityStatus echo | 4B | 30 |
| 0x00 | raw | Unknown | 5-12B | 3 |
| 0x3c | raw | WorldUpdate | 12B | 2 |
| 0x2a | raw | RequestPos | 16B | 1 |
| 0x0c | raw | TimeSync | 5B | 1 |
| 0x03->0x24 | reliable | ReadyForWorldState | 6B | 1 |

### UDP Outer Types (non-0x13)

| Byte 0 | Direction | Name | Size |
|--------|-----------|------|------|
| 0x01 | C2S | Handshake | 10B |
| 0x03 | C2S | Sync | 8B |
| 0x08 | C2S | Abort | 1B |

---

## Appendix B: Unobserved Packet Types

The following packet types are defined in the protocol (from Ceres-J source
and Ghidra RE) but were NOT observed in any of the 6 retail captures analyzed:

| Type | Name | Direction | Notes |
|------|------|-----------|-------|
| 0x03->0x31 | RequestShortPlayer | C2S | Client never sent this in captured sessions |
| 0x03->0x30 | ShortPlayerInfo | S2C | Not seen as reliable; the "0x30" in raw 0x1f at offset 3 is the pool status sub-opcode |
| 0x03->0x26 | RemoveWorldItem | S2C | No world items were removed during captures |
| 0x03->0x2b | CityCom | S2C | No CityCom terminals used |
| 0x03->0x22 | InfoRequest/CharInfo | C2S | Only the multipart (0x07) CharInfo response was seen |
| 0x03->0x08 | ZoningEnd | S2C | Expected but not captured (may have been in the initial burst before strace started) |
| 0x03->0x01 | Resend | S2C | Retransmission request (may occur under packet loss) |

---

## Appendix C: Retail Server Addresses

| Service | Address | Port |
|---------|---------|------|
| Game TCP | 157.90.195.74 | 12000 |
| Game UDP | 157.90.195.74 | 5005-5008 |
| Zones observed | viarosso_p2, viarosso_p3, apps/vr_app_1 | |

---

*Generated from retail NCE 2.5.766 strace captures using tools/decrypt-retail.py,
tools/parse-burst.py, tools/deep_burst_diff.py, tools/decode_combat_packets.py,
and tools/find_death_packets.py.*
