# Client-side Lua → Wire packet bridge

The NC2 client embeds a Lua scripting engine that runs **dialog
trees, mission flow, and scenario scripts**. These scripts call
into a fixed C-side API (the `SendScriptMsg` bridge) which
encodes calls into wire packets.

This document is the **bridge between the script-level mission
flow and the wire-level packet protocol**.

## Source files

The client's Lua scripts live in `scripts.pak` (1.79 MB). Extract via:

```bash
mkdir -p /tmp/scripts_pak && cd /tmp/scripts_pak
php tools/pak_decompress.php /home/javier/Neocron2/scripts.pak
```

The extracted tree contains 808 Lua files under
`tools/tmp/home/javier/Neocron2/scripts/lua/`. Subdirectories:

- `area_mc5/` — AreaMC5 (Military Command 5) NPC scripts —
  the scripts for the LORCAN/EZRA/TASHA/GEORDI mission NPCs
  in our `RETAIL_CREATION_LEVELING_LONG` capture
- `epicruns/` — epic-run mission scripts (faction storylines)
- `m_*.lua` — generic mission templates (delivery, kill, etc.)
- `dialogheader.lua` — **the C bridge layer**: defines every
  scriptable function and its `SendScriptMsg` translation
- `missionheader.lua` — mission-specific helpers
- 700+ NPC scripts (each NPC has its own Lua dialog tree)

## The `SendScriptMsg` C bridge

`dialogheader.lua` defines every scriptable Lua function as a
wrapper around `SendScriptMsg`:

```lua
function ACCEPTMISSION()
    if (node==state) then
        SendScriptMsg("acceptmission", dialogclass)
    end
end
```

**`SendScriptMsg` is a C function exposed by the client engine**.
It takes a string command name + arguments and emits a wire
packet. The full enumeration of script commands:

### Complete dialog/mission RPC catalog (55 commands)

Extracted from `tools/tmp/home/javier/Neocron2/scripts/lua/dialogheader.lua`:

#### Dialog flow

| Command | Lua API | Direction | Purpose |
|---|---|---|---|
| `say` | `SAY(text)` | C→S→render | NPC says text |
| `sayrsc` | `SAYRSC(rscid, section)` | C→S→render | Says text from resource string table |
| `setanswer` | `ANSWER(text, target_state)` | C→S→render | Adds player response option |
| `setnextdialogstate` | `SETNEXTDIALOGSTATE(state)` | C→S | Move to dialog node |
| `enddialog` | `ENDDIALOG()` | C→S | Close dialog |

#### Mission state

| Command | Lua API | Purpose |
|---|---|---|
| `acceptmission` | `ACCEPTMISSION()` | Player accepts |
| `startmission` | `STARTMISSION(missionid)` | Begin a mission |
| `getmissionstatus` | `GETMISSIONSTATUS(missionid)` | Query state |
| `setmissionstatus` | `SETMISSIONSTATUS(missionid, status)` | Update state |
| `ismissiontargetaccomplished` | `ISMISSIONTARGETACCOMPLISHED(target)` | Check |
| `candoepicrun` | `CANDOEPICRUN(faction, mission)` | Eligibility |
| `epicrunaccomplished` | `EPICRUNFINISHED(faction, mission)` | Mark done |

#### Item & money

| Command | Lua API | Purpose |
|---|---|---|
| `giveitem` | `GIVEITEM(type)` | Give item to player |
| `giveitemwithslots` | `GIVEITEMWITHSLOTS(...)` | With ammo slots etc. |
| `givequestitem` | `GIVEQUESTITEM(type)` | Mission item |
| `givespecialitem` | `GIVESPECIALITEM(...)` | Custom-tagged |
| `givetaggeditem` | `GIVETAGGEDITEM(...)` | Inscribed/named |
| `takeitem` | `TAKEITEM(type)` | Remove from player |
| `takeitemcnt` | `TAKEITEMCNT(type, cnt)` | Remove N copies |
| `takequestitem` | `TAKEQUESTITEM(type)` | Mission item |
| `takespecialitemcnt` | `TAKESPECIALITEMCNT(...)` | Special-tagged |
| `givemoney` | `GIVEMONEY(amount)` | Cash reward |
| `takemoney` | `TAKEMONEY(amount)` | Cash deduction |
| `trade` | `TRADE()` | Open trade window |

