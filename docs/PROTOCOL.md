# Neocron 2 Network Protocol Documentation

Reverse-engineered protocol documentation for the Neocron 2 (NCE 2.5.766) game client.

> **Note (2026-05-02):** the canonical catalog is now under
> [`docs/protocol/`](protocol/INDEX.md). It is auto-generated from
> retail pcaps via `tools/catalog_extract.py` and tracks every
> observed packet type with evidence pointers. Per-packet structure
> docs live in [`docs/protocol/packets/`](protocol/packets/INDEX.md);
> capture corpus map is in
> [`docs/protocol/captures/`](protocol/captures/INDEX.md). This file
> is preserved as the legacy reference while per-packet hand-curated
> content gets folded into the new structure.

## Architecture

The game uses three network layers:

| Layer | Transport | Port | Purpose |
|-------|-----------|------|---------|
| Info Server | TCP | 7000 | Server list, initial authentication |
| Game Server | TCP | 12000 | Login, character management, game data |
| Game Server | UDP | 5000-5005 | Real-time gameplay (movement, combat, chat) |

Retail server uses UDP port **5005**. The port is sent to the client via the `UDPServerData` TCP packet. Ceres-J binds its game UDP listener to port **5000** by default (configurable via `GameServer.init`) and reports that port in its own `UDPServerData` replies.

