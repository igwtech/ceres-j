# NC2 Packet Function Reference — master index

_Single entry point for "what does this packet type/sub-type DO?". Each
layer's full table is in a linked section; this page is the map + the
cross-cutting summary + the honest list of what's still unknown._

Built by consolidating `OPCODE_STRUCTURE.md`, `SUBTAGS.md`, the deep-dive
RE docs, the 102 per-packet stubs in `packets/`, and the project memory.
For HOW the opcode space is organized (the design rules), read
[`OPCODE_STRUCTURE.md`](OPCODE_STRUCTURE.md) first.

## The frame hierarchy (where each layer lives)

```
Wire bytes (UDP: LFSR+CFB encrypted | TCP: plaintext, `fe [len LE16]` framed)
│
├─ TCP: [hi op][lo op]  (2B BE; hi=subsystem, lo=operation)
│        → see §C below / _funcref_tcp.md
│
└─ UDP: decrypted plaintext[0] = TRANSPORT opcode
         ├─ 0x01/02/03/04/08/0b/f2 … transport control (connect/alive/ping)
         └─ 0x13 = GAMEDATA app layer
              [0x13][ctr LE2][ctr+sessionkey LE2] ( [subLen LE2][wrapper][…] )+
              wrapper ∈ { 0x01 retransmit-req, 0x02 simplified-reliable,
                          0x03 reliable, 0x07 multipart, 0x08 primer/ack,
                          0x09 server-ack, raw 0xN direct, 0x02-rewrap }
                └─ 0x03 reliable: [0x03][seq LE2][OP][body]
                     OP ∈ { 0x2c CharInfo, 0x1f GamePackets, 0x1b Movement,
                            0x25 state-txn, 0x28 WorldInfo, 0x2d NpcData, … }
                       └─ 0x1f GamePackets: `01 00 [act_tag] …`
                            act_tag ∈ { 0x3d frame-poll, 0x01 weapon-fire,
                                        0x17 use-obj, 0x25 state-txn, … }
                              └─ 0x25 state-txn: `… 25 [sub_tag] …`
```

Max depth: `outer → sub-wrapper → op → act_tag → sub_tag → payload` (6 layers).

## Sections (the full per-type/sub-type FUNCTION tables)

| Section | Covers | File |
|---|---|---|
| **A. UDP transport + 0x13 sub-wrappers + reliable OP space** | transport 0x01–0xf2, the 6 sub-wrappers, ~26 reliable ops | [`_funcref_udp.md`](_funcref_udp.md) |
| **B. 0x03/0x1f act_tags + 0x25 sub-tags** | the general-purpose event channel (26 act_tags + 14 sub-tags decoded) | [`_funcref_subtags.md`](_funcref_subtags.md) |
| **C. TCP opcodes** | 0x80/0x83/0x84/0x87/0xa0 subsystems (19 opcodes) | [`_funcref_tcp.md`](_funcref_tcp.md) |

## Cross-cutting rules worth remembering

- **Channel duality**: a raw `0xN` direct datagram ≡ the reliable `0x03/0xN`
  form; the server picks per-opcode whether to send reliable or best-effort.
- **0x02 ≠ 0x03**: the `0x02` "simplified reliable" wrapper does NOT advance the
  client's reliable receive window (it's the retransmit/response channel); only
  `0x03` advances it. Login STATE must go on `0x03`. (See
  [`RE_reliable_window.md`](RE_reliable_window.md).)
- **Reliable seqs start at 1** — the client drops seq 0 (sentinel).
- **TCP direction from the high byte**: `0x83`≈S→C, `0x84`=C→S, `0x80`/`0xa0`
  mixed handshakes; request `0x84xx` ↔ reply `0x83xx`. Exception: `0x8301`
  ResumeAuth is C→S.
- **Parser trap**: the inner OP for a 0x03 sub is at body[3] (body[1..2] = inner
  seq), not body[1]; "Corrupted Message Type:15" is an N-byte misframe, not a
  real short packet.

## Still genuinely unknown (do not guess — capture/RE needed)

- UDP: 0xf2 challenge purpose; transport-0x02 wire bytes; single-sub outers
  0x0f/0x11/0x1d; reliable ops 0x29/0x2a/0x34+; multipart discriminators
  0x03/0x04/0x38; ~9 sparse upper outers.
- 0x1f act_tags: 0x16, 0x31, 0x32, 0x35, 0x36, 0x38, 0x39, 0x4d, 0x4e (all 1–2
  obs); the S→C 0x22 opaque variant. 0x25 sub-tags: 0x0b counter semantics,
  0x32 trigger, the 0x25/0x13 inner markers 0x0b/0x0e/0x02/0x03/0x05.
- TCP: 0x8305 flags, 0x8381 trailer, 0x8385 session_byte, 0x8317/0x8383 field
  units, the 0x83/0x88 16B variant; the op-byte overloads (0x8317/0x8318/0x8388
  appear as both captured packets and differently-described decompiler cases).

## Deep-dives (byte-level)

- [`RE_reliable_window.md`](RE_reliable_window.md) — client reliable commit rules.
- [`RE_state_sync.md`](RE_state_sync.md) — S→C state-sync / runtime CHARSYS.
- [`RE_tcp_confirm.md`](RE_tcp_confirm.md) — per-action confirmation transport.
- [`CLIENT_LUA_BRIDGE.md`](CLIENT_LUA_BRIDGE.md) — Lua→wire RPC for 0x1f commands.
- [`SUBTAGS.md`](SUBTAGS.md) — the auto-generated 0x1f act_tag corpus stats.