#### Player query

| Command | Lua API | Purpose |
|---|---|---|
| `getclass` | `GETCLASS()` | Class enum |
| `getprofession` | `GETPROFESSION()` | Profession |
| `getgender` | `GENDERCHECK()` | Gender flag |
| `getbaseskill` | `GETBASESKILL(skill)` | Skill value |
| `getskill` | `GETSKILL(skill)` | Effective skill |
| `getbasesubskill` | `GETBASESUBSKILL(sub)` | Subskill value |
| `getsubskill` | `GETSUBSKILL(sub)` | Effective subskill |
| `getfactionsympathy` | `GETFACTIONSYMPATHY(fid)` | Sympathy with faction |
| `changefaction` | `CHANGEFACTION(fid)` | Change faction |
| `getdoyalignment` | `DOYALIGNMENT()` | DoY (Day-of-Yenev) alignment |

#### World & NPC

| Command | Lua API | Purpose |
|---|---|---|
| `spawnnpc` | `SPAWNNPC(type, x, y, z)` | Spawn an NPC |
| `spawnnpcex` | `SPAWNNPCEX(...)` | Spawn with script + loot |
| `setnpcscript` | `SETNPCSCRIPT(name, params)` | Switch NPC script |
| `npcaction` | `ATTACK()`, `DIE()`, `FOLLOW()`, … | NPC action enum |
| `getdistance` | `GETDISTANCE(x, y, z)` | Distance to point |
| `getchildcnt` | `GETCHILDCNT()` | NPC's child count |

#### Triggers & state

| Command | Purpose |
|---|---|
| `activatedialogtrigger` | Set a dialog trigger flag |
| `adddialogtriggercnt` | Increment trigger counter |
| `gettrigger` / `settrigger` | Read/write trigger value |
| `gettimer` / `resettimer` | Mission timer |
| `rand` | Random number request |

#### Communication

| Command | Purpose |
|---|---|
| `sendcustommsg` | Custom UI message |
| `sendlevelmsg` | Level-up notification |
| `sendemail` | Send mail to player |
| `showtuttext` | Show tutorial text |

#### WoC (World of Combat?)

| Command | Purpose |
|---|---|
| `getwoclevel` | WoC level |
| `getwocskill` | WoC skill |
| `addwoclevel` | Add WoC level points |

## How `SendScriptMsg` translates to the wire

**Critical finding (verified 2026-05-03):** the dialog/mission
RPC commands do **NOT** travel as ASCII strings on the wire.
The client encodes them as **numeric tags inside `0x03/0x1f`
GamePackets**. We searched the captures for ASCII strings like
`"acceptmission"`, `"startmission"`, `"say"` — **zero hits**.

What WAS found as ASCII on the wire:

| ASCII string | Where it appears | Channel |
|---|---|---|
| `mc5_lorcan`, `mc5_ezra` | `0x03/0x1f` tag `0x2a` body | mission grant |
| `DCBSetup`, `Welcome`, `Emaillist`, `Archive`, `DCBGetLastEmail` | `0x03/0x2b` body | CityCom DCB RPC |
| `scripts\lua\scenario\redlight\h_tchenspiel.lua` | `0x03/0x00` body | cross-district script-path upload |

So there are **two distinct RPC mechanisms** in NC2:

1. **CityCom DCB (`0x03/0x2b`)** — uses ASCII method names in
   the body. Used for kiosk-style UIs (mail terminal, public
   CityCom, Hometerm).
2. **Dialog/Mission script bridge (`0x03/0x1f` tag-encoded)** —
   uses numeric tag bytes. Used for NPC dialog trees, mission
   state, and Lua scenario scripts.

**The string→numeric mapping is internal to the client.**
`SendScriptMsg` consults a hash table or jump table to find
the numeric tag for each command name. To get the full mapping,
we need to:

1. Find `SendScriptMsg` in the Ghidra-decompiled `neocronclient.exe`
2. Walk the dispatch table that maps command-name hash → tag/sub-tag
3. Cross-reference against the 55 Lua command names

## Verified mappings (from sub-tag analysis)

So far we have these confirmed Lua-command → wire-tag mappings:

