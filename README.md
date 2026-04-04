# Ceres-J

Open-source Neocron 2 server emulator written in Java. Fork of the original [Irata](https://sourceforge.net/projects/irata/) project, updated to work with the modern NCE 2.5.x game client.

> **Ceres** — In Neocron lore, Ceres is one of the asteroid mining stations and a key location in the game's storyline. The "-J" suffix denotes the Java implementation.

## Features

- **TCP Login & Authentication** — Handles the modern NCE 2.5.x auth protocol (30-byte header offset)
- **Character Management** — Create, list, delete characters with CSV-based persistence
- **UDP Game Connection** — Encrypted UDP handshake with obfuscation support (reverse-engineered `ObfuscateStreamBuf`)
- **Game Data Transfer** — Zone locations, player models, game data exchange
- **Web Server** — HTTP status page and server list
- **Info/Patch Server** — Server discovery and version checking
- **Docker Support** — Full Docker Compose setup for local development
- **Auto-Create Accounts** — New accounts created on first login

## Quick Start

### Docker (recommended)

```bash
# Clone the repo
git clone https://github.com/igwtech/Ceres-J.git
cd Ceres-J

# Set the path to your Neocron 2 game installation
export NC2_CLIENT_PATH=/path/to/Neocron2

# Build and run
docker compose up -d

# Check server status
docker compose logs -f

# Stop
docker compose down
```

The server will be available at:
- **Game Server TCP**: port 12000
- **Game Server UDP**: port 5000
- **Info Server**: port 7000
- **Patch Server**: port 8020
- **Web Server**: http://localhost:8080

### From Source

**Requirements:** Java 11+, Maven 3.x

```bash
# Build
mvn clean package

# Run
java -jar bin/ceres-j-1.0-SNAPSHOT.jar
```

The server reads `ceres.cfg` from the working directory.

## Configuration

### ceres.cfg

| Setting | Default | Description |
|---------|---------|-------------|
| `GUI` | `true` | Enable Swing GUI (set `false` for Docker) |
| `ServerIPLocal` | `auto` | Local IP for client connections |
| `ServerIPWAN` | `auto` | WAN IP for internet clients |
| `ServerVersion` | `111` | Protocol version |
| `ServerName` | `Ceres-J` | Server display name |
| `AutoCreateAccounts` | `true` | Auto-create accounts on first login |
| `CharsPerAccount` | `4` | Max characters per account |
| `NC2ClientPath` | — | Path to Neocron 2 game files (required) |
| `WebServerPort` | `8080` | HTTP status page port |

### Docker Configuration

The Docker setup uses `network_mode: host` so that Wine/Proton game clients can reach the UDP port. Set `ServerIPLocal` in `ceres.docker.cfg` to your machine's LAN IP.

```yaml
# docker-compose.yml
environment:
  NC2_CLIENT_PATH: /path/to/Neocron2  # Mount your game files
```

## Protocol Documentation

Comprehensive reverse-engineered protocol documentation is available in [`docs/PROTOCOL.md`](docs/PROTOCOL.md), covering:

- TCP packet format and full connection flow
- All packet IDs with field-level documentation
- UDP obfuscation algorithm (reverse-engineered from `ObfuscateStreamBuf`)
- Auth packet structure for modern NCE 2.5.x client
- UDP handshake sequence
- Gamedata sub-packet hierarchy

## Project Structure

```
├── src/main/java/server/
│   ├── Server.java                 # Entry point
│   ├── database/                   # CSV-based persistence
│   │   ├── accounts/               # Account management
│   │   ├── playerCharacters/       # Character data + inventory
│   │   ├── items/                  # Item definitions (from NC2 client)
│   │   └── worlds/                 # World/zone definitions
│   ├── gameserver/                 # Core game server
│   │   ├── GameServer.java         # TCP + UDP listeners
│   │   ├── Player.java             # Player session state
│   │   ├── PlayerManager.java      # Active player tracking
│   │   ├── Zone.java               # Zone management
│   │   └── packets/                # Packet handlers
│   │       ├── client_tcp/         # Client → Server TCP packets
│   │       ├── server_tcp/         # Server → Client TCP packets
│   │       ├── client_udp/         # Client → Server UDP packets
│   │       └── server_udp/         # Server → Client UDP packets
│   ├── infoserver/                 # Server discovery (port 7000)
│   ├── patchserver/                # Version checking (port 8020)
│   ├── webserver/                  # HTTP status (port 8080)
│   ├── networktools/               # Protocol utilities
│   │   ├── PacketObfuscator.java   # UDP encryption/decryption
│   │   └── ProtocolConstants.java  # All packet IDs and constants
│   └── tools/                      # Config, logging, utilities
├── src/test/java/                  # Unit tests
├── docs/
│   ├── PROTOCOL.md                 # Full protocol documentation
│   └── obfuscate_decompiled.c      # Ghidra decompilation of ObfuscateStreamBuf
├── database/                       # CSV data files
├── ceres.cfg                       # Server configuration
├── ceres.docker.cfg                # Docker configuration
├── Dockerfile                      # Multi-stage build
└── docker-compose.yml              # Docker Compose setup
```

## Current Status

| Feature | Status |
|---------|--------|
| TCP Handshake | Working |
| Authentication (NCE 2.5.x) | Working |
| Character CRUD | Working |
| UDP Obfuscation | Working (reverse-engineered) |
| UDP Handshake | Working |
| Zone Loading | Not yet implemented |
| Movement / Combat | Not yet implemented |
| Chat (Local + Global) | Partially implemented |
| Inventory | Partially implemented |
| NPC Interaction | Not yet implemented |

The main blocker for entering the game world is the zone loading sequence — the server needs to send ~7KB of world initialization data after the UDP handshake (zone geometry references, NPC spawns, item placement, weather, etc.).

## History

- **Irata** — Original project by MrsNemo and r2d22k (GPLv2, [SourceForge](https://sourceforge.net/projects/irata/))
- **Ceres-J** — Fork with modern NCE 2.5.x protocol support, Docker containerization, UDP obfuscation reverse-engineering, and comprehensive protocol documentation

## Acknowledgments

- [Irata](https://sourceforge.net/projects/irata/) — Original Neocron 2 server emulator
- [TinNS](https://github.com/) — Neocron 1 server emulator (protocol research reference)
- [TechHaven Wiki](https://wiki.techhaven.org/) — Community documentation
- The Neocron community — for keeping the game alive

## License

GNU General Public License v2.0 — see [license.txt](license.txt)