Retail pcap evidence (see `/tmp/retail_capture.pcapng`, zone
pepper/pepper_p3, retail server `157.90.195.74:5005`): immediately after the
3-way UDP handshake finishes the server sends ~15 large (~440 byte) 0x13
packets containing world-entry state, culminating in a ZoningEnd terminator
after which the client leaves the loading screen. See [World-Entry Packet
Sequence](#world-entry-packet-sequence-post-handshake) for details.

## TCP Protocol

### Packet Format

All TCP packets use the `FE` framing format:

```
Offset  Size  Description
0x00    1     Header (always 0xFE)
0x01    2     Payload size (little-endian)
0x03    2     Packet ID (big-endian)
0x05    ...   Payload data
```

### Connection Flow

```
Client                          Server
  |                               |
  |--- TCP Connect (port 12000) ->|
  |<-- HandshakeA (0x8001) -------|
  |--- HandshakeB (0x8000) ------>|
  |<-- HandshakeC (0x8003) -------|
  |--- Auth (0x8480) ------------>|  (login credentials)
  |<-- AuthAck (0x8381) ----------|  (session token)
  |--- GetGamedata (0x8737) ----->|
  |<-- Gamedata (0x873a) ---------|
  |--- GetCharList (0x8482) ----->|
  |<-- CharList (0x8385) ---------|
  |                               |
  |  [Character selection]        |
  |                               |
  |--- TCP Connect (port 12000) ->|  (NEW connection)
  |<-- HandshakeA (0x8001) -------|
  |--- HandshakeB (0x8000) ------>|
  |<-- HandshakeC (0x8003) -------|
  |--- AuthB (0x8301) ----------->|  (re-auth with char slot)
  |<-- UDPServerData (0x8305) ----|  (UDP IP + port + session)
  |--- GetGamedata (0x8737) ----->|
  |<-- Gamedata (0x873a) ---------|
  |<-- GameinfoReady (0x830d) ----|  (server ready signal)
  |<-- Location (0x830c) ---------|  (zone name + ID)
  |--- GetUDPConnection (0x873c)->|
  |    (no-op — no second         |
  |     UDPServerData sent)       |
  |                               |
  |  [UDP connection starts]      |
```

### TCP Packet IDs

| ID | Direction | Name | Description |
|----|-----------|------|-------------|
| 0x8001 | S→C | HandshakeA | Server hello |
| 0x8000 | C→S | HandshakeB | Client hello response |
| 0x8003 | S→C | HandshakeC | Handshake complete |
| 0x8480 | C→S | Auth | Login (username + encrypted password) |
| 0x8381 | S→C | AuthAck | Login successful (account ID + session) |
| 0x8301 | C→S | AuthB | Re-authentication with character slot |
| 0x8482 | C→S | GetCharList | Request character list |
| 0x8385 | S→C | CharList | Character list response |
| 0x8737 | C→S | GetGamedata | Request game data |
| 0x873a | S→C | Gamedata | Game data response |
| 0x873c | C→S | GetUDPConnection | Request UDP server info |
| 0x8305 | S→C | UDPServerData | UDP server IP, port, session ID |
| 0x830c | S→C | Location | Zone ID + world file name |
| 0x830d | S→C | GameinfoReady | Server ready signal |
| 0x838f | S→C | Unknown | Sent after zone data (possibly game state) |
| 0x8303 | S→C | ClientKicked | Disconnect reason |
| 0x8317 | C↔S | CustomChat | Chat message |
| 0x8318 | S→C | ChatFailed | User not online |
| 0x7b00 | C→S | GetPatchVersion | Patch server version request |
| 0x3702 | S→C | PatchVersion | Patch server version response |

### Auth Packet (0x8480) — Modern Client (NCE 2.5.x)

```
Offset  Size  Description
0x00    2     Packet ID (0x84 0x80)
0x02    1     Encryption key for password
0x03    30    Unknown (was 18 bytes in older versions)
0x21    2     Username length (LE, includes null terminator)
0x23    2     Password length (LE, encoded byte count)
0x25    N     Username (C string, null-terminated)
0x25+N  M     Encrypted password (shorts: ((char + key) << 4))
```

**Important:** The unknown block changed from 18 bytes to 30 bytes in the modern NCE client.

### AuthB Packet (0x8301) — Character Selection

```
Offset  Size  Description
0x00    2     Packet ID (0x83 0x01)
0x02    4     Unknown (possibly client IP)
0x06    4     Client port
0x0A    1     Encryption key
0x0B    7     Unknown
0x12    4     Character slot (LE int)
0x16    2     Password length (LE, /2 for char count)
0x18    2     Username length (LE, includes null)
0x1A    N     Username (C string)
0x1A+N  M     Encrypted password
```

### UDPServerData Packet (0x8305)

```
Offset  Size  Description
0x00    2     Packet ID (0x83 0x05)
0x02    4     Account ID (LE)
0x06    4     Character ID (LE)
0x0A    4     Server IP (network byte order)
0x0E    2     UDP port (LE)
0x10    4     Flags/unknown (retail sends 0x00830000)
0x14    8     Session ID (transformed: 127 - original_byte)
```

### Location Packet (0x830c)

```
Offset  Size  Description
0x00    2     Packet ID (0x83 0x0c)
0x02    4     Location/Zone ID (LE)
0x06    4     Unknown (0 normally, 1 for zone 9999)
0x0A    N     World file path (C string, e.g. "pepper/pepper_p3")
```

## UDP Protocol

### UDP Wire Encryption (NCE 2.5.x)

**Both** directions (C→S and S→C) are encrypted using the same
per-packet LFSR cipher. The encryption layer lives in the WinSockMGR
module (`src/nclib/nclib/net/WinSockMGR.cpp` per Ghidra path strings),
wrapping the raw `sendto` and `recvfrom` calls. Each UDP datagram is
independently encrypted with its own random 16-bit seed.

**Confirmed 2026-04-12** by decrypting 88/88 retail S→C packets from 4
independent session captures. Prior documentation claiming
ObfuscateStreamBuf was the wire cipher was incorrect —
ObfuscateStreamBuf is a `VFile`/streambuf subclass for file I/O, not
network traffic.

#### Wire Packet Format

Each UDP datagram on the wire has a 4-byte encryption header prepended
to the plaintext data:

```
Offset  Size  Description
0x00    2     Seed (16-bit LE, unencrypted)
0x02    2     Encrypted data length (16-bit, see below)
0x04    N     Encrypted data payload
```

Total wire size = plaintext_data_length + 4 bytes.

The seed in bytes 0–1 is sent in the CLEAR. Bytes 2+ are encrypted
using the LFSR PRNG in cipher-feedback (CFB) mode.

#### LFSR PRNG (`FUN_004e36e0`)

The core key-generation function is a 16-bit Linear Feedback Shift
Register with data feedback. It takes a running 16-bit state and an
8-bit input byte, produces an 8-bit output byte, and updates the state
in place. Called once per byte to encrypt/decrypt.

```
function lfsr_byte(state: u16, input: u8) -> (output: u8, new_state: u16):
    output = 0
    for bit = 0 to 7:
        hi = (state >> 8) & 0xFF
        lo = state & 0xFF
        data_bit = (input >> bit) & 1
        feedback = ((hi >> 6) ^ (hi >> 5) ^ (hi >> 3) ^ lo ^ data_bit) & 1
        state = ((state << 1) | feedback) & 0xFFFF
        output |= (feedback << (7 - bit))     // MSB-first output
    return (output, state)
```

**Tap polynomial**: bits 13, 12, 10 of the 16-bit state (corresponding
to `hi >> 6`, `hi >> 5`, `hi >> 3` on the high byte), XOR'd with the
full low byte and one bit of the input data. This is a Galois-style
LFSR with external data injection for cipher-feedback.

**Ghidra source**: `FUN_004e36e0` at address `004e36e0` in
`neocronclient.exe`.

#### Encryption Flow (`FUN_00560090`)

Called by WinSockMGR before every `sendto`:

```
function encrypt_packet(plaintext: bytes, length: int) -> bytes:
    // 1. Generate random per-packet seed
    seed = rand() & 0xFFFF
    state = seed

    // 2. Write seed in the clear
    wire[0] = seed & 0xFF          // seed low byte
    wire[1] = (seed >> 8) & 0xFF   // seed high byte

    // 3. Encrypt the 2-byte length field
    key, state = lfsr_byte(state, wire[1])      // input = seed high byte
    wire[2] = key ^ (length & 0xFF)              // encrypted length low

    key, state = lfsr_byte(state, wire[2])      // input = previous CIPHER byte (CFB)
    wire[3] = key ^ ((length >> 8) & 0xFF)       // encrypted length high

    // 4. Encrypt each data byte (cipher-feedback mode)
    prev_cipher = wire[3]
    for i = 0 to length-1:
        key, state = lfsr_byte(state, prev_cipher)   // CFB: prev cipher byte
        wire[4 + i] = key ^ plaintext[i]
        prev_cipher = wire[4 + i]                     // update for next iteration

    return wire[0 .. length+3]                        // total = length + 4
```

**Ghidra source**: `FUN_00560090` at address `00560090` in
`neocronclient.exe`. Ordinal_20 is `sendto`.

#### Decryption Flow (`FUN_0055ff30`)

Called by WinSockMGR after every `recvfrom`:

```
function decrypt_packet(wire: bytes, wire_length: int) -> bytes:
    if wire_length < 4: return error

    // 1. Read seed from first 2 bytes (unencrypted)
    seed = wire[0] | (wire[1] << 8)
    state = seed

    // 2. Decrypt the 2-byte length
    key, state = lfsr_byte(state, wire[1])      // input = seed high byte
    length_lo = key ^ wire[2]

    key, state = lfsr_byte(state, wire[2])      // CFB: prev cipher byte
    length_hi = key ^ wire[3]

    data_length = (length_hi << 8) | length_lo

    // 3. Decrypt each data byte
    plaintext = []
    prev_cipher = wire[3]
    for i = 0 to data_length-1:
        cipher_byte = wire[4 + i]
        key, state = lfsr_byte(state, prev_cipher)
        plaintext[i] = key ^ cipher_byte
        prev_cipher = cipher_byte

    return plaintext[0 .. data_length-1]
```

**Ghidra source**: `FUN_0055ff30` at address `0055ff30` in
`neocronclient.exe`. Ordinal_17 is `recvfrom`. Note: this function
handles multiple encrypted sub-packets within a single UDP datagram
(each with its own 4-byte header), looping until all bytes are consumed.

#### WinSockMGR Dispatch (`FUN_0055ec10`)

After decryption, `FUN_0055ec10` dispatches on the FIRST byte of the
plaintext data. Low values (1–10) are session-management commands;
values above 14 (0x0E) are game data with the first byte decremented
by 15 (0x0F) before dispatch to the game layer:

```
switch (plaintext[0]):
    case 1:  session handshake step 1 (nonce exchange)
    case 2:  session handshake step 2 (nonce verification)
    case 3:  session handshake step 3 (confirmation)
    case 4:  session handshake complete
    case 5:  server info request
    case 6:  server info response
    case 8:  session abort
    case 9:  keepalive request → responds with 10
    case 10: keepalive response
    default:
        if plaintext[0] > 0x0E:
            plaintext[0] -= 0x0F    // strip session-layer offset
            dispatch to game layer via WorldClient vtable
```

This means the game-layer packet types are offset by +0x0F on the wire
BEFORE encryption:

| Plaintext byte 0 | After −0x0F | Game-layer meaning |
|---|---|---|
| 0x10 | 0x01 | UDP handshake |
| 0x12 | 0x03 | Sync / reliable delivery |
| 0x13 | 0x04 | Keepalive |
| 0x17 | 0x08 | Abort |
| 0x22 | 0x13 | Gamedata |

**Ghidra source**: `FUN_0055ec10` at address `0055ec10`.

#### Ceres-J Implementation (2026-04-12)

The cipher is fully implemented on outgoing server packets:

- **`server.networktools.WireEncrypt`** — Java port of the LFSR CFB cipher.
  - `lfsrByte(state, input) -> key_byte` — the PRNG inner loop
  - `encrypt(plaintext, offset, length) -> wire_bytes` — full packet encrypt
- **`GameServerUDPConnection.sendPacket()`** — calls
  `WireEncrypt.encrypt()` on every outgoing datagram before
  `socket.send()`, wrapping the plaintext in the 4-byte cipher header.

Earlier assumption that "the client accepts plaintext" was MISLEADING:
while the client doesn't crash on plaintext, it misroutes the bytes.
With no 4-byte header, byte 0 of the packet hits the WinSockMGR
session-layer switch directly — plaintext `0x13` gamedata gets the
`-0x0F` subtraction and routes to case 4 (handshake complete) instead
of the game-layer dispatcher. That misrouting was the ultimate cause
of the state machine never advancing. Encrypting the packets gives the
client the proper framing header it needs to correctly peel off the
cipher layer and deliver the plaintext to the game-layer dispatcher.

#### Historical note: ObfuscateStreamBuf

The `ObfuscateStreamBuf` class (vtable at `0x00a1fd1c`) was initially
suspected to be the wire cipher. Analysis confirmed it is a
`std::streambuf` subclass in the `VFile` hierarchy (alongside
`VFilePak`, `VFilePakUncompressed`, etc.) used for file I/O
obfuscation, NOT network traffic. Its cipher formula
`key = seed * (position + 1)` with global seed `DAT_00b05360 = 0x3039`
and cumulative position was mathematically proven NOT to match retail
UDP traffic (16-bit brute-force across 65536 seeds returned 0 candidates
in both per-packet and cumulative modes).

#### Decryption tool

`ceres-j/tools/decrypt-retail.py` — Python implementation of the LFSR
cipher. Usage:

```bash
python3 tools/decrypt-retail.py --input strace/nc2_strace_RETAIL_ACC1_CHAR1.log --dump 88
```

Decrypted retail login burst saved at
`ceres-j/docs/retail_decoded_burst.txt`.

### UDP Packet Types

| First Byte | Name | Description |
|------------|------|-------------|
| 0x01 | Handshake | UDP session establishment |
| 0x03 | Sync | Synchronization / reliable delivery |
| 0x04 | Keepalive | Connection heartbeat |
| 0x08 | Abort | Session termination |
| 0x13 | Gamedata | Game state packets (multiplexed) |

### Handshake (0x01)

```
Offset  Size  Description
0x00    1     Type (0x01)
0x01    8     Session data
0x09    1     Interface ID
0x0A    4     Unknown
```

Client sends 3 handshake packets immediately after receiving `UDPServerData`. Server responds with `UDPAlive` (0x04).

### Gamedata (0x13) — Multiplexed

```
Offset  Size  Description
0x00    1     Type (0x13)
0x01    4     Packet counter (big-endian)
0x05    ...   Sub-packets (length-prefixed)
```

Each sub-packet:
```
Offset  Size  Description
0x00    1     Sub-packet length
0x01    N     Sub-packet data
```

Sub-packet types (first byte of data):

| ID | Name | Description |
|----|------|-------------|
| 0x03 | Reliable | Reliable delivery wrapper |
| 0x0b | CPing | Client ping |
| 0x0c | TimeSync | Time synchronization request |
| 0x20 | Movement | Player movement update |
| 0x2a | RequestPos | Request position update |

### 0x03 Reliable Sub-packets

```
Offset  Size  Description
0x00    1     Type (0x03)
0x01    2     Sequence counter
0x03    1     Sub-type
```

| Sub-type | Name | Description |
|----------|------|-------------|
| 0x01 | Resend | Packet retransmission request |
| 0x07 | Multipart | Multi-part packet |
| 0x08 | ZoningEnd | Zone transition complete |
| 0x0d | TimeSync | Server time sync |
| 0x1b | Group1B | NPC/world item group |
| 0x1f | GamePackets | Core game packets |
| 0x22 | InfoRequest | Character/clan/map requests |
| 0x23 | InfoResponse | Character/clan/map responses |
| 0x25 | PlayerInfo | Player info in zone |
| 0x26 | RemoveWorldItem | Remove world object |
| 0x27 | RequestWorldInfo | Request info about world ID |
| 0x28 | WorldInfo | Info about world ID response |
| 0x2b | CityCom | CityCom terminal data |
| 0x2c | StartPosResponse | Starting position data |
| 0x2d | NPCData | NPC initial data |
| 0x2e | Weather | Weather change |
| 0x2f | UpdateModel | Player model update |
| 0x30 | ShortPlayerInfo | Brief player info |
| 0x31 | RequestShortPlayer | Request brief player info |

### 0x1F Game Sub-packets

| ID | Name | Description |
|----|------|-------------|
| 0x01 | Shoot | Weapon fire |
| 0x02 | Jump | Player jump |
| 0x16 | Death | Player death |
| 0x17 | Use | Use object/NPC |
| 0x18 | NPCScript | NPC script trigger |
| 0x19 | Dialog | Dialog interaction |
| 0x1a | NPCResponse | NPC dialog response |
| 0x1b | LocalChat | Local chat message |
| 0x1e | ItemMove | Inventory item move |
| 0x1f | SlotUse | Equipment slot action |
| 0x22 | ExitChair | Leave seated position |
| 0x27 | CloseConnection | Close interaction |
| 0x29 | HackSuccess | Hacking success |
| 0x2c | HackFail | Hacking failure |
| 0x2e | Outfitter | Outfitter interaction |
| 0x2f | GenRep | GenRep usage |
| 0x30 | HLTUpdate | HLT update |
| 0x33 | ChatList | Chat channel list |
| 0x38 | WorldAccess | World access check |
| 0x3b | OtherChat | Other chat channel |
| 0x3d | QuickCommand | Quick command |
| 0x3e | TradeSetting | Trade window setting |
| 0x40 | JoinTeam | Team join request |
| 0x4c | ChangeChannels | Chat channel change |
| 0x4e | PlayerTrade | Player trade action |

### 0x25 Info Sub-packets

| ID | Name | Description |
|----|------|-------------|
| 0x01 | StartProcessor | Start crafting |
| 0x03 | StopProcessor | Stop crafting |
| 0x04 | IncreaseSubskill | Level up subskill |
| 0x06 | Skillboost | Apply skill boost |
| 0x07 | UseBooster | Use stat booster |
| 0x0b | LevelUp | Level up notification |
| 0x0c | SwitchWeaponMod | Switch weapon modification |
| 0x11 | UpdateMoney | Update trade money counter |
| 0x13 | Confirmation | Confirmation/management |
| 0x14 | MoveInventoryItem | Move item in inventory |
| 0x16 | StartReload | Begin weapon reload |
| 0x17 | CombineItems | Combine inventory items |
| 0x18 | RPOSCommand | RPOS interface command |
| 0x19 | MainSkills | Main skills update |
| 0x1f | SynapticImpairment | Synaptic impairment effect |
| 0x25 | StopReload | Stop weapon reload |

### CharInfo — Login Character Data (verified 2026-05-01 across 6 retail chars)

The `CharInfo` packet carries the complete per-character payload the
server sends after login or zone-change. It is delivered through one
of two channels depending on data size:

| CharInfo size | UDP channel | Wrapping |
|---|---|---|
| ≤ ~900 bytes | `0x03/0x2c` (single packet) | 3-byte prefix `02 01 01`, then sections |
| > ~900 bytes | `0x03/0x07` (multipart) | Per-fragment header `[frag_idx LE2][total_frags LE4][disc=0x01][data_size LE3]`; reassembled body starts with `00 22 02 01`, then sections |

**Verified 2026-05-01:** Dr.Stone (fresh Biotech scientist, 810B CharInfo)
used `0x03/0x2c` in both Genesis Dungeon (port 5004) and Plaza Sec-1
(port 5008). The 5 established chars (Krafteo/Norman/Hannibal/Augusto/Oda,
1357–4130B CharInfo) all used `0x03/0x07`. Channel choice is **purely
size-driven** — not shard or zone-specific.

**Channel `0x07` is a generic fragmentation channel.** The discriminator
byte routes the reassembled blob to its handler:
- `disc=0x01` → CharInfo (verified all 6 captures)
- `disc=0x02` → CharsysInfo (per Ghidra reversal; not exercised in current captures)

#### Per-zone UDP gameserver ports

NC2 spins up zones on dynamic UDP ports. **Don't hardcode** — discover
from the wire.

| Zone | Port observed |
|---|---|
| Mixed main-world (Viarosso/Pepper Park/Plaza/Outzone, 2026-04-26) | 12000 |
| Genesis Dungeon (tutorial) | 5004 |
| Plaza Sec-1 (2026-05-01) | 5008 |

#### Body section format

Once reassembled (multipart) or after the 3-byte prefix (single), the
CharInfo body is a series of `[section_id u8][size LE2][body]` sections.
Established-char body has a 3-byte prelude `22 02 01` first, then the
section list begins. Fresh-char single-packet body skips the prelude.

| Section | Size | Contents | Verification |
|---|---|---|---|
| 1 | 10B fixed | `fa`, 3 unknown bytes (per-char), UID LE32, `01 00`, `11 00` const | UID at offset 4 confirmed via Drstone TCP char-list cross-check |
| 2 | 28B fixed | Pools — see "Section 2 byte map" below | **Fully cracked** |
| 3 | 60B fixed | Main skill table — see "Section 3 byte map" | **Fully cracked** |
| 4 | 94B fixed | Subskill table — see "Section 4 byte map" | **Fully cracked, 33/33 subskills positioned** |
| 5 | 494–1516B var | Inventory items (F2 container) | TLV stream, not byte-mapped |
| 6 | 27–1020B var | QB / processor / implants / armor | Variable |
| 7 | 1B fixed | `0x00` const | Verified |
| 8 | 39–215B var | Wallet / Genrep / profile — fresh=39B, established=67B+; see "Section 8 byte map" | Cash position confirmed |
| 9 | 102B fixed | Faction sympathies (20 floats) + current faction | Implementation works |
| 10 | 0 or 10B | Conditional list (death log? PvP record?) | Variable presence |
| 11 | 1–38B var | Conditional list (cabinet contents?) | Variable presence |
| 12 | 1–1089B var | NPC reps / faction kill list | Highly variable |
| 13 | 8B fixed | First 8 bytes of S1 echoed (UID signature footer) | Verified |

#### Section 2 byte map (pools — fully verified)

```
+0  04                CONST    pool_count = 4
+1  04                CONST    stride = 4 bytes per pool
+2/3   HP_cur LE16    [variable]
+4/5   HP_max LE16    [variable]
+6/7   PSI_cur LE16   [variable]
+8/9   PSI_max LE16   [variable]
+10/11 STA_cur LE16   [variable]
+12/13 STA_max LE16   [variable]
+14/15 ff 00          CONST    4th pool sentinel "uncapped"
+16/17 ff 00          CONST    4th pool max sentinel
+18/19 LE16           HP_max × 7/20  HUD bar green-zone (35%)
+20/21 LE16           HP_max × 9/20  HUD bar yellow-zone (45%)
+22/23 LE16           HP_max × 4/20  HUD bar red-zone (20%)
+24    64             CONST    = 100 (synaptic impairment cap)
+25    80             CONST    = 128 (likely runspeed cap)
+26/27 00 00          CONST    trailing padding
```

**HP zone formula verified 2026-05-01 across 6 chars** (Dr.Stone fresh
through Norman tank). The 35%/45%/20% split sums to 100% of HP_max — these
mark the HUD bar color zones.

#### Section 3 byte map (main skills — fully verified)

```
+0..+10   06 09 00 00 00 00 00 00 00 00 01    CONST 11-byte header
+11..+19  STR entry  (9 bytes)
+20..+28  DEX entry  (9 bytes)
+29..+37  CON entry  (9 bytes)
+38..+46  INT entry  (9 bytes)
+47..+55  PSI entry  (9 bytes)
+56..+59  f0 03 00 00                          CONST 4-byte trailer
```

Per-entry layout (9 bytes, order STR/DEX/CON/INT/PSI matches `PlayerCharacter.SKILLS`):

```
+0     u8         level (close to F1-displayed value, with at-login-vs-now drift)
+1/2   u16le      skill points available (Krafteo STR=170, Norman STR=0, etc. — verified)
+3..+6 u32le      total XP (verified 5/5 chars within 1% drift)
+7     u8         skill rate (TBD — varies per char)
+8     u8         skill max  (TBD — varies per char)
```

#### Section 4 byte map (subskills — fully cracked)

`[2e 02 00 01][45 × (value u8, rank u8)]` = 4-byte header + 90-byte body.

Position-to-subskill map (verified across 5 main-world chars + Dr.Stone fresh):

| Pos | Sub | Pos | Sub | Pos | Sub |
|---|---|---|---|---|---|
| 1 | M-C (STR) | 17 | RCL (DEX) | 35 | RES (INT) |
| 2 | H-C (STR) | 20 | ATL (CON) | 36 | IMP (INT) |
| 3 | TRA (STR) | 21 | END (CON) | 37 | WPW (INT) |
| 6 | PCR (STR) | 22 | FOR (STR) | 40 | PPU (PSI) |
| 10 | P-C (DEX) | 23 | FIR (CON) | 41 | APU (PSI) |
| 11 | R-C (DEX) | 24 | ENR (CON) | 42 | FCS (PSI) |
| 12 | T-C (DEX) | 25 | XRR (CON) | 43 | PPW (PSI) |
| 13 | VHC (DEX) | 26 | POR (CON) | 44 | PSR (PSI) |
| 14 | AGL (DEX) | 27 | HLT (CON) | | |
| 15 | REP (DEX) | 30 | HCK (INT) | | |
| 16 | REC (DEX) | 31 | BRT (INT) | | |
|     |           | 32 | PSU (INT) | | |
|     |           | 33 | WEP (INT) | | |
|     |           | 34 | CST (INT) | | |

Padding slots (always 0): 4, 5, 7, 8, 9, 18, 19, 28, 29, 38, 39, 45.
These are NC1-era subskills pruned in NC2 Evolution. **STR/CON are
protocol-interleaved** — `FOR` (STR resist) sits at slot 22 between END
and the resist block (23-26), even though F1 displays it under STR.

The "rank" byte is a tier indicator: 1 for value 0–49, 2 for 50–79, 3
for 80+. Likely used by client for skill-bar rendering.

#### Negative findings

- **Soullight is NOT in CharInfo** at any encoding (u8/s8/u16/s16/transformed). Tested 6 chars with SL values 86/51/10/10/59/10 — no matching offset across ≥3 chars under any of: raw, +K offset, XOR K, low-7-bit, low-nibble, high-nibble. SL travels via TCP or is computed locally.

#### Implementation status (Ceres-J 2026-05-01)

`CharInfo.java` and `PacketBuilderUDP130307.java` now emit retail-faithful
byte layouts and select the wire channel based on body size:

- ✓ **Size-based channel selection.** `getDatagramPackets()` checks body
  size and emits via `0x03/0x2c` single packet when ≤ 900 bytes, or
  `0x03/0x07` multipart when above. Verified by `charInfoUsesSinglePacketForSmallBody`
  test. Wire prefix for single is `02 01`; for multipart is `00 22 02 01`.
- ✓ **Section 1**: explicit `fa [00 00 00] [UID LE32] [01 00] [11 00]`
  layout. Bytes 1-3 emit zeros (TBD: retail has 3 per-char unknown bytes
  there — possibly creation timestamp; client appears to read only UID +
  trailing const).
- ✓ **Section 2 trailing bytes** use `HP_max × 7/20`, `× 9/20`, `× 4/20`
  formulas (HUD bar zone markers).
- ✓ **Section 3** has explicit 11-byte header `06 09 00..00 01` and
  5-skill loop in retail order STR/DEX/CON/INT/PSI.
- ✓ **Section 4** has explicit 4-byte header `2e 02 00 01` and slot 1..45
  loop. `SUBSKILL_WPW` corrected from 45 to 37; `SUBSKILL_PCR=6` added.
- ✓ **Section 7** = single `0x00` byte (retail confirmed).
- ✓ **Section 8** padded to 67 bytes with retail post-cash bytes
  `05 00 04 01 00` (matches established-char retail format; fresh-char
  retail uses 39 bytes).
- ✓ **Section 12** (NPC kill list) moved to its retail position (after S11,
  before S13). Emits 1-byte placeholder `0x00` matching fresh-char retail
  format. Will need expansion when kill log is implemented.
- ✓ **Section 13** mirrors S1[0..7] with same UID at offset 4 and zero
  placeholder at bytes 1-3.

##### Remaining future work

- Section 1 bytes 1-3 — investigate retail semantics (creation time?
  appearance hash?). Currently emits zeros without observed HUD impact.
- Section 8 size adaptation for fresh chars (currently always 67 B; retail
  fresh chars use 39 B). Low impact since extra padding is benign.
- Section 9 byte format — current implementation works for sympathies,
  but full retail byte map not yet verified.
- Sections 5/6/12 dynamic-list contents — populated from inventory/QB/
  kill-log data, but retail entry formats not fully decoded.

**Source:** `src/main/java/server/gameserver/packets/server_udp/CharInfo.java`.
Schema migration: `src/main/java/server/database/SqliteDatabase.java`
(`CURRENT_SCHEMA_VERSION = 1`, `FIDELITY_COLUMNS`, `migrateSchema()`).

## Server Configuration

### irata.cfg

| Setting | Default | Description |
|---------|---------|-------------|
| GUI | true | Enable Swing GUI (false for Docker) |
| ServerIPLocal | auto | Local IP for client connections |
| ServerIPWAN | auto | WAN IP for internet clients |
| ServerVersion | 111 | Protocol version identifier |
| AutoCreateAccounts | true | Auto-create on first login |
| NC2ClientPath | /client | Path to NC2 game files |

### Docker

Use `network_mode: host` so Wine/Proton clients can reach UDP ports. Set `ServerIPLocal` to the host's LAN IP (not 127.0.0.1, which is Wine's loopback).

