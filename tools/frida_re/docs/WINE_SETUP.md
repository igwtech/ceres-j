# Wine setup notes

## Why "inside Wine" beats Linux-side attach

When Frida attaches to a Linux process (`frida -p $(pgrep wine64-preloader)`)
it sees the process through Linux's view of memory:

* Modules come from `/proc/$pid/maps`, which only shows the file-backed
  mappings Wine made via `mmap(MAP_FIXED, …)`. The PE image *is*
  mapped that way for `NeocronClient.exe`, so the module *does* show
  up — but its name on Linux is the full path Wine resolved, not
  the friendly `NeocronClient.exe` the in-process Windows view
  provides.
* Thread context: `Interceptor.attach` works at the architecture
  level (x86 regs), but Frida's SEH handling assumes a real Windows
  process; under Wine the exception/signal model is hybrid and some
  `Interceptor.replace` paths don't unwind cleanly.
* Symbol resolution (PE exports, Authenticode-style validation) goes
  through Frida's PE parser on the Linux side, which is fine for
  reads but produces unusual address arithmetic if the EXE was
  loaded at a non-default base by Wine's loader.

Dropping `frida-gadget.dll` inside the Wine prefix puts Frida in the
same in-process view the game has:

* `Process.findModuleByName("NeocronClient.exe")` returns the in-Win32
  module the EXE itself sees. Bases match the Ghidra ImageBase
  (`0x00400000` modulo ASLR, which NC2 doesn't enable).
* `Interceptor` operates on the Win32 thread the EXE actually uses
  (the Wine "main" thread that the loader cooked up for the PE).
* PE imports / exports work normally — no Linux-side proxy.

## How the gadget gets loaded

`setup.sh` renames `frida-gadget.dll` to one of the system DLLs the
EXE imports. We prefer `winmm.dll` because:

1. Almost every Windows game (and NC2 specifically) imports
   `winmm.dll` for `timeGetTime` / sound mixer hooks. Wine's loader
   *will* try to resolve it.
2. By dropping our renamed gadget into the game directory, Wine's
   loader prefers the local file over the system one (this is
   standard Wine DLL search order: app dir → System32 → other paths).
3. We don't even need `WINEDLLOVERRIDES` — the local file wins on
   import-name lookup.

Fallbacks: `dinput8.dll`, `dwmapi.dll`, `d3d9.dll` (any of which the
EXE imports). `setup.sh` introspects the EXE's import table via
`objdump -p` and picks the first hit.

### Edge case: the EXE actually calls functions in the proxied DLL

If we proxy `winmm.dll` and the EXE calls `timeGetTime` early, our
renamed gadget doesn't export that — the EXE crashes at startup.

There are three mitigations:

1. **Use a DLL the EXE imports but doesn't actually call.** Some
   imports are vestigial (linker pulled them in but no live call
   path exercises them).
2. **Wrap the gadget in a proxy DLL** that re-exports the real
   `winmm.dll`'s functions and load the gadget on the side. ReShade
   uses this pattern; we have a working version in
   `renodx_stack_validated.md`.
3. **Use `dwmapi.dll`** — XP-era games (NC2 is one) import it for
   forward compatibility but never call it under XP. Lowest-risk
   fallback if the prefered DLL crashes.

`setup.sh` doesn't yet ship a proxy-wrapper build; that's deferred
until we observe an actual startup crash with the simple-rename
approach.

## Verifying the gadget is loaded

After launching the client with the gadget in place:

```bash
# From Linux:
ss -tlnp | grep 27042       # should show the wine process listening
```

If you see the listener but the orchestrator gets `Connection refused`,
the gadget loaded but Wine networking is using a `WINEPREFIX`-private
loopback — unlikely with default Wine but possible with some
sandboxed setups. Inspect with `strace -e network -p <pid>` to
confirm.

## Cleanup

The gadget DLL is persistent across launches. To uninstall:

```bash
cd ~/Neocron2/NeocronClient
mv winmm.dll.bak winmm.dll      # restore original (if setup.sh saved one)
rm winmm.config.json            # remove gadget config
```

Or for a fresh attempt, re-run `setup.sh` and it'll re-install.
