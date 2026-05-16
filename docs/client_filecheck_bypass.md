# Neocron 2 Evolution client — disable the in-client "File System Check"

How to make `neocronclient.exe` boot **without** the
`File System Check failed: N file(s) corrupt or missing: ... The application
will now shutdown.` MessageBox + process termination, so a hand-patched exe
runs without re-hashing `ini\hash.ini` after every edit.

**Analysis only — no binary was modified by this work. The on-disk
`~/Neocron2/neocronclient.exe` is verified pristine
(md5 `649231490291a96c20890686f317cadd`, 8,977,408 bytes).**

> Two builds exist. The Ghidra project `Neocron2clien.rep` holds an OLDER
> image (strings at VA `0xa0bxxx`, file-check fns `FUN_004609e0` /
> `FUN_0043f7d0`). The on-disk / running exe is a **different, newer build**
> (8,977,408 B; strings at VA `0xae6xxx`). Same two-function logic, but PE
> layout, stack-frame displacements and rel32 targets differ. **All offsets
> below were re-derived independently against the 8,977,408-byte on-disk
> exe** (PE parsed: imagebase `0x400000`, `.text` RVA `0x1000` raw `0x400`).

---

## 0. Why attempt #1 failed (root cause)

The previous agent's reasoning chain was *almost* right but it patched the
**wrong function**:

- It correctly found that the client has functions referencing the failure
  strings: `FUN_004609e0` ("the in-client check") and `FUN_0043f7d0` (which
  it dismissed as "the launcher/updater hash builder, not called by
  FUN_004609e0 — updater path, irrelevant").
- It patched a conditional jump **inside `FUN_004609e0`**. On-disk that is
  file offset `0x9f528`, VA `0x8a0128`, bytes `0f 84 44 02 00 00`
  (`JZ 0x8a0372`). The byte signature it gave was actually correct and the
  rel32 arithmetic was correct — the jump *was* flipped to an unconditional
  `JMP 0x8a0372`.
- **But `FUN_004609e0` never produces the boot dialog.** Its only caller
  (Ghidra `0x00462b7c`) invokes it as a `thiscall` with the 4th argument
  **`param_4 = 1`** (`PUSH 0x1` at `0x00462b62`, right before the call).
  Inside `FUN_004609e0` the MessageBox+throw block is gated by
  `CMP byte ptr [EBP+0x14],0 ; JZ <fatal>` — i.e. it only shows the dialog
  and throws **when `param_4 == 0`**. Called with `param_4 = 1`, the
  function merely returns `false`; it never pops a dialog and never throws.
  So patching its internal flag-gate changes nothing the user can see.

- The dialog + shutdown the user actually hits comes from the **other**
  function, `FUN_0043f7d0`. On disk this is the block at
  VA `0x87f3xx–0x87fexx` / file `0x7e7xx–0x7f2xx`. It is called
  **unconditionally from the CRT/`WinMain` startup path**
  (`FUN_009ac530` → `FUN_009ac2e8` → `0x009ac3de CALL thunk_FUN_0043f7d0`,
  args `PUSH 0x400000` = image base). It has **no `param_4`-style guard**:
  whenever its internal fail-flag is set it *unconditionally* calls
  `MessageBoxA(... "File Error!" ...)` then `_CxxThrowException` — and that
  is the "File System Check failed: 1 file(s) corrupt or missing:
  neocronclient.exe … The application will now shutdown." popup followed by
  process death. This function was never patched.

**Conclusion: attempt #1 patched the cosmetically-similar but functionally
inert sibling. The real boot-time integrity gate is in `FUN_0043f7d0`.**

---

## 1. The real boot-time check — `FUN_0043f7d0`

| | on-disk (8,977,408 B) | Ghidra build |
|-|-|-|
| function | code at VA `~0x87f300`+ | `FUN_0043f7d0` |
| reached via | CRT startup → thunk `0x00414f01` | same |
| call site | `0x009ac3de  CALL thunk` (args incl. `PUSH 0x400000`) | same |

Failure strings (each occurs **exactly once** in the on-disk file):

| string | file offset | VA |
|--------|-------------|----|
| `ini\hash.ini` | `0x6e55fc` | `0xae67fc` |
| `File System Check failed: %d file(s) corrupt or missing:\n\n%s` | `0x6e560c` | `0xae680c` |
| `File System Check failed: could not load file table` | `0x6e56f8` | `0xae68f8` |
| `File Error!` | `0x6e5738` | `0xae6938` |
| `File System Check failed.` | `0x6e5748` | `0xae6948` |
| `…The application will now shutdown…` | `0x6e5659` | (in msg body) |

### Decompiled failure flow (Ghidra `FUN_0043f7d0`, identical logic on disk)

```c
... open ini\hash.ini, build allowed-extension table, hash every listed file
    (per-file mismatch → fail-flag byte = 1, name appended to a list) ...

if (fail_flag != 0) {                                  // <-- THE MASTER GATE
    if (file_table_ptr == 0)
        sprintf(buf,"File System Check failed: could not load file table");
    else {
        sprintf(buf,"File System Check failed: %d file(s) corrupt or "
                    "missing:\n\n%s", count, list);
        strcat(buf,"\nThe application will now shutdown. You may use the\n"
                   "NeocronLauncher's \"Check Files...\" option ...");
    }
    MessageBoxA(0, buf, "File Error!", 0x10);           // THE DIALOG
    build_exception("File System Check failed.");
    _CxxThrowException(...);                            // PROCESS TERMINATION
}
... normal cleanup / return ...
```

### On-disk disassembly of the master gate (re-derived, exact)

```
87f6f0:  88 85 df 94 ff ff   mov BYTE [ebp-0x6b21],al   ; fail-flag writer #1
87f7b4:  c6 85 df 94 ff ff 01 mov BYTE [ebp-0x6b21],1   ; fail-flag writer #2
87f965:  c6 85 df 94 ff ff 01 mov BYTE [ebp-0x6b21],1   ; fail-flag writer #3
...
87f99b:  56                  push esi
87f99c:  e8 57 46 f8 ff      call 0x803ff8
87f9a1:  8a 85 df 94 ff ff   mov  al,BYTE [ebp-0x6b21]  ; read fail-flag (ONLY read)
87f9a7:  83 c4 08            add  esp,8
87f9aa:  84 c0               test al,al
87f9ac:  74 48               je   0x87f9f6              ; <-- MASTER GATE (flag==0 -> PASS)
87f9ae:  83 bd d8 94 ff ff 00 cmp DWORD [ebp-0x6b28],0  ; file-table ptr == 0?
87f9b5:  0f 84 38 04 00 00   je   0x87fdf3              ;   -> "could not load file table"
87f9bb:  ...                                            ;   -> "%d file(s) corrupt" build
         ... both sub-branches converge ...
87fe0c:  6a 10               push 0x10
87fe0e:  68 38 69 ae 00      push 0xae6938              ; "File Error!"
87fe1c:  ff 15 1c 08 28 01   call ds:0x128081c          ; MessageBoxA  (THE DIALOG)
87fe28:  68 48 69 ae 00      push 0xae6948              ; "File System Check failed."
87fe42:  e8 49 e2 5f 00      call 0xe7e090              ; _CxxThrowException (SHUTDOWN)
87f9f6:  8d 8d 44 ff ff ff   lea  ecx,[ebp-0xbc]        ; <-- PASS continuation
87f9fc:  e8 de 3a f9 ff      call 0x8134df              ;   destructor / cleanup
87fa01:  ...                                            ;   normal return, NO MB / NO throw
```

The fail-flag byte `[ebp-0x6b21]` has **three writers** but is **read
exactly once** (`0x87f9a1`) and tested at exactly one place
(`0x87f9aa`/`0x87f9ac`). Every "could not load file table" and every
"%d file(s) corrupt" sub-path is **downstream of the single `je 0x87f9f6`**.
The pass target `0x87f9f6` is plain cleanup code — it does **not** reach
`MessageBoxA` (`0x128081c`) or `_CxxThrowException` (`0xe7e090`).

---

## 2. Chosen patch — flip the single master gate (1 byte, 1 site)

**Option (b)**: force the gate to always take the "passed" branch, at the
one point right before the corrupt-count / load-fail verdict is acted on.
This covers **both** failure sub-branches because they are both reachable
only by *not* taking this jump.

- Patching `FUN_004609e0` (attempt #1): **rejected** — that function is
  called with `param_4=1` and is already non-fatal; it is not the boot
  gate. (This was the bug.)
- Stubbing the `FUN_0043f7d0` prologue to early-return (option a):
  **rejected** — the function has required side effects after the gate
  (resource frees, the `[esi]` cleanup at `0x87f9f6`, and it returns a
  status the CRT consumes); skipping its whole body risks leaks / a wrong
  startup status word. The flag-gate flip keeps all of that intact.
- Neutralising the MessageBox/throw site directly (option c): possible but
  multi-instruction and leaves the formatted-string build running for
  nothing. The single-branch flip is smaller and strictly cleaner.

`je rel8` (`74 48`) and `jmp rel8` (`eb 48`) are **both 2 bytes with the
same rel8 displacement `0x48`**, so the destination is byte-identical
(`0x87f9ae + 0x48 = 0x87f9f6`). Only the condition is removed; nothing
shifts, no padding/NOP needed, no rel32 recomputation.

### Exact on-disk bytes

| | value |
|-|-|
| patch file offset | **`0x7edac`** |
| VA | `0x87f9ac` |
| original bytes | `74 48`  (`JE 0x87f9f6`) |
| new bytes | `eb 48`  (`JMP 0x87f9f6`) |
| jump destination | unchanged — file `0x7edf6`, VA `0x87f9f6` (pass path) |

**Unique signature (occurs exactly once in the 8,977,408-byte file):**

13-byte exact gate signature, found at file offset `0x7eda1`, **1
occurrence**:

```
8a 85 df 94 ff ff 83 c4 08 84 c0 74 48
└ mov al,[ebp-0x6b21] ┘ └add esp,8┘ └test al,al┘ └JE┘
                                              ^^^^^ patch the 'je' (74) at +12 = file 0x7edac
```

Wider 32-byte verification window (file `0x7ed9b`, **1 occurrence**):

```
56 e8 57 46 f8 ff 8a 85 df 94 ff ff 83 c4 08 84 c0 74 48 83 bd d8 94 ff ff 00 0f 84 38 04 00 00
```

`74` (JE) lives at window offset +17 → file `0x7edac`.

**Confidence: high.** Unique signature (1 occurrence), instruction lengths
identical so no displacement recomputation, destination byte-confirmed as
the cleanup/return path (no MessageBox, no throw), gate proven to be the
sole test of the sole fail-flag, and this function proven to be the
CRT-startup integrity check (the one with the unconditional
MessageBox+throw, unlike the inert `param_4=1` sibling).

---

## 3. Apply / verify / restore (copy-paste)

```sh
# 0. BACK UP FIRST
cp ~/Neocron2/neocronclient.exe ~/Neocron2/neocronclient.exe.filecheck.bak

# 1. Sanity-check BEFORE patching.
#    Gate byte (must print: 74 48):
xxd -s 0x7edac -l 2 ~/Neocron2/neocronclient.exe
#    13-byte unique gate signature (must print: 8a85df94ffff83c40884c07448):
xxd -s 0x7eda1 -l 13 ~/Neocron2/neocronclient.exe
#    32-byte wide window (must print:
#      56e857 46f8ff 8a85df94ffff 83c408 84c0 7448 83bdd894ffff00 0f84380400 00):
xxd -s 0x7ed9b -l 32 ~/Neocron2/neocronclient.exe

# 2. Apply the patch  (JE 0x87f9f6  ->  JMP 0x87f9f6 ; same target)
printf '\xeb\x48' | \
  dd of="$HOME/Neocron2/neocronclient.exe" bs=1 seek=$((0x7edac)) count=2 conv=notrunc

# 3. Verify (must now print: eb 48)
xxd -s 0x7edac -l 2 ~/Neocron2/neocronclient.exe

# 4. Restore if needed
cp ~/Neocron2/neocronclient.exe.filecheck.bak ~/Neocron2/neocronclient.exe
```

After patching, the startup integrity routine still opens `ini\hash.ini`,
still builds the extension table, still hashes every file and still tallies
"corrupt" files internally — but the tally is never acted on: no
`File Error!` box, no `_CxxThrowException`, boot proceeds. No need to
regenerate `ini\hash.ini` after editing the exe.

---

## 4. Safety / collateral analysis

- **Only the failure OUTCOME is removed.** Everything before the gate
  (hash.ini open + parse, extension-table descramble, per-file hashing,
  the cleanup at/after `0x87f9f6`) runs unchanged. We flip exactly one
  condition; no bytes move; the jump target is identical.
- **Covers ALL failure paths.** Both the "%d file(s) corrupt" branch
  (`0x87f9bb`+) and the "could not load file table" branch (`0x87fdf3`,
  reached via `je 0x87fdf3` at `0x87f9b5`) are reachable **only when the
  master `je 0x87f9f6` is NOT taken**. Making it unconditional bypasses the
  entire `if(fail)` block, so a missing/stripped hash.ini also no longer
  blocks boot. There is exactly one read and one test of the single
  fail-flag byte; all three flag writers funnel through it.
- **Pass target is the normal return path.** `0x87f9f6` is destructor +
  resource-free + function return; it does not call MessageBoxA
  (`0x128081c`) nor `_CxxThrowException` (`0xe7e090`). So we behave exactly
  as a genuine "all files OK" run.
- **Sibling `FUN_004609e0` untouched.** It is unrelated to the boot dialog
  (called with `param_4=1`, already non-fatal). We do not patch it; the
  prior site `0x9f528` stays at its pristine `0f 84 44 02 00 00`.
- **No shared-helper collateral.** The patched byte is one branch private
  to `FUN_0043f7d0`. File loading, the PAK loaders, the world loader and
  networking are in unrelated code and untouched. The hashing helpers still
  run; their result is simply no longer fatal.
- **Launcher handshake.** The "Please double click NeocronLauncher.exe"
  MessageBox is a *different* `MessageBoxA` site in the same function
  (`0x87f4f4`, string `0xae67b4`), reached on a different condition; it is
  not on the corrupt-file path and is not modified. (If the game refuses to
  start because it wasn't launched via NeocronLauncher.exe, that is a
  separate gate — out of scope here; this patch only kills the integrity
  dialog/shutdown.)

### Residual uncertainties (explicit)

- **Build specificity.** Offsets are for the 8,977,408-byte exe
  (md5 `649231490291a96c20890686f317cadd`). For any other build, locate the
  13-byte signature `8a 85 ?? ?? ?? ?? 83 c4 08 84 c0 74 ??` (the `??` are
  a stack-frame disp32 and the rel8 that drift between builds; in this
  build they are `df 94 ff ff` and `48`) — it is the
  `mov al,[fail-flag]; add esp,8; test al,al; je <pass>` idiom right before
  a `cmp DWORD [..],0 / je <could-not-load-table>` — and change that `74`
  (JE) to `eb` (JMP), leaving its rel8 byte unchanged.
- The CRT call path was confirmed by disassembly
  (`FUN_009ac530`→`FUN_009ac2e8`→`CALL thunk_FUN_0043f7d0`, `PUSH 0x400000`);
  Ghidra could not decompile those CRT frames (returned empty), but the
  raw disasm and the unconditional MessageBox+throw (no `param_4` guard,
  unlike `FUN_004609e0`) are unambiguous and match the observed symptom
  text ("…The application will now shutdown…").
- The hash.ini *content* digest algorithm (in the per-file hash helpers)
  was not reversed — not needed for this bypass. If a re-hash route is ever
  wanted instead, that is where to look.
- If the launcher CDN-update overwrites `neocronclient.exe`, re-apply the
  patch (or patch a working copy).

## Reproduce / re-derive

1. PE-parse the on-disk exe (imagebase `0x400000`, `.text` RVA `0x1000`
   raw `0x400`; VA→fileoff = `VA − 0x401000 + 0x400`).
2. Locate the four failure strings by content (each unique), get their VAs.
3. Find `PUSH <stringVA>` (`68 xx xx xx xx`) sites in `.text`: they cluster
   into two functions — `0x7e7xx–0x7f2xx` (`FUN_0043f7d0`, the CRT-startup
   check) and `0x9efxx–0x9f8xx` (`FUN_004609e0`, the `param_4`-guarded
   sibling).
4. Ghidra (older build, `Neocron2clien.rep`) for control flow + the
   `param_4=1` call-site proof:
   ```
   /opt/ghidra/support/analyzeHeadless . Neocron2clien -process \
     -noanalysis -scriptPath ceres-j/tools/ghidra \
     -postScript FileCheckThunkCallers.java   # callers & call args
   ```
   plus `FindFileCheck.java` (string→xref + decomp). Disassemble the
   on-disk failure regions with `objdump -D -b binary -m i386 -M intel`.
5. The master gate is the single `test al,al ; je <pass>` over the single
   fail-flag in `FUN_0043f7d0` → file offset `0x7edac`, `74 48` → `eb 48`.