## File References

| File | Description |
|------|-------------|
| `ini/hash.ini` | File integrity hashes (XOR 0x8D encrypted) |
| `ini/updater.ini` | Server IP, port, patch info |
| `neocron.ini` | Client config (NETBASEIP = server address) |
| `neocron.yaml` | Modern client settings |
| `worlds/worlds.ini` | Zone/world definitions |

## World-Entry Packet Sequence (post-handshake)

After the 3-way UDP handshake completes, the NCE 2.5 client spins on a
loading screen until the server pushes a burst of world-entry packets
terminated by a `ZoningEnd` (`0x03 -> 0x08`). Retail captures of zone
`pepper/pepper_p3` (see `/tmp/retail_capture.pcapng`) show ~15 large
(~440 byte) `0x13` datagrams back-to-back in this phase.

Ceres-J reproduces the burst via `server.gameserver.internalEvents.WorldEntryEvent`
which is scheduled from `HandshakeUDP.HandshakeUDPAnswer2` once the handshake
finishes. The event sends the following in order:

| # | Packet | Wrapper | Purpose |
|---|--------|---------|---------|
| 1 | `UDPAlive` | raw `0x04` | Keepalive/ack before the burst |
| 2 | `UpdateModel` | `0x13 -> 0x2f` | Player body model, hair, beard, textures |
| 3 | `CharInfo` | `0x13 -> 0x03 -> 0x07` (multi-part) | Stats, skills, sympathies, inventory, money |
| 4 | `TimeSync` | `0x13 -> 0x03 -> 0x0d` | Server time baseline |
| 5 | `PositionUpdate` | `0x13 -> 0x03 -> 0x2c` | Start position (REL_START_POS) |
| 6 | `WorldWeather` | `0x13 -> 0x03 -> 0x2e` | Zone weather state |
| 7 | `LongPlayerInfo` (self) | `0x13 -> 0x03 -> 0x25` | Full info for own character, required so the client sees itself in its zone registry |
| 8 | `PlayerPositionUpdate` (self) | `0x13 -> 0x03 -> 0x1b` | Position broadcast |
| 10 | Zone NPCs | `0x13 -> 0x1b` (`SendPresentWorldID`) | One per NPC in the zone |
| 11 | Other players' `LongPlayerInfo` + `PlayerPositionUpdate` | `0x13 -> 0x03` | Only sent if zone has other players |
| 12 | `ZoningEnd` | `0x13 -> 0x03 -> 0x08` | Terminator — releases the loading screen |

