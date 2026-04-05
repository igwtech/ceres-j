# Neocron 2 Wireshark Dissector

A Lua dissector for the Neocron 2 game protocol as implemented by
Ceres-J and spoken by the retail NCE 2.5.x client.

## What it decodes

- **TCP port 12000** — the gameserver. Outer framing (`fe` + 16-bit LE
  length + payload), opcode dispatch, and field-level parsing for the
  high-traffic packets (Auth, AuthB, UDPServerData, Location, CharList).
- **UDP ports 5000–5999** — the per-session game port pool. Automatic
  direction detection based on whether source or destination is in the
  pool range.
- **C→S UDP** — applies the per-packet XOR cipher
  (`PacketObfuscator.java`) to recover plaintext. The seed is encoded
  in the first byte of the ciphertext; we brute-force it against the
  known set of plaintext headers `{0x01, 0x03, 0x04, 0x08, 0x13}`.
- **S→C UDP** — sent plaintext by both retail and Ceres-J, no
  decryption step needed.
- **0x13 gamedata sub-packet chain** — walks the
  `<len:u8> <data:len bytes>` chain inside gamedata frames and labels
  each sub-packet by its opcode. Unwraps the `0x03 <counter>` reliable
  wrapper when present.

## Install

### Linux
```
mkdir -p ~/.local/lib/wireshark/plugins
cp neocron2.lua ~/.local/lib/wireshark/plugins/
```

### macOS
```
mkdir -p ~/.config/wireshark/plugins
cp neocron2.lua ~/.config/wireshark/plugins/
```

### Windows
Copy `neocron2.lua` into `%APPDATA%\Wireshark\plugins\` (create the
directory if needed).

Then in Wireshark: **Analyze → Reload Lua Plugins** (Ctrl+Shift+L), or
restart Wireshark.

## Verify

Open any of the retail captures:
```
wireshark /tmp/retail_capture.pcapng
wireshark /tmp/retail_capture_multibox.pcapng
wireshark /tmp/retail_capture_samechartwice.pcapng
```

Every packet should show `NC2 TCP` or `NC2 UDP` in the Protocol column.
Expand a packet tree to see decoded fields.

Command-line opcode coverage check:
```
tshark -r /tmp/retail_capture.pcapng \
  -Y 'nc2_tcp or nc2_udp' \
  -T fields -e nc2_tcp.opname -e nc2_udp.opname | sort -u
```

## Useful filters

- `nc2_udp.opcode == 0x13`  — gamedata packets only
- `nc2_tcp.opcode == 0x830c` — Location (zone name) packets
- `nc2_tcp.opcode == 0x8305` — UDPServerData (per-session port handoff)
- `nc2_udp.dir == "C->S"` — client-to-server
- `tcp.port == 12000 and nc2_tcp.opname contains "Auth"` — login flow
- `udp.port == 5007` — isolate a single retail session by its assigned
  server port (multi-box captures have one port per login)

## Scope / known gaps

**v1 covers**:

- TCP frame + opcode dispatch (all known opcodes from
  `GamePacketReaderTCP.java`)
- UDP top-level + 0x13 sub-packet chain
- Field-level decoding for Auth, AuthB, UDPServerData, Location,
  CharList, UDPAlive
- Opcode names for every sub-packet in
  `GamePacketReaderUDP.decodesub13()`

**Left for v2**:

- CharInfo multi-chunk reassembly across datagrams
  (0x13 → 0x03 → 0x07 fragmented). v1 shows each chunk individually
  with its sub-packet metadata.
- Detailed field parsing for UpdateModel, Movement, PositionUpdate,
  LongPlayerInfo, ShortPlayerInfo, PlayerPositionUpdate, CharInfo
  sections, WorldWeather, TimeSync, GlobalChat, WorldNPCInfo. These
  currently render as `opcode + payload hex`.
- Reliable-delivery counter semantics (ack / resend). v1 just prints
  the raw counter values.

**Not planned**:

- Retail S→C stream cipher decoding. This does not exist — the
  `ObfuscateStreamBuf` class in `neocronclient.exe` uses a different
  seed and different data; applying its formula to 77 retail S→C
  packets produced 0 matches under every reset model, plus 0 matches
  cross-validated against known-plaintext C→S packets. Retail sends
  S→C as plaintext.
- Editable fields / packet injection. This is a read-only dissector.

## Reference

The dissector mirrors the canonical Ceres-J sources:

- `src/main/java/server/networktools/PacketBuilderTCP.java` — TCP frame
- `src/main/java/server/networktools/PacketObfuscator.java` — C→S cipher
- `src/main/java/server/networktools/PacketBuilderUDP13.java` — UDP gamedata frame
- `src/main/java/server/gameserver/packets/GamePacketReaderTCP.java` — TCP dispatch
- `src/main/java/server/gameserver/packets/GamePacketReaderUDP.java` — UDP dispatch
- `docs/PROTOCOL.md` — human-readable protocol reference

When a packet's layout changes in the Java code, update the matching
dissector function in `neocron2.lua`.
