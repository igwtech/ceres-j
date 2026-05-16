# Neocron 2 Evolution client — debug / verbose logging

How to make `neocronclient.exe` emit its own maximum-verbosity diagnostics
(zone-cross, position, sync, "Joining session") into `error_*.log`, derived
by Ghidra RE of the client. **Analysis only — no binary was modified.**

> Two different builds are involved. The binary imported into the Ghidra
> project `Neocron2clien.rep` is **7,977,984 bytes** (addresses below prefixed
> `GH:`). The binary actually on disk / running, `~/Neocron2/neocronclient.exe`,
> is **8,977,408 bytes** (md5 `649231490291a96c20890686f317cadd`,
> addresses prefixed `RUN:`). The *code logic* is identical; only addresses
> and file offsets differ. **Use the `RUN:` values to patch the live game.**

---

## 1. The logging sink

Single printf-style sink that formats a timestamped line and appends it to
the daily error log.

- `GH:  FUN_004471c0`  (VA `0x004471c0`)
- `RUN: VA 0x004868c0`, file offset `0x85cc0`
  (prologue `55 8b ec 6a ff 68 f0 c4 a8 00 64 a1 00 00 00 00`)

Signature `void LogWrite(char *msg)`. It:

1. Inspects an optional `@`-prefix on `msg` to pick a severity threshold.
2. Compares the global verbosity level against that threshold; if
   `level < threshold` it returns without writing (the gate).
3. Else: `GetLocalTime` + `GetCurrentThreadId`, prepends
   `"%02d.%02d.%04d %02d:%02d:%02d %05d "`, locks a mutex, lazily opens the
   log file, writes the line + newline, flushes.

Output file name (format string, both builds): `.\logs\error_%04d%02d%02d_%04d.log`
→ resolves to `~/Neocron2/logs/error_<date>_<seq>.log`.

There is a **second, separate** sink, `GH: FUN_0045a280` (the `"Init.Log"`
channel), which writes `~/Neocron2/logs/Init.Log`. It is *not* level-gated and
already records the high-level world-change narrative
(`WorldClient: Joining session . . .`, `WorldManager: Getting start position.`,
`WorldClient: Create new world to change to . . .`,
`WorldClient: World changes successfull!`). Read `Init.Log` first for the
zone-cross skeleton; the byte-level detail is in `error_*.log`.

`SCRIPTEDPLAYER::*` Verify/Help lines additionally go to `OutputDebugStringA`
(visible with DebugView / a debugger), independent of the level gate.

---

## 2. The verbosity gate (single global int)

The gate is **one global integer**, read/written **only inside the sink**.
No config file, no registry key, no command-line argument, no in-game cvar
feeds it. (Confirmed: the address has exactly one writer — the `@L` path in
section 3 — and all readers are the four threshold compares.)

| build | gate variable VA | file offset | default value |
|-------|------------------|-------------|---------------|
| GH    | `0x00b05078`     | (Ghidra `.data`) | `2` |
| RUN   | `0x00bee088`     | `0x7ec088` (`.data`) | `2` (`02 00 00 00`) |

### Prefix → required level (decompiled, RUN disassembly)

The sink branches on the first 1–3 chars of the message, then does
`CMP [gatevar], <threshold>` / `JL skip`. A line is written **iff
`level >= threshold`**:

| message prefix                    | meaning  | threshold | logged at default (2)? |
|-----------------------------------|----------|-----------|------------------------|
| `@?F…` (3rd char `F`)             | FATAL    | 0         | yes (always)           |
| `@E…`                             | ERROR    | 1         | yes                    |
| `@W…` **or no `@` prefix**        | WARN     | 2         | yes                    |
| `@?P…` (3rd char `P`) / `@P…`     | VERBOSE  | 3         | **NO — suppressed**    |
| `@L<number>`                      | *set level = atoi(number)*; nothing printed |

So at the shipped default `level = 2`, the **VERBOSE (threshold-3) tier is
suppressed**. Raising the level to `>= 3` unlocks it. There is no tier above
3, so any value `>= 3` (use `9` for headroom) = maximum verbosity.

### RUN disassembly of the gate (for reference)

```
0x004868f1  CMP byte [msg],0x40        ; '@' ?
0x004868f9  CMP CL,0x4c                ; 'L'  -> set-level path
0x00486902  CALL atoi ; MOV [0xbee088],EAX   (RUN file off of MOV = 0x85d0b)
0x0048691c  CMP dword [0xbee088],0x1   ; @E  ERROR  (level>=1)
0x0048692c  CMP dword [0xbee088],0x0   ; @?F FATAL  (level>=0)
0x00486939  CMP dword [0xbee088],0x3   ; @?P VERBOSE(level>=3)
0x00486942  CMP dword [0xbee088],0x2   ; @W / none WARN (level>=2)
0x00486949  JL  0x0048690a             ; <-- THE GATE  (6 bytes 0f 8c bb 01 00 00)
```

---

## 3. Is there a config / arg / console to set it? — NO

- **Config file:** none. No `.ini`/`.cfg`/`loglevel`/`log_level`/`verbose`/
  `debug` key reaches the gate variable. (`ini\updater.ini`, `ini\hash.ini`
  exist but only feed the updater, not logging.)
- **Registry:** none referencing the gate.
- **Command line:** no `-log`/`-debug`/`@L` argv construction reaches it.
- **In-band only:** the *only* writer is the sink's own `@L` path —
  i.e. some code would have to call `LogWrite("@L9")`. No such call site
  exists in the binary (no `@L…` string is present; the only `@P/@E/@W`
  literals are ordinary messages). So in practice the level is fixed at the
  compile-time `.data` default of `2` and cannot be changed without a patch.
