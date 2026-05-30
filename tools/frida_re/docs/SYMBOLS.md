# Canonical Ghidra symbol table

All addresses are **file-VA** as displayed in Ghidra, assuming the
default PE ImageBase `0x00400000`. The runtime offset added to the
live module base is ``file_va - 0x00400000``.

This file is **generated from** `orchestrator/symbols.py` — if you
edit one you must edit the other. The Python module is the source of
truth at install time (the orchestrator pushes the offsets to the
agent at attach), this table is for human reference.

| Name | Ghidra file-VA | Runtime offset | Status | Purpose |
|---|---|---|---|---|
| `udp_cipher_a` | `0x00560090` | `0x00160090` | ON | UDP cipher (LFSR+CFB) — direction A |
| `udp_cipher_b` | `0x0055ff30` | `0x0015ff30` | ON | UDP cipher (LFSR+CFB) — direction B |
| `session_dispatch` | `0x0055ec10` | `0x0015ec10` | STUB | reliable-channel session dispatcher (body byte0 = Type) |
| `multipart_reassemble` | `0x0055c270` | `0x0015c270` | STUB | 0x03/0x07 multipart fragment reassembler |
| `charsys_tlv_parse` | `0x008447d0` | `0x004447d0` | STUB | CHARSYS TLV parser (CharInfo body) |
| `fullcharsys_dispatch` | `0x00803cd0` | `0x00403cd0` | STUB | FULLCHARSYSTEM event dispatcher (runtime CHARSYS) |
| `hud_hp_tick` | `0x007e87d0` | `0x003e87d0` | STUB | HUD HP tick / pool clamp |
| `hud_psi_tick` | `0x007e8930` | `0x003e8930` | STUB | HUD PSI tick / pool clamp |
| `hud_sta_tick` | `0x007e8a20` | `0x003e8a20` | STUB | HUD STA tick / pool clamp |

## Provenance

The full provenance for each address lives in
`ceres-j/docs/PROTOCOL.md` and `ceres-j/docs/protocol/RE_state_sync.md`.
Brief origin notes:

* `udp_cipher_a` / `udp_cipher_b` — pinned 2026-04-26 during the cash
  carrier hunt. The LFSR+CFB derivation is implemented host-side as
  `server.networktools.WireEncrypt`; both directions confirmed via
  pcap byte-identity tests.
* `session_dispatch` — from `RE_state_sync.md`; the reliable-channel
  dispatcher that switches on body byte0 (the "Type" table, *not*
  the outer opcode).
* `multipart_reassemble` — from `multipart_framing.md`; assembles
  0x03/0x07 fragments using the 6-byte per-fragment header.
* `charsys_tlv_parse` — pinned during the CharInfo Section-4 subskill
  table mapping (`charinfo_s4_subskill_table.md`).
* `fullcharsys_dispatch` — runtime CHARSYS dispatcher (`case 0xb3` is
  dead code but the dispatcher itself is live for events like 0x6e).
* `hud_*_tick` — found during the `current_pool_damage_heal_path`
  investigation that pinned the HUD widget as summing CHARSYS sec2
  buckets `+0x3f4/+0x3f8/+0x3fc`.

## ASLR

NC2's PE flag `IMAGE_DLLCHARACTERISTICS_DYNAMIC_BASE` is **not** set,
so the runtime base is normally `0x00400000` in any sane loader.
Wine's loader honours this. The agent still resolves the base
dynamically via `Process.findModuleByName('NeocronClient.exe')` so
that if the EXE is ever rebuilt with ASLR (or run under a hardened
loader) the offsets still work.