Subsequent client 0x03 sync packets are handled by
`client_udp.SyncUDP`, which re-broadcasts the relevant zone state plus a
fresh `ZoningEnd`.

### ZoningEnd (0x13 -> 0x03 -> 0x08)

| Offset | Size | Description |
|--------|------|-------------|
| 0x00 | 1 | `0x13` gamedata header |
| 0x01 | 2 | outer counter (LE) |
| 0x03 | 2 | outer counter + sessionKey (LE) |
| 0x05 | 1 | inner payload size (total - 6) |
| 0x06 | 1 | `0x03` reliable wrapper |
| 0x07 | 2 | reliable sequence counter (LE) |
| 0x09 | 1 | `0x08` ZoningEnd sub-type |
| 0x0A | 2 | mapId (LE) |
| 0x0C | 1 | status flag (`0x00` = ok) |

### WorldWeather (0x13 -> 0x03 -> 0x2e)

| Offset | Size | Description |
|--------|------|-------------|
| 0x00..0x08 | 9 | outer 0x13 header + reliable wrapper |
| 0x09 | 1 | `0x2e` Weather sub-type |
| 0x0A | 2 | mapId (LE) |
| 0x0C | 1 | weather id (0=clear, 1=rain, 2=fog, 3=storm) |
| 0x0D | 1 | intensity (0x00..0xff) |
| 0x0E | 4 | duration in seconds (LE) |

### CHARSYS buffer format (Ghidra-traced, 2026-04-26)

The complete in-memory representation of FULLCHARSYSTEM is delivered as
a TLV stream parsed by `FUN_008447d0` (loop) → `FUN_00845400`
(per-section) which switches on the section ID byte. Each section has the
header `[id 1B][len LE2][data ...len bytes...]` and the parser advances by
`len + 3` bytes. Verified section-id → handler mapping:

| ID | Handler | Purpose |
|----|---------|---------|
| 1 | FUN_00846240 | (unknown — early field) |
| 2 | FUN_00845820 | **Pools** — HP/PSI/STA cur+max, synaptic, soullight |
| 3 | FUN_00846960 | Skills (top-level skills) |
| 4 | FUN_00846d60 | Subskills (HLT/STA/PSI/etc) |
| 5 | FUN_00846320 | Inventory (F2 container) |
| 6 | FUN_00846c30 | QB/Processor/Implants/Armour |
| 7 | FUN_00845710 | (unknown) |
| 8 | FUN_00846470 | **Cash** + GenReps + buddies + clan |
| 9 | FUN_00845a40 | (unknown) |
| 10 | FUN_00846e40 | (unknown) |
| 11 | FUN_008467d0 | (unknown) |
| 12 (0x0c) | FUN_00846160 | "Gogu" container |
| 0xfe | FUN_007fc420 | special — debug/maint |
| 0xff | FUN_007fc360 | special — debug/maint |

**Section 2 (Pools) byte layout** (verified from FUN_00845820 decompile):
```
[num_pools 1B] [stride=4 1B]
per-pool × num_pools: [cur LE2] [max LE2]
   pool[0] = HP, pool[1] = PSI, pool[2] = STA, pool[3] = ?
[unk1 LE2] [unk2 LE2] [unk3 LE2]   // optional; written to charsys+0x3f4/+0x3f8/+0x3fc
[synaptic 1B]                       // 0..100, written to charsys+0x440/+0x444/+0x448
[soullight_byte 1B]                 // signed, wire = 128 + signed; charsys+0x454 = byte − 128
[unk4 LE2]                          // optional → charsys+0x404
```