| Lua command | Wire packet | Verified by |
|---|---|---|
| `say` (NPC text) | `UDP S→C 0x32` raw outer (NPC dialogue text) | NPC_TALK markers |
| `setanswer` (player option) | `UDP C→S 0x32` raw outer | NPC_TALK_CLICKDIALOG markers |
| `acceptmission` / dialog navigation | `UDP * 0x03/0x1f` tag `0x1a` | NPC_TALK_CLICKDIALOG_END markers (dialog flow) |
| `startmission` / mission grant | `UDP S→C 0x03/0x1f` tag `0x2a` | mc5_<npc> ASCII strings in body |
| `giveitem` / `givequestitem` | `UDP * 0x00` 12B inventory channel | inventory_equip.md |
| `givemoney` / `takemoney` | `UDP S→C 0x03/0x1f` tag `0x25` sub `0x04` (cash carrier) | vendor_buy.md, level_up.md |
| `trade` | `UDP C→S 0x2d ?` 6B (trade-request) | p2p_trade.md |
| `showtuttext` | TBD — likely a unique tag | tutorial UI markers |
| `sendemail` | `UDP * 0x03/0x2b` CityCom DCB | apartment_mail.md |
| `spawnnpc` / `npcaction` | `UDP S→C 0x03/0x2d NPCData` (54B) | combat captures |
| `setnextdialogstate` | `UDP S→C 0x03/0x1f` tag `0x1a` (next-node) | dialog navigation |

## Mission ID conventions

From the area_mc5 NPC scripts + the wire-decoded mission IDs:

| Convention | Example | Component |
|---|---|---|
| `<area>_<npc>` | `mc5_lorcan` | Static-NPC mission |
| `m_s_<type>` | `m_s_packet`, `m_s_killenemy` | Generic mission template |
| `m_e_<type>` | `m_e_spy` | Enemy / "Element"-class mission |
| `<faction>_<chapter>` | `tangent01`, `crahn01`, `biotech04` | Epic run (faction storyline) |

Mission names are 11-22 chars, ASCII-encoded in `0x03/0x1f` tag
`0x2a`. They serve as **stable IDs**: the client looks up the
mission script in `scripts.pak`, the server tracks state by ID.

## How to use this for Ceres-J server-side mission implementation

Since the dialog/mission protocol is binary-encoded (not ASCII),
the Ceres-J server must:

1. **Implement the tag dispatch**: when receiving `0x03/0x1f`,
   decode the tag byte and route to a handler.
2. **Maintain a mission script registry**: load the same Lua
   scripts the client uses, OR re-implement their state-machine
   logic on the server in Java.
3. **Encode mission IDs**: send the 16-byte ASCII string in the
   `0x2a` body when granting missions.
4. **Mirror the dialog tree**: when the client sends tag `0x1a`
   with option_idx, the server consults the NPC script's NODE
   table and returns the matching response state.

The script-engine APPROACH (server runs Lua) would be the
fastest path to feature parity, but requires a Java Lua runtime
(LuaJ?) AND the same `SendScriptMsg` shim that maps strings to
tags. The HARD-CODED approach (translate each NPC's dialog tree
into Java) is more idiomatic but means re-implementing 700+
NPC scripts.

## Open questions

- **Find `SendScriptMsg` in Ghidra** — gives us the exact
  string→tag mapping for all 55 commands.
- **`gettargetvalue`** — appears in some scripts; not yet
  mapped to a wire packet.
- **Dialog state synchronization** — when the player walks away
  mid-dialog, what packet ends it? Possibly the same `0x32`
  raw-outer with a "close" sub-byte, or implicit timeout.
- **`sendemail` flow** — the C→S email-send must be in
  `0x03/0x2b` since it's a Hometerm action. Not yet captured.
- **`SETLEVEL`** appears in the API list but not as
  `SendScriptMsg("setlevel", ...)` — may use a different
  bridge function.

## Related docs

- [`OPCODE_STRUCTURE.md`](OPCODE_STRUCTURE.md) — opcode-space conventions
- [`SUBTAGS.md`](SUBTAGS.md) — `0x03/0x1f` tag distribution
- [`flows/missions.md`](flows/missions.md) — wire-level mission flow
- [`flows/interactions.md`](flows/interactions.md) — NPC dialog protocol
- [`flows/apartment_mail.md`](flows/apartment_mail.md) — CityCom DCB (the *other* RPC channel)
