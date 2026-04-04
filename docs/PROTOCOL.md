# Neocron 2 Network Protocol Documentation

Reverse-engineered protocol documentation for the Neocron 2 (NCE 2.5.766) game client.

## Architecture

The game uses three network layers:

| Layer | Transport | Port | Purpose |
|-------|-----------|------|---------|
| Info Server | TCP | 7000 | Server list, initial authentication |
| Game Server | TCP | 12000 | Login, character management, game data |
| Game Server | UDP | 5000-5005 | Real-time gameplay (movement, combat, chat) |

Retail server uses UDP port **5005**. The port is sent to the client via the `UDPServerData` TCP packet.

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
  |<-- Location (0x830c) ---------|  (zone name + ID)
  |--- GetUDPConnection (0x873c)->|
  |<-- UDPServerData (0x8305) ----|  (repeated)
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
0x10    4     Flags/unknown (retail sends 0x00890000)
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

### Obfuscation (NCE 2.5.x)

The modern client applies per-packet obfuscation to **outgoing UDP packets**. Server responses are sent **unencrypted**.

**Algorithm** (reverse-engineered from `ObfuscateStreamBuf` in neocronclient.exe):

```
Encryption (client → server):
  seed = random byte (0-255)
  for each byte at position i (0-based):
    s = (i + 1) * seed
    encrypted[i] = plaintext[i] ^ (s >> 16 & 0xFF) ^ (s & 0xFF)

  Result: encrypted[0] = plaintext[0] ^ seed
          (since (1 * seed) >> 16 = 0 for seed < 256)

Decryption (server receives):
  seed = encrypted[0] ^ expected_first_byte
  For handshake packets: seed = encrypted[0] ^ 0x01
  Apply same XOR formula to decrypt remaining bytes.
```

The first plaintext byte is always a known packet type (0x01, 0x03, 0x04, 0x08, or 0x13), allowing the seed to be recovered.

**Source:** `ObfuscateStreamBuf` class at vtable 0x00a1fd1c in neocronclient.exe. Decompiled methods:
- vtable[3] (overflow/write): `FUN_004dff90` — encrypts outgoing bytes
- vtable[6] (underflow/peek): `FUN_004e02a0` — decrypts without advancing
- vtable[7] (underflow/read): `FUN_004e0220` — decrypts and advances position

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

## Implementation Status (irata)

| Feature | Status | Notes |
|---------|--------|-------|
| TCP Handshake | Working | |
| Auth (0x8480) | Working | 30-byte offset for modern client |
| AuthB (0x8301) | Working | Character slot selection |
| Character CRUD | Working | Create, list, delete |
| UDPServerData | Working | IP, port, session |
| Location | Working | Zone name sent |
| UDP Obfuscation | Working | PacketObfuscator.java |
| UDP Handshake | Working | 3-way handshake + UDPAlive |
| Zone Loading | Not implemented | Needs ~7KB of world init data |
| Movement | Not implemented | UDP 0x20 packets |
| Combat | Not implemented | |
| Chat | Partially implemented | Local + global chat |
| Inventory | Partially implemented | Basic item moves |
| NPC Interaction | Not implemented | |