**The pool max field is volatile.** The PSI/STA tick functions
(`FUN_007e8930`, `FUN_007e8a20`) recompute max from skill data each
frame, so any max value sent in section 2 is overwritten the next
tick. The `cur` field appears to persist (with delta-tracking
logic that preserves "lost HP" amounts across max changes).

**HUD-displayed numbers are NOT raw pools.** Even after Section 2
delivers `HP=1234, max=9999`, the HUD shows the constructor-default
or skill-derived numbers (`260/294`). The displayed value is
computed by `FullCharsysInfo` (`FUN_0080b8b0`) from skill data, NOT
just copied from the pool.

### Multipart 0x07 reassembly + dispatch chain (2026-04-26)

Confirmed from `FUN_0055c270` decompile:
1. Each fragment carries 6 header bytes after the multipart wrapper:
   `[chain_key 1B][0x00 1B][discriminator 1B][total_size LE4]`.
2. The reassembler keys on `chain_key`. Login uses `0x00`; runtime
   updates **must use a different chain_key** to start a fresh chain
   (otherwise fragments append silently to a closed login chain).
3. On full-buffer reassembly, the dispatcher branches on `discriminator`:
   - `0x01` → "CharInfo received" → fire UI event `0xa7` (size 0x45)
     to the WORLDCLIENT-managed UI dispatcher at `this+0x10c`.
     Sets bit 0 of `this+0x2ae`. Has a *guard*: only fires the
     event the FIRST time bit 0 is unset → subsequent disc=0x01
     multiparts do nothing.
   - `0x02` → "CharsysInfo received" → fire UI event `0xa8` with
     full buffer to the same dispatcher. Sets bit 1 of `this+0x2ae`.
     **No guard** — fires every time.
4. After firing, both branches call `FUN_0055ce20` which sets a
   "ready" flag at `this+0x190` ONLY when `(this[0x2ae] & 0xf) == 0xf`
   (all 4 lower bits set). What sets bits 2 and 3 is not yet known.

### ResourceProbe test (2026-04-25) — runtime updates fail

Ran `ResourceProbeEvent` (one-shot harness in `internalEvents/`) immediately
after world entry. Sequence was: send `[Probe] HP -> 1234 / 9999` chat
banner + `PoolUpdate(POOL_HP, delta, 9999)` + `PoolStatusBroadcast`, then
PSI / STA / Soullight 0 / Soullight 100 / Cash 99999 (probe sub=0x04 on
0x02 wrapper) / Cash 0 (probe sub=0x04 on 0x03 reliable wrapper) / heal.

**Result:** all chat banners reached the client (visible in COMMUNICATOR
panel: `[Probe] HP -> 1234 / 9999 (signed delta + status)`), confirming
the wire path is good. Strace + parse-burst.py decoded the on-wire
bytes and they match the documented retail layout exactly:

```
PoolDelta:  1f 01 00 50 e7 ff ff ff 00 00 00 04 64 00 00 00   (delta -25, type=HP, max=100)
PoolStatus: 1f 01 00 30 19 00 64 00 64 00 64 00 64 00         (HP=25, PSI=100, STA=100, max=100,100)
Soullight:  02 04 00 1f 01 00 25 1f 00 00 c8 42               (float 100.0)
CashProbe:  02 07 00 1f 01 00 25 04 9f 86 01 00               (uint32 99999, sub=0x04, 0x02 wrapper)
CashProbe:  03 1c 00 1f 02 00 25 04 bd 27 04 00 00 00 00      (uint32 0,    sub=0x04, 0x03 wrapper)
```

But the **HUD did not move**: HLT stayed at the CharInfo-derived `260/294`,
STA at `121/121`, PSI at `0/0`, CASH at `0`, SOULLIGHT at `0` for the
entire ~30 s probe. (See `docs/probe_hud_unchanged.png` for the HUD frame
and `docs/probe_chat_received.png` for the chat banner that confirms
delivery.)

**Implication.** None of the previously-documented runtime sub-opcodes
actually drive the self-player HUD pools/cash/soullight readouts. The
displayed values are computed locally — most likely from CharInfo
Section 2 (pools) and Section 4 (subskills). The 0x1f→0x30 / 0x1f→0x50
packets seen in the retail death capture probably target *foreign*
entity HP (other players, NPCs), not the player's own HUD.

### FULLCHARSYSTEM event dispatcher (Ghidra 2026-04-26)

`FUN_00803cd0` is `FULLCHARSYSTEM::OnEvent(event_id, param2, param3)` —
the vtable A slot 8 method. It's a giant switch on `event_id`. Key
cases for resource updates:

| Event ID | Behavior |
|----------|----------|
| `0xa7` | Query if `+0xe19` (some pointer) is non-null; returns a byte. Used by case 0xa8 to gate. |
| `0xa8` | **No-op for HUD updates.** Recursively queries `vtable[0]` of `*in_ECX` for events 0xa7 and 0x95. Returns without parsing any buffer. **This is the event the multipart disc=0x02 fires** — explains why our `CharsysOnly` test didn't update the HUD. |
| **`0xb3`** | **The real synchronous-update event.** Calls `FUN_00842a80(buffer, size)` (enqueue LC_RESTORECHAR), then `FUN_007ef260()`, then **`FUN_008447d0(buffer, size)` immediately** (synchronous parse), then `FUN_0080b8b0()` (FullCharsysInfo recompute → fires HUD events 0x1f44/0x1f48/0x1f59/0x85/0x7a). Logs `"CHARSYS : Buffer loaded : F %i U %i"` if `+0x1c > 2`. |

**The latent-command (LC) machinery** — when case 0xb3 calls
`FUN_00842a80(buffer, size)`:
1. Increments a sequence counter at `this+0x30`.
2. `operator new(0x1c)` allocates 28 bytes for an `LC_RESTORECHAR`.
3. `FUN_0083fe30(seq, buffer, size)` — constructor: installs vtable
   `LC_RESTORECHAR::vftable` (= 00a5d860), copies buffer to a fresh
   heap allocation, stores size at `this+0x14` and buffer pointer at
   `this+0x18`.