- **Developer console:** the `console.log` / `Host: Console initialized.`
  console is the rendering/engine console; no command there sets the log
  level (it is not wired to the gate variable). No `sv_cheats`/`developer`/
  `exec`-style command affects logging verbosity.

Conclusion: **the only way to raise verbosity is a binary patch.** No safe
config/env/arg method exists in this build.

---

## 4. Recommended patch — 1 byte, raise the default level

Change the gate variable's `.data` default from `2` to `9` (any value `>= 3`).
No code logic changes; every threshold compare now passes for all tiers.

**Target: `~/Neocron2/neocronclient.exe`**

| | value |
|-|-|
| file offset | **`0x7ec088`** |
| original byte | `02` |
| new byte | `09` |
| (verify next 3 bytes) | `00 00 00` (leave as-is) |

Apply (make a backup first):

```sh
cp ~/Neocron2/neocronclient.exe ~/Neocron2/neocronclient.exe.bak
printf '\x09' | dd of=$HOME/Neocron2/neocronclient.exe bs=1 seek=$((0x7ec088)) count=1 conv=notrunc
# verify:
xxd -s 0x7ec088 -l 4 ~/Neocron2/neocronclient.exe   # expect: 09 00 00 00
```

> If the launcher CDN-update or hash-checks `neocronclient.exe`, patch a local
> copy and disable the integrity step, or re-patch after each update.
> The Ghidra-build equivalent (only if you ever run the 7,977,984-byte exe)
> is VA `0x00b05078`.

### Fallback patch — NOP the gate branch (forces every line, all tiers)

If for some reason the default-value patch doesn't take (e.g. the variable is
re-initialised by C++ static init in a build you didn't inspect), NOP the
single gate branch instead so the sink can never skip:

**Target: `~/Neocron2/neocronclient.exe`**

| | value |
|-|-|
| file offset | **`0x85d49`** (VA `0x00486949`) |
| original bytes | `0f 8c bb 01 00 00`  (`JL +0x1bb`) |
| new bytes | `90 90 90 90 90 90`  (6× `NOP`) |

```sh
printf '\x90\x90\x90\x90\x90\x90' | dd of=$HOME/Neocron2/neocronclient.exe bs=1 seek=$((0x85d49)) count=6 conv=notrunc
xxd -s 0x85d49 -l 6 ~/Neocron2/neocronclient.exe   # expect: 90 90 90 90 90 90
```

Prefer the 1-byte default patch; the NOP is heavier (writes literally every
log call including the per-frame ones) and noisier.

---

## 5. What this unlocks for the zone-cross investigation

Already in `error_*.log` at default level 2 (unprefixed / `@W` / `@E`):
`@WWORLDMGR : Unable to Spawn WA`, `SCRIPTEDPLAYER : Script spawn failed`,
`LSTPLAYER : Update Message corrupted`, `Exception created WORLDCLIENT :
Connection failed`. Already in `Init.Log`: the world-change narrative.

These zone-cross / position / sync diagnostics are **present in the binary as
log calls but NOT currently appearing** at level 2 (they are gated to the
threshold-3 verbose tier) — raising the level surfaces them:

- `WC : Changepos set`
- `no worldchange entity found, using random startpos`
- `World Has No StartPos`
- `WORLDCLIENT : Char sys info rcv %i` / `Char Sys Info rcv`
- `WORLDCLIENT : World Change denied`
- `WORLDCLIENT : sync to worldserver failed`
- `WORLDCLIENT : SRV: %i:%i`
- `WORLDCLIENT : new world name received %s %i`
- `WORLDCLIENT : Client up to date %i` / `info rcv %i` / `session closed %i`
- `CTRLPLAYER : Position reset to startpos 0` / `No Startpos found`
- `Old Position: %0.3f, %0.3f, %0.3f` / `New Position: %0.3f, %0.3f, %0.3f`
- `StartPos %i, %i, %i`
- `@PWORLDHOST Connect to %i, %i`  (the one explicit `@P` line — IP/port)

i.e. exactly the "what position does the client compute / why does it loop
Zoning1 / why can't it move" data wanted, straight from the client instead of
inferred from pcaps. The `Old Position` / `New Position` float triples and
`StartPos %i,%i,%i` are the highest-value lines for the movement-authority
issue.

---

## 6. Dead ends (stated explicitly)

- No config-file / `.ini` / `.cfg` key controls log verbosity. Searched all
  defined strings for `loglevel`, `log_level`, `verbose`, `debug`,
  `console`, `developer`, registry paths — none reach the gate variable.
- No command-line switch (`-log`, `-debug`, `@L`, `/debug`, `-console`)
  builds an `@L` argument. The in-band `@L<n>` mechanism exists in the sink
  but is never invoked anywhere in the binary.
- The engine "Console" (`Host: Console initialized.`, `console.log`) is not
  wired to the log-level gate; no console command changes verbosity.
- File-offset patches are build-specific. Offsets here were re-derived for
  the 8,977,408-byte on-disk binary by byte-signature, not copied from the
  Ghidra (7,977,984-byte) build, because the two have different PE section
  layouts.

## Reproduce / re-derive

Ghidra scripts (in `ceres-j/tools/ghidra/`, run from
`/home/javier/Documents/Projects/Neocron`):
`FindLogSink.java` (sink discovery via log-string→call-target voting),
`FindLogGate.java` (gate global xrefs + keyword string scan),
`FindLogLevelInit.java` (default value + `@L`/log-file-name),
`DumpGateBytes.java` (gate machine-code signature for cross-build location).
On-disk offsets verified by PE-section parsing of
`~/Neocron2/neocronclient.exe`.
