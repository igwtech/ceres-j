# Neocron Evolution 2.5 Wireshark Dissector

A Lua dissector for the Neocron Evolution 2.5 (NCE 2.5.x) game protocol
as spoken by the retail client and the Ceres-J server emulator.

## What it decodes

- **TCP port 12000** (gameserver) — `FE`-framed PDUs (`0xFE`, 16-bit LE
  length, payload). Field-level parsing for `Auth` (0x8480),
  `AuthB` (0x8301), `AuthAck` (0x8381), `UDPServerData` (0x8305),
  `Location` (0x830c), `CharList` (0x8385), `ClientKicked` (0x8303),
  `CustomChat` (0x8317). Opcode table covers the full set documented in
  `docs/PROTOCOL.md`.
- **UDP ports 5000–5999** (per-session game traffic) — automatic
  bidirectional **LFSR-CFB decryption** (per-packet random 16-bit seed,
  4-byte cipher header, cipher-feedback LFSR PRNG). Both C→S and S→C
  are encrypted in NCE 2.5; the dissector decrypts both. If the
  recovered plaintext byte 0 is not a known opcode, the dissector
  falls back to treating the packet as plaintext (legacy/pre-cipher
  captures still render).
- **0x13 gamedata** — `counter`, `counter+sessionKey`, then a chain of
  sub-packets prefixed with **2-byte LE lengths** (retail format).
  The chain is walked until a zero length or buffer end is hit.
- **Sub-packet types**:
    - `0x02` wrapper (counter + inner sub-type)
    - `0x03` reliable wrapper (sequence + sub-type), with field-level
      parsing for `0x07` Multipart, `0x08` ZoningEnd, `0x28` WorldInfo,
      `0x2e` Weather
    - `0x0b` CPing, `0x0c` TimeSync request
    - `0x1f` game-ops with sub-op table (Shoot, Death, LocalChat,
      PoolStatusBroadcast at `0x30`, PoolUpdate at `0x50`, …)
    - `0x20` Movement (raw payload)
    - `0x25` info-ops (StartProcessor, UpdateMoney, MainSkills, …)
    - `0x2a` RequestPositionUpdate
- **Multipart (0x03 → 0x07)** — per-fragment header
  `[chain_key][0x00][discriminator][total_size LE32]` is broken out
  so you can spot login (`chain_key=0x00`) vs runtime chains and
  CharInfo (`disc=0x01`) vs CharsysInfo (`disc=0x02`) at a glance.
- **WorldInfo (0x28)** — world object ID, NPC type ID, X/Y/Z position,
  combat class, script name, model name (matches the retail-confirmed
  layout in `docs/PROTOCOL.md`).
- **PoolUpdate / PoolStatusBroadcast** — signed delta + pool type +
  max for `0x1f → 0x50`, and HP/PSI/STA/maxHP snapshot for `0x1f → 0x30`.

## Cipher (LFSR-CFB)

```
Wire datagram = [seed_lo][seed_hi][enc_len_lo][enc_len_hi][enc_data...]
```

The seed (16-bit LE) is sent in the clear. The 16-bit LFSR PRNG
(`FUN_004e36e0` in `neocronclient.exe`) generates one key byte per
plaintext byte; cipher-feedback uses the previous ciphertext byte as
the LFSR's data input. Total wire length = `plaintext_length + 4`.

Tap polynomial: bits 13, 12, 10 of the state, XOR'd with the full low
byte and one bit of the input data, MSB-first output. Implemented in
`server.networktools.WireEncrypt` (Java) and re-implemented in
`lfsr_byte` / `lfsr_decrypt` in this dissector.

Documented in `docs/PROTOCOL.md` § "UDP Wire Encryption (NCE 2.5.x)".

## Install

### Linux
```sh
mkdir -p ~/.local/lib/wireshark/plugins
cp neocron2.lua ~/.local/lib/wireshark/plugins/
```

### macOS
```sh
mkdir -p ~/.config/wireshark/plugins
cp neocron2.lua ~/.config/wireshark/plugins/
```

### Windows
Copy `neocron2.lua` into `%APPDATA%\Wireshark\plugins\` (create the
directory if needed).

Then in Wireshark: **Analyze → Reload Lua Plugins** (Ctrl+Shift+L), or
restart Wireshark.

## Verify

Open any retail or Ceres-J capture:
```sh
wireshark /tmp/retail_capture.pcapng
```

Every NC2 packet should show `NC2 TCP` or `NC2 UDP` in the Protocol
column. Opening the protocol tree on a UDP packet should show
`Encrypted: True`, the recovered seed, the encrypted length, and the
decoded plaintext as a sub-tvb.

CLI opcode coverage check:
```sh
tshark -r /tmp/retail_capture.pcapng \
  -Y 'nc2_tcp or nc2_udp' \
  -T fields -e nc2_tcp.opname -e nc2_udp.opname | sort -u
```

## Useful filters

| Filter | Purpose |
|--------|---------|
| `nc2_udp.opcode == 0x13` | gamedata datagrams only |
| `nc2_udp.gd.sub_op == 0x03` | reliable sub-packets |
| `nc2_udp.rel.subtype == 0x07` | multipart fragments |
| `nc2_udp.mp.disc == 0x01` | CharInfo multipart chains |
| `nc2_udp.mp.disc == 0x02` | CharsysInfo multipart chains |
| `nc2_udp.rel.subtype == 0x28` | NPC WorldInfo broadcasts |
| `nc2_udp.wi.script == "S_CITYMERCS_0"` | a specific NPC type |
| `nc2_udp.g1f.op == 0x50` | PoolUpdate (combat damage / heal) |
| `nc2_tcp.opcode == 0x830c` | Location (zone name) |
| `nc2_tcp.opcode == 0x8305` | UDPServerData (per-session port) |
| `nc2_udp.dir == "C->S"` | client-to-server only |
| `udp.port == 5007` | isolate one session by its assigned port |

## Out of scope

- **Cross-datagram multipart reassembly.** Each fragment is annotated
  with its `chain_key`, `discriminator`, and `total_size` header so you
  can correlate fragments manually. A reassembly engine that buffers
  fragments by `(addr, port, chain_key)` and emits a virtual packet on
  completion is a future v3 feature.
- **Editable / injectable fields.** Read-only.
- **Detailed CharInfo section walk.** The 13 TLV sections inside the
  reassembled CharInfo buffer (pools, skills, subskills, inventory,
  cash, factions) are documented in `docs/PROTOCOL.md` § "CharInfo"
  and `docs/CHARINFO_ANALYSIS.md` but not yet broken out by the
  dissector.

## Reference

The dissector tracks these source-of-truth files:

- `docs/PROTOCOL.md` — full protocol reference
- `docs/PACKET_REFERENCE.md` — per-packet byte tables
- `src/main/java/server/networktools/WireEncrypt.java` — LFSR-CFB cipher
- `src/main/java/server/networktools/PacketBuilderUDP13.java` — outer 0x13 frame
- `src/main/java/server/networktools/PacketBuilderUDP130307.java` — multipart fragments
- `src/main/java/server/gameserver/packets/GamePacketReaderUDP.java` — UDP dispatch
- `src/main/java/server/gameserver/packets/GamePacketReaderTCP.java` — TCP dispatch

When a packet's layout changes in the Java code, update the matching
section of `neocron2.lua`.