4. `FUN_00840bc0(lc_obj)` — enqueue for later execution.
5. Later, the LC processor calls `LC_RESTORECHAR::Execute()` =
   vtable B slot 8 = `FUN_00841dc0`, which reads `this+0x14`/`+0x18`
   and calls `FUN_008033d0(buffer, size)` ("CHARSYS : Buffer
   loaded, buffer size: %i"). This is the SECOND, deferred parse.

**The factory `FUN_00840ee0`** is the LC dispatcher used by stream
parser `FUN_008420f0`. Indexed by `byte[2] - 1` of an inbound buffer:
`case 0x11 → LC_RESTORECHAR (no buffer)`, `case 0x12 → LC_SETFRACTIONVALUE`,
plus 23 other LC types (`case 0..0x19`).

### CharsysOnly multipart (disc=0x02) test — also fails (2026-04-26)

Built `CharsysOnly` packet that emits `0x03→0x07` multipart with
discriminator `0x02` (CharsysInfo path) carrying a `[section 2][section 8]`
TLV payload. Used a fresh `chain_key` per call (`0x01, 0x02, …`) to
avoid colliding with login's CharInfo chain at `chain_key=0x00`. Probed
HP=1234/9999, cash=99999, cash=7777777, soullight=±100, etc.

**Result:** chat banners arrived, fragments were on the wire (verified
via parse-burst.py), but the HUD readouts did **not** change. **Root
cause now identified** (above): the multipart disc=0x02 path fires UI
event `0xa8` to FULLCHARSYSTEM, but `FUN_00803cd0 case 0xa8` is just
a query — it doesn't parse the buffer. **Only event `0xb3` synchronously
parses + recomputes.** Multipart's `0xa7`/`0xa8` are first-CharInfo-only.

A second test (disc=0x01 + 72-byte filler to trigger event 0x13ef
without re-firing the bit-0-guarded 0xa7) also failed: 0x13ef is not a
case in FUN_00803cd0 either. So the multipart 0x07 wrapper has no path
to event 0xb3 from the server side post-login.

### CONFIRMED: HUD pool maxes are subskill-derived (2026-04-26)

**Empirical proof.** Modified `CharInfo.java` Section 4 to send these
overrides at login:
```java
case PlayerCharacter.SUBSKILL_HLT (27): lvl=200, pts=250;
case PlayerCharacter.SUBSKILL_ATL (20): lvl=200, pts=250;
case PlayerCharacter.SUBSKILL_END (21): lvl=200, pts=250;
case PlayerCharacter.SUBSKILL_PSU (32): lvl=200, pts=250;
```

**Result:** the HUD readout changed for the FIRST time since we began
testing:

| Field | Before (default subskills 0/0) | After (subskill 200/250) |
|-------|--------------------------------|--------------------------|
| HLT   | 260 / 294                      | **303 / 1116**           |
| STA   | 121 / 121                      | **244 / 281**            |
| PSI   | red / 0                        | (still 0/0 visible — needs PSU/PPU/APU) |
| CASH  | 0                              | (unchanged, separate path) |
| SOUL  | 0                              | (unchanged, separate path) |

Saved evidence: `ceres-j/docs/hud_subskill_test_proof.png`.

**Conclusion.** The HUD-displayed pool maxima are computed
client-side by `FUN_007e8930` (PSI tick) / `FUN_007e8a20` (STA tick)
/ `FUN_007e87d0` (HP tick) **from the subskill data delivered in
CharInfo Section 4 at login**. Each tick reads its relevant subskill
via `FUN_007e7c00(skill_id, 0, scaling_constant)` and produces a max
that the FullCharsysInfo recompute then exposes to the HUD via UI
events (0x1f44, 0x1f59, etc.).

**This is the canonical server lever for pool maxes.** To change a
player's max HP/STA/PSI server-side:
1. Mutate the subskill array on `PlayerCharacter` (`setSubskillLVL` /
   `setSubskillPtsPerLvl` etc.).
2. Force a CharInfo redelivery (zone change via `ForcedZoning`, or
   relog).
3. The new subskills feed through the local recompute and the HUD
   updates.

For runtime mutations without relog, no path is currently known.

### HUD pool **current** values come from a different source

The HUD pool render (`FUN_00726c50`, `FUN_0070e100`, `FUN_00745440`,
all using the format string `"%d / %d"` at `00a462f4` and `00a18fb8`)
reads from a singleton at `DAT_011633c0`:

- `*(char *)(DAT_011633c0 + 0x49)` and `+0x4a` — byte-sized pool
  values (used by `FUN_0070e100`).
- Local `short` variables in `FUN_00726c50` — likely the bigger
  HLT/STA values like 260/294/121.

`DAT_011633c0` has exactly **one writer** in the entire binary:
`FUN_005b66c0` at `005b677e`, the constructor for the `BASERPOS`
(base radar position?) class — `*in_ECX = BASERPOS::vftable`. The
constructor calls `DAT_011633c0 = thunk_FUN_005c5f30();` (a
singleton getter). After init, all access is read-only — the
singleton's *fields* are mutated by other code. Per-offset writers
were not yet identified by the simple operand-string scan; further
work would need a smarter Ghidra script that resolves the actual
target offsets of MOV instructions.

### Case 0xb3 is dead code (Ghidra 2026-04-26)

Searched the entire `neocronclient.exe` for any instruction that
loads constant `0xb3` followed by an indirect call within 30
instructions. Filtered the matches to the `[reg+0x20]` (vtable slot 8)
pattern — **zero hits**. Without that filter we get 7 hits, but they
are all `[EDX+0x28]` (slot 10) on different objects, or fixed-address
calls inside `__report_gsfailure` runtime code. None target
FULLCHARSYSTEM::vtable[8].

**Conclusion: case 0xb3 in `FUN_00803cd0` is NOT REACHED in this
NC2 Evolution build.** Whatever code path historically fired 0xb3 has
been removed or replaced with a different mechanism. The buffer
parse + recompute pipeline (`FUN_008447d0` → `FUN_0080b8b0`) is
exercised only:
1. At login via the constructor `FUN_007e4cc0` (loads
   `Chartest.dat` if a debug flag is set, otherwise just zero-init).
2. At login via the multipart 0x07 disc=0x01 path → fires UI event
   `0xa7` to FULLCHARSYSTEM::vtable[8] case 0xa7 — which only
   queries `+0xe19`, doesn't parse anything.

The HUD-displayed pool numbers (`HLT 260 / 294`, `STA 121 / 121`,
etc.) are computed locally by `FUN_007e8930`(PSI), `FUN_007e8a20`(STA),
and similar tick functions, from the **subskill data** delivered in
CharInfo Section 4. Those derived values are then maintained by the
tick functions; nothing in the binary writes them from a network
packet at runtime.

**This is a design decision, not a missing feature.** NC2 Evolution
appears to have moved away from server-pushed pool updates and toward
fully client-side derivation. The server provides:
- **Skill levels and subskills** in CharInfo at login → drive max pools.
- **Damage events** through some unknown sub-opcode (PoolUpdate
  0x1f→0x50 fires correctly on the wire but has NO entity ID; we
  tested it and the HUD did not respond — likely it targets foreign
  entities only or requires an ID we're not setting).

**Real path forward** (deferred — not pursuing further this session):
1. **Modify subskill data at login** — change Section 4 of CharInfo
   to deliver different HLT/STA/PSI subskill values, and the locally
   computed displayed max will follow. This is the only known
   server-controlled lever.
2. **Cash and Soullight** are the dynamic exceptions — they're
   delivered as raw values (not skill-derived), but their runtime
   sub-opcodes remain unidentified. A fresh retail pcap of a
   `/give cash` admin command or vendor purchase would resolve this.
3. **Skip the runtime update entirely**: when cash/soullight changes
   server-side, force a client *zone* (which retriggers full CharInfo
   delivery and runs through the login-only update path).

### Character Resource Updates — verified findings (2026-04-26)

This section records ONLY what's been verified end-to-end against
retail traffic with HUD-screenshot ground truth. Earlier speculation
and dead-end hypotheses are archived in
`docs/cash_investigation_archive.md` and the corresponding git history.

#### HUD pool maxes (HLT/STA/PSI) — subskill-derived (PROVEN)

The HUD-displayed max for HLT/STA/PSI is computed locally by the
client from CharInfo Section 4 subskill data on every login/zone
transition. The relevant subskills:

| Subskill | Index | Drives |
|---|---|---|
| HLT | 27 | Health pool max |
| ATL | 20 | Stamina pool max (athletic) |
| END | 21 | Stamina pool max (endurance) |
| PSU | 32 | PSI pool max |

Server-side lever: mutate the subskill table on `PlayerCharacter` and
force CharInfo redelivery via `ForcedZoning`. Confirmed by setting
those four subskills to `lvl=200, pts=250` and observing HUD HLT/STA
move from 260/294 → 303/1116 and 121/121 → 244/281. No runtime packet
moves these values mid-zone — the tick functions (`FUN_007e87d0` HP,
`FUN_007e8930` PSI, `FUN_007e8a20` STA) recompute from the local
subskill table.

Implementation: `!setmaxhp <lvl>`, `!setsub <idx> <lvl> <pts>` in
`AdminCommandHandler`. See `hud_pool_path_confirmed.md` memory.

#### Cash carrier — `0x03→0x1f→[01 00 25 13 …]` family (VERIFIED on retail wire)

The `0x25 0x13` sub-opcode is a **transactional-event channel**.
Inner format is a list of one or more event blocks:

```
01 00 25 13   [txn LE2]   [sub-tag 1B]   [data]      [next event…]
```

| Sub-tag | Payload | Meaning |
|---|---|---|
| `0x04` | LE32 | wallet update (cash) |
| `0x0b` | 2 bytes | event marker (zone transition? unknown) |
| `0x0e` | 1 byte | event marker (unknown) |
| `0x05` | variable | compound multi-event |
| `0x02`, `0x03` | variable | compound multi-event |

**Wire-evidence** (retail capture
`nc2_strace_RETAIL_FULL_PCAP_TRACE_20260426_154648.pcap` with
HUD-screenshot ground truth from 3 mob kills):

| Event | Packet bytes | LE32 cash | HUD CASH after |
|---|---|---|---|
| Kill 2 | `03 99 01 1f 01 00 25 13 e8 2f 04 71 0d 08 00` | `71 0d 08 00` = 527,729 | 527,729 ✓ |
| Kill 3 | `03 36 02 1f 01 00 25 13 ed 2f 04 a6 0d 08 00` | `a6 0d 08 00` = 527,782 | 527,782 ✓ |

The 41-byte vendor-buy variant is the SAME packet with 30 bytes of
item-receipt data appended after the cash field; the cash slot is
always at offset 11 inside the inner data.

Implementation: `CashUpdate.java`, `!setcash` admin command. The
class emits the correct format byte-for-byte (verified on the
Ceres-J wire via dumpcap).

> ⚠️ **Open issue:** the Ceres-J HUD CASH widget does NOT yet
> display the value despite the packet being byte-correct. Retail's
> HUD shows the cash from the moment of WORLDENTRY (i.e. CharInfo
> Section 8 initializes the widget); something about our CharInfo
> doesn't activate the widget. See "Open issues" below.

#### Sub-op map — `0x03→0x1f→01 00 25 [op]` family

Observed S→C in the FULL_PCAP retail capture:

| Op | Count | Body | Likely meaning |
|---|---|---|---|
| `0x23` | 738 | 1 byte counter | Heartbeat (~1/s) |
| `0x13` | 12 | variable | **Cash + transactional events** (above) |
| `0x22` | 5 | 14B (7× LE16) | Stat block — fixed values |
| `0x1f` | 2 | float32 | Likely max soullight (sample = 100.0) |
| `0x16` | 1 | empty | Unknown rare event |

The `0x22` packets all have identical body
`3d 08 95 03 95 03 90 01 ae 02 1e 03 00 00`. Fires at world-entry
and zone changes (3 of 5 occurrences at WORLDENTRY/ENTER_SEWER/
AFTER_KILL_3 boundaries) — looks like a fixed stat-cap broadcast.

#### Outer sub-type `0x3c` — attribute-update channel

A separate 12-byte packet, distinct from the `0x25 13` family:

```
3c 01 00 [TAG] [LE32 a] [LE32 b]
```

Observed tags: `0x04` during vendor buy (values 19,544 / 17,913),
`0x09` at fall-impact, `0x01`/`0x02` in idle traffic. Per-event
broadcast that does NOT drive the HUD CASH widget when sent alone
(verified on Ceres-J — `!attr3c 04 NNN NNN` had no effect).
Currently treated as a **secondary attribute snapshot** of unknown
purpose.

#### Vendor-buy command flow (retail-traced)

Sub-ops on the C→S side:

| Direction | Sub-op | Inner bytes | Meaning |
|---|---|---|---|
| C→S | `0x4c` | `01 00 4c ff ff 03 00` | Open vendor |
| C→S | `0x1e` | `01 00 1e <item LE16> <flags> ff ff <qty LE16>` | Buy item |
| S→C | `0x25 13` (41B) | `01 00 25 13 [txn][04][cash][item-receipt 30B]` | Cash deduct + item delivered |
| C→S | `0x14` | `01 00 25 14 <item-id-echo>` | Buy ack |

#### Fall damage — wire signatures

C→S during fall (16-burst): `0x03→0x1f→[01 00 3d 11 00 00 00 00]` ×16
in 280ms — frame-by-frame airborne position pings.

C→S land impact (1×): `0x03→0x1f→[01 00 02]` (3-byte inner).

S→C fall-state pair: `0x03→0x2d` sub-types `0x0f` (velocity float
`00 00 80 bf` = -1.0) and `0x10` (Z-position float `00 00 d4 c3` =
-424.0). Fires once per fall frame the server acknowledges. Sub-types
are absent from non-fall windows.

HP loss is applied via the existing PoolUpdate path
`0x1f→0x50 [delta LE4 signed][pool_type 0x04]`.

#### CharInfo field positions — confirmed via 5-char differential analysis (2026-04-26)

Differential correlation across 5 retail characters with HUD-screenshot
ground truth (Krafteo, Norman Gates, Hannibal Lecture, Father Augusto,
Oda Daramitz — all distinct HP / STA / PSI / CASH / SL values):

| Field | Section + offset | Encoding | Confidence |
|---|---|---|---|
| HP_cur | S2+2 | u16le | 3/5 (drifts post-login) |
| HP_max | S2+4 | u16le | 5/5 ✓ |
| PSI_cur / PSI_max | S2+6 / S2+8 | u16le | 5/5 ✓ |
| STA_cur / STA_max | S2+10 / S2+12 | u16le | 5/5 ✓ |
| **CASH** | **S8+1** | u32le | **5/5 ✓** |
| Soullight | — | — | NOT FOUND in CharInfo |

Section 8 first byte (S8+0) varies per character (`0x0a`, `0x09`, `0x02`)
— it's NOT a fixed `0x0a` tag as previously assumed. Likely a count
field (GR-related?). Our Ceres-J `CharInfo.java` hardcodes `0x0a`
which works for some chars but doesn't match the variability observed.

Section 8 size also varies wildly: 43B (Father Augusto, low-rank),
51B (Hannibal), 67B (Krafteo), 159B (Oda Daramitz, high-rank), 215B
(Norman Gates). The size grows with GR/buddy/clan list lengths.

**Soullight not in CharInfo.** Searched all 5 chars for SL values as:
- raw byte (`SL`)
- offset-128 byte (`128+SL` per legacy docs)
- LE16/LE32 integer
- LE float

None matched at a consistent offset across all 5 chars. SL is likely
delivered via a separate packet (probably `0x25 1f` float we observed
once per zone change at WORLDENTRY, value 100.0) or computed locally
from faction sympathies / GR data.

Tools added 2026-04-26:
- `tools/correlate_charinfo.py` — section-aware multi-char differential
- `tools/multichar_template.json` — per-char known values

#### CharInfo Section 8 — retail layout = 67 bytes

Decoded from the FULL_PCAP retail multipart reassembly:

```
Offset  Bytes                          Meaning
──────  ─────────────────────────────  ───────────────────────────
0       0a                             Cash tag
1..4    [LE32]                         Wallet credits
5..6    [LE16]                         Number of GRs (5 in capture)
7..9    04 01 00                       Constant prefix
10..27  18 bytes (3× LE16 × 3 groups)  GR list / faction buffs
28..33  04 04 00 00 02 55              Constants / capability bits
34..41  86 01 00 fe 85 01 00 00        Possibly EpicQuest progress
42..45  [LE32]                         Transaction-id-like field
46..51  6 zero bytes                   Padding
52..55  [LE32]                         Constants
56..59  [LE32]                         Constants
60..63  [LE32]                         Constants
64..66  01 34 03 98 03                 Trailing constants
```

Ceres-J's legacy 39-byte Section 8 was missing 28 trailing bytes plus
had different post-cash bytes. Padded to 67 bytes 2026-04-26.

#### Open issues

1. **HUD CASH not initialized at Ceres-J login.** Wire bytes match
   retail format (verified via dumpcap on lo) but the widget stays
   at 0 even with 67-byte Section 8 + correct `0x25 13 04` runtime
   updates. Path forward: byte-diff our entire CharInfo against
   retail's reassembled multipart and replace section bodies one at
   a time until HUD activates.
2. **SOULLIGHT widget** never observed updating in any test; retail's
   HUD shows 86 throughout. The `0x25 1f` float carrier (= 100.0 at
   retail WORLDENTRY) is the prime suspect for max-soullight init;
   current value source unknown (possibly Section 2's
   `[synaptic][soullight 1B]` byte encoded as `128 + signed_value`).
3. **PSI pool** shows `0/0` on Ceres-J despite the subskill table
   having entries. Worth checking `MISC_PROFESSION` mapping vs retail.

### WorldInfo (0x13 -> 0x03 -> 0x28)

NPC world-state packet. Sent proactively by the server at ~2 Hz per NPC to
keep the client's world model populated. Also sent in response to client
`0x27 RequestWorldInfo`. Inner payload layout confirmed from retail
`pepper_p3` pcap (21 NPCs, all verified):

| Inner offset | Size | Description |
|-------------|------|-------------|
| 0-1   | 2 | `00 01` (constant) |
| 2-3   | 2 | world-object ID (LE16) — must match `0x1b` broadcast ID |
| 4-5   | 2 | `00 00` (padding) |
| 6-9   | 4 | world-instance reference (LE32, `0x008897A7` = 8958887) |
| 10-11 | 2 | NPC type ID (LE16) — index into client's `pak_npc.def` |
| 12-13 | 2 | Y position (LE16) |
| 14-15 | 2 | Z position (LE16) |
| 16-17 | 2 | X position (LE16) |
| 18    | 1 | `0x00` (padding) |
| 19    | 1 | variable byte (unknown, session-related) |
| 20    | 1 | zone sub-sector byte (`0x22` or `0x23` observed) |
| 21-23 | 3 | `00 00 00` (padding) |
| 24-28 | 5 | NPC stats (unknown encoding; zero-safe) |
| 29    | 1 | combat class |
| 30-34 | 5 | `00 00 00 00 00` (padding) |
| 35+   | N | `script_name\0` (from `pak_npc.def` column 22) |
| +     | M | `model_name\0` (from `pak_npc.def` column 23) |

The client (`SCRIPTEDPLAYER` subsystem) reads inner[10-11] to look up the
NPC type in its local `pak_npc.def`, then reads inner[35+] as the script
name to spawn the NPC's AI script.

**Client-side spawn flow** (from Ghidra disassembly of neocronclient.exe):

```
SCRIPTEDPLAYER ctor (FUN_0069a580)
  ├── reads script_name from packet at param_3[6]
  ├── reads model_name from packet at param_3[7]
  └── thunk_FUN_007afe80(script_name, model_name)
        ├── stores script_name in this+0x20 (std::string)
        ├── stores model_name in this+0x38 (std::string)
        └── FUN_0081e310(script_name, model_name)
              ├── hash lookup in script-factory map (FUN_0081b430)
              ├── if found:    return factory.vtable[0x20](model_name)
              └── if NOT found: log "Unable to find script: %s" (gated by log level < 3)
                                 return 0  ← triggers SCRIPTEDPLAYER spawn fail
```

The "Unable to find script:" log is gated by `*DAT_0118d53c < 3` so it may
not appear unless the verbosity level is raised. The factory map is
populated at startup; this client install appears to lack the script
registrations for the NPC types we send.

**NPC type IDs** (from `pak_npc.def`, column 1 = setentry ID):

| Type ID | Script name | Model | Description |
|---------|-------------|-------|-------------|
| 1 | `NCPD` | `WCOP` | NCPD cop (MaxHP 15168, level 120) |
| 20 | `S_CITYADMIN` | `WSK` | City administrator |
| 23 | `DUMMY_SEC` | `WLDK` | City Admin faction security guard |
| 29 | `trader_pa` | `WSK` | Personal assistant / trader |
| 120 | `DUMMY_SEC` | `WLDK` | Tsunami faction security guard |
| 121 | `TSUNAMI` | `WSK` | Tsunami faction rep |
| 191 | `S_CITYMERCS_0` | `WLDK` | CityMercs faction guard |
| 215 | `DRUGDEALER` | `WSK` | Drug dealer |
| 375-378 | ` ` | `DANCER` | Dancer (no script) |
| 2600 | `TECHFREAK` | `WSK` | Tech dealer |
| 3568/3569 | `RSM_TS` | `WSK` | Tsunami RSM service rep |
| 3606 | `FSM_TS` | `WSK` | Tsunami FSM service rep |

## Cipher Discovery Timeline

This section documents the investigation path for future reference.

1. **Initial hypothesis** (2026-04-10): `ObfuscateStreamBuf` is the wire
   cipher. Based on Ghidra decompile of vtable at `0x00a1fd1c` showing
   XOR-based encryption with seed `DAT_00b05360 = 0x3039`.

2. **Entropy analysis** (2026-04-11): retail S→C UDP confirmed encrypted
   at ~7.99 bits/byte across 4 captures. `PacketObfuscator.java`'s
   per-packet XOR disproven (entropy unchanged after decode attempt).

3. **ObfuscateStreamBuf disproven** (2026-04-12): 16-bit brute-force
   across 65536 seeds returned 0 candidates in both per-packet and
   cumulative modes. Mathematically proven incompatible with retail data
   (best single key_byte matched only 6/88 packets, expected ~1.7 by
   chance).

4. **Real cipher found** (2026-04-12): Ghidra trace of WinSock callers
   via `FindUDPCipher.java` → `FUN_0055f5a0` (recv handler logging
   "Receive 0 Buffer") → `FUN_0055ec10` (session dispatcher) →
   `FUN_0055ff30` (recvfrom decrypt wrapper) and `FUN_00560090` (sendto
   encrypt wrapper) → `FUN_004e36e0` (LFSR PRNG).

5. **Validation**: 88/88 retail S→C packets decrypted successfully.
   All decode to `0x13` gamedata with sequential counters (14→107+).
   Decoded entropy drops to 6.08 with 23.9% null bytes (plaintext
   structure). Cross-validated against known Ceres-J plaintext
   structure.

6. **Burst comparison** (2026-04-12): parsed and diffed retail vs
   Ceres-J sub-packet streams. Key retail findings:
   - Sub-packet lengths are **2 bytes LE** in retail (Ceres-J uses 1).
   - Retail does **NOT** send ZoningEnd (`0x03→0x08`) during login.
   - Retail sends InfoResponse (`0x03→0x23`) × 2 and ChatList
     (`0x03→0x33`) that Ceres-J never sent.
   - 10 CharInfo multipart fragments (vs our 3).
   - An undocumented `0x02` sub-wrapper (like `0x03` reliable but type
     0x02) carries UpdateModel, Weather, TimeSync, InfoResponse
     sub-types.
   - None of our experimental packets (`0x19/0x04`, `0x19/0x07`,
     `0x03` SyncResponse) appear in retail.

7. **Cipher implemented** (2026-04-12): `WireEncrypt.java` ported from
   the Ghidra decompile. Wired into `GameServerUDPConnection.sendPacket()`
   so every outgoing UDP datagram is encrypted with the correct 4-byte
   header. This fixes the plaintext misrouting where `0x13` was being
   stripped to `0x04` by the WinSockMGR `-0x0F` offset.

8. **Retail-matching burst applied** (2026-04-12): WorldEntryEvent
   updated to mirror retail:
   - Removed ZoningEnd, CommandIdAck, SyncResponse.
   - Added `InfoResponse.zoneInfo()` (0x23, 6B) after CharInfo.
   - Added `ChatList` (0x33, 2B) and `InfoResponse.sessionInfo()`
     (0x23, 10B) in the zone-data phase.
   - Reordered phases: CharInfo → InfoResp+TimeSync → ChatList+InfoResp+zone → UpdateModel → NPCs.
   - Kept 1-byte sub-packet lengths for now; switching to 2-byte is
     still a future enhancement (client accepts both).

Tools used: `tools/ghidra/FindUDPCipher.java`,
`tools/ghidra/DecompileAddr.java`, `tools/decrypt-retail.py`,
`tools/parse-burst.py`.

Detailed analysis: `docs/retail_burst_analysis.md`.

## Implementation Status (Ceres-J)

| Feature | Status | Notes |
|---------|--------|-------|
| TCP Handshake | Complete | |
| Auth (0x8480) | Complete | 30-byte offset for modern client |
| AuthB (0x8301) | Complete | Character slot selection |
| Character CRUD | Complete | Create, list, delete |
| UDPServerData | Complete | IP, port, session. Flags: `0x00830000` (retail match). Sent once per login from `GetGamedata` only; `GetUDPConnection` is a no-op. |
| Location (0x83 0x0c) | Complete | Zone name sent |
| GameinfoReady (0x83 0x0d) | Complete | Sent between UDPServerData and Location (retail match) |
| TCP Keepalive (0x83 0x8f) | Complete | Sent every 10s on TCP (retail match) |
| UDP Encryption (C→S) | Complete | `WireEncrypt.decrypt()` on both `PlayerUdpListener` and `ListenerUDP`. Confirmed against 72/72 retail C→S packets. Legacy `PacketObfuscator` kept only for old handshake path. |
| UDP Encryption (S→C) | Complete | LFSR CFB cipher in `WireEncrypt.java`. All outgoing UDP datagrams encrypted with per-packet random 16-bit seed. Applied in `GameServerUDPConnection.sendPacket()`. |
| UDP Cipher Cracked | **Yes** | 88/88 retail packets decrypted. LFSR PRNG at `FUN_004e36e0`, encrypt at `FUN_00560090`, decrypt at `FUN_0055ff30`. |
| UDP Handshake | Complete | 3-way handshake + UDPAlive |
| Zone Loading / World Entry | Complete | Session survives indefinitely. `WorldEntryEvent` streams CharInfo, UpdateModel, PositionUpdate, LongPlayerInfo, weather, NPCs. Heartbeats deferred until zone-handoff completes. |
| Zone-handoff (25s disconnect) | **Fixed** | Root cause: duplicate `UDPServerData` from `GetUDPConnection` created second WinSockMGR socket, OOO timeout. Fix: `GetUDPConnection` is now a no-op. |
| CharInfo multipart (0x03→0x07) | Complete | Retail-format per-fragment headers: `[0x00][discriminator][total_size LE4]`. chain_key=0x00. |
| 0x02 wrapper | Complete | Used for InfoResponse, Weather, UpdateModel, Soullight sub-types (retail match) |
| NPC Spawns | Complete | SQLite-backed, 8 NPCs in plaza_p1. Types from `pak_npc.def` (191=CityMercs, 20=CityAdmin, 29=Trader, 2600=TechDealer, 215=DrugDealer). `script_name`+`model_name` stored and sent in 0x28 at inner[35+]. |
| WorldInfo (0x28) | Complete | Retail-confirmed layout: type_id at [10-11], script_name at [35+], model_name immediately after. Fixed 2-byte offset bug (name was at [33] instead of [35]). Verified 2026-04-25: client reads correct script names ('S_CITYMERCS_0', 'trader_pa', 'DRUGDEALER', 'TECHFREAK', 'S_CITYADMIN'). |
| HLT/STA/PSI pool MAX | **Solved (subskill-derived)** | HUD displays max computed locally by tick functions from CharInfo Section 4 subskill data. Server lever: mutate subskills (HLT=27, ATL=20, END=21, PSU=32) + force CharInfo redelivery via `ForcedZoning`. `0x1f→0x30` PoolStatusBroadcast and `0x1f→0x50` PoolUpdate are wire-correct but only render foreign-entity HP. |
| HLT/STA/PSI pool CURRENT | **Format known, in use** | `0x1f→0x50 [delta LE4 signed][pool_type 0x04/05/06]` PoolUpdate works for damage/heal events on the local player. PoolStatusBroadcast `0x1f→0x30` is sent ~1Hz to keep the client tick from staling. |
| Soullight | **Format known; HUD activation OPEN** | Wire format `0x03→0x1f→01 00 25 1f [float LE4]` (= max, observed value 100.0 in retail at WORLDENTRY). Current value source unknown — possibly Section 2's `[synaptic][soullight 1B]` byte. Ceres-J HUD shows 0 across all tests. |
| Cash | **Carrier identified; HUD activation OPEN** | Runtime: `0x03→0x1f→[01 00 25 13 [txn LE2][04][cash LE32]]` — verified byte-for-byte against retail HUD readings on 3 mob kills. `CashUpdate.java` emits the correct format on the wire (dumpcap-confirmed). HUD CASH widget on Ceres-J still shows 0 — Section 8 of CharInfo (67B in retail vs our 67B padded clone) doesn't yet activate the widget. Needs full retail-vs-Ceres-J CharInfo byte-diff. |
| ScriptedPlayer NPC spawn | Client-side data issue | Script names transmit correctly but client logs `SCRIPTEDPLAYER : Script spawn failed`. Root cause traced via Ghidra (`FUN_0069a580` → `FUN_007afe80` → `FUN_0081e310`): the client looks up scripts in a hash-map registry; the map is empty for these names. The `pak_<name>.lua` files exist in `/home/javier/Neocron2/scripts/lua/` but aren't auto-registered. NOT a protocol bug — needs a script-loading or content-pack fix on the client side. |
| ShortPlayerInfo (0x30) | Partial | Triggered by client `0x31 RequestShortPlayerInfo`. Client logs `LSTPLAYER : Update Message corrupted Size:18 3` — packet structure differs from retail. Format needs further capture analysis. |
| Zone broadcasts | Complete | Compound 0x1b + 0x2d + 0x28 at 2 Hz |
| Movement | Partial | Inbound UDP 0x20 parsed, SMovement broadcast via `Zone.sendPlayerMovement` |
| Combat | Not implemented | |
| Chat | Partial | Local + global chat |
| Inventory | Partial | Basic item moves |
| NPC Interaction | Not implemented | |
