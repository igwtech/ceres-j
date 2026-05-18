# Documented Neocron commands — client-local vs wired

Task #187. Source of truth for the documented retail command set is
**wiki.techhaven.org/Set_Commands**. The retail client intercepts all
`/`-prefixed input locally (like the WoW client) — typing `/set …` or
`/emote …` never sends that string to the server. Each command is
parsed client-side; only some produce a C→S packet.

This is **not** the Ceres `.`/`!` admin extension (tasks
#179/#182/#186) — that registry is a Ceres-only convenience and is
kept untouched. This document covers the *documented retail* commands.

Ground truth used:

- Static decompile of `neocronclient.exe`:
  - `FUN_0065d710` — the `/set <keyword>` config dispatcher.
  - `FUN_006576f0` — the app-action packet builder
    (`[0x15][localId&0x3ff LE16][0x3d][payload]`, wrapped onto
    `0x03/0x1f/<localId>/0x3d`).
  - `FUN_005c8cb0` — the chat-input dispatcher (handles `/emote`,
    `/e`, `/me`, `/sms`, `/exec`, `/config`, …).
  - `FUN_006f9680` — the chat-send helper used by `/emote`/`/e`/`/me`.
- Retail pcap `strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap`
  (live server 157.90.195.74). The captured player **only sat and
  talked to an NPC** — it never used kill_self / reset_position /
  emote. The only `0x1f/0x3d` app-action sub-bytes present are
  `0x11` (heartbeat ×2684) and `0x32` (status snapshot ×12).

## Server-affecting documented commands

| Command | Verdict | Wire form | Status |
|---|---|---|---|
| `/set kill_self 1` | **Wired (binary-pinned, capture-gated)** | `0x03/0x1f/<localId>/0x3d` app-action, sub-byte **`0x10`**, body `00 00 00` → inner `1f <id LE16> 3d 10 00 00 00` | **Implemented** — `KillSelfRequest` → `Player.die()` |
| `/set reset_position 1` | **Client-local only — cannot be server-implemented** | none | Documented; no handler (proven below) |
| `/emote <keyword>` | **Client-resolved; wire form NOT provable from this pcap** | client resolves keyword→anim-id locally, sends via `FUN_006f9680` (`send(0x78, 8B = [anim_id LE32][duration float])`); outer sub-tag unobserved here | Capture-gated — NOT implemented (no proof) |

### `/set kill_self 1` — instant suicide

`FUN_0065d710` matches the `kill_self` keyword and branches on the
local player-object flag at `+0x29`:

- **`+0x29 == 0`** (common in-world case): the client calls its own
  player-object death vtable slot `(+0x84)(0)` — a *local* death
  request; death then resolves through the normal damage/pool path
  the server already drives.
- **`+0x29 != 0`** (vehicle / special state): the client emits
  `FUN_006576f0(payload = 10 00 00 00, len = 4)`. That helper frames
  `[0x15][localId & 0x3ff LE16][0x3d][payload]`; the Winsock wrapper
  renumbers it onto the standard `0x03/0x1f/<localId>/0x3d`
  app-action channel — the same tag/channel the ~90 Hz in-flight
  heartbeat (sub-byte `0x11`) rides on.

Inner C→S (binary-pinned): `1f <localId LE16> 3d 10 00 00 00`.

**Capture provenance:** binary-derived, **not** in this pcap (no
suicide was performed in the capture). Implemented and decoded, but
the `0x10` byte should be confirmed against a future pcap of an
actual suicide.

Server reaction implemented: `KillSelfRequest.execute()` →
`Player.die()` → lethal self-damage → HP 0 → death/respawn flow
(`PlayerDeath` + `RespawnEvent`), matching the documented
"instant suicide".

Decoder route: `GamePacketReaderUDP.decodesub13` →
`case 0x1f → 0x3d → switch(subByte){ 0x10 → KillSelfRequest;
default → null }`. The `default → null` keeps the dominant `0x11`
heartbeat (and `0x32`) recognised-as-null exactly as before — **no
regression** to the heartbeat path.

### `/set reset_position 1` — CLIENT-LOCAL (proven)

`FUN_0065d710` `reset_position` block, byte-for-byte:

```c
*(undefined4 *)(in_ECX + 0x150) = 0x42f00000;  // float 120.0  (the documented 120 s timer)
*(undefined1 *)(in_ECX + 0x154) = 2;            // local movement-lock state
```

No packet builder is called. The client itself counts down 120 s,
disables movement (except jump/crouch/item-use), and resets the
player to a predefined position in the same zone. **There is no C→S
packet and therefore nothing the server can implement.** The server
will already see the resulting position via the normal movement
stream once the local reset fires.

### `/emote <keyword>` — client-resolved; wire form unproven here

`FUN_005c8cb0` routes `/emote ` (and the `/e ` alias) by skipping the
prefix (`param_1 + 7` / `+ 3`) and passing the remaining text to the
chat-send helper `FUN_006f9680(keyword, …)` with chat-channel
selector `local_1bc = 1` (the same selector `/me` uses).

`FUN_006f9680` does **not** send the keyword string. It extracts the
first alpha word, lowercases it, looks it up in a client emote table
(`FUN_0074ea90`), and on a hit sends the resolved numeric anim id:
`send(0x78, 8, &buf)` where `buf = [anim_id LE32][duration float]`.

The captured pcap contains **no emote**, and no prior catalog entry
pins the outer `0x1f/<sub>` for player emote animations. Per the
project's no-speculation rule the wire form is **capture-gated**:
documented here, but NOT implemented, until a retail pcap of an
actual `/emote` is taken (then byte-pin the outer sub-tag and add an
`EmoteRequest` decoder + a zone re-broadcast of the anim id, same
pattern as `KillSelfRequest` / `ExitSeatRequest`).

## Client-local-only documented commands (cannot be server-implemented)

All other `/set …` keywords resolve through `FUN_0065d710` to
`thunk_FUN_004a4430("<key>")` (read a local config value) and write a
field on the local config/camera/HUD object. They never produce a
packet. Per the techhaven set-command list these are:

- Controls: `ctrl_hudallowmove`, `ctrl_mousefac`, `ctrl_mouseinvert`,
  `ctrl_mouselock`, `ctrl_bob`
- Graphics: `gfx_hudcolor`, `ctrl_renderhud`
- Sound: `ctrl_sfxvolume`, `ctrl_musicvolume`, `ctrl_musicdefaultset`
- Camera: `ctrl_externalcam`, `ctrl_externaldist`,
  `ctrl_externalangle`
- Misc: `sys_messages`, `ctrl_globalchat`
- plus the many other `ctrl_*` / `gfx_*` / `sys_*` keys in
  `FUN_0065d710` (e.g. `ctrl_maxfps`, `ctrl_maxrframe`,
  `ctrl_maxpframe`, `ctrl_showclanarrows`, `sys_logvalue`, …)

(`gm_*` keywords in the same table — `gm_noclip`, `gm_sethealth`, … —
DO hit the wire on the separate `0x03/0x1f/<localId>/0x06` GM channel,
already handled by `AdminCommandRequest`, task #179. They are GM
debug commands, not part of the player-facing documented set, and are
out of scope for this task.)
