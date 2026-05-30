# frida-gadget placement

The gadget is **frida-gadget for Windows x86** (matches the 32-bit
`NeocronClient.exe` PE arch — NC2 is a 32-bit Win32 game). It must
be placed inside the Wine prefix so Wine's loader pulls it into the
target process's address space at startup.

## File layout (inside the game directory)

```
~/Neocron2/NeocronClient/
├── NeocronClient.exe
├── <chosen-name>.dll           ← frida-gadget renamed to a DLL the
│                                 EXE imports (preferred: winmm.dll,
│                                 dinput8.dll, or dwmapi.dll). Use a
│                                 proxy DLL that re-exports the
│                                 original entry points if the EXE
│                                 actually calls them at startup.
└── <chosen-name>.config.json   ← frida-gadget configuration (must
                                  match the DLL's stem, not just
                                  "frida-gadget.config.json").
```

The gadget loader looks for ``<dll-stem>.config.json`` next to the
DLL it was loaded as. If the gadget is renamed to ``winmm.dll`` then
the config file must be ``winmm.config.json``. `setup.sh` handles
the renaming for both files automatically.

## Configuration (`frida-gadget.config.json`)

```json
{
  "interaction": {
    "type": "listen",
    "address": "127.0.0.1",
    "port": 27042,
    "on_port_conflict": "fail",
    "on_load": "wait"
  }
}
```

* ``type: "listen"`` — gadget binds a TCP socket and waits for an
  external client (our orchestrator).
* ``on_load: "wait"`` — gadget **pauses early in DllMain** until the
  orchestrator connects. This is the critical bit: hooks install
  before any game packet flies.
* Port 27042 is the Frida default; pick anything if you run multiple
  agents.

## Why this configuration over the alternatives

* **``script`` mode** (gadget auto-loads a JS file) — simpler but no
  bidirectional RPC, can't push different agents between runs.
* **Linux-side attach** (``frida -p $(pgrep wine64-preloader)``) —
  works in theory, but module bases come from Linux ``/proc/maps``
  which sees Wine's loader gymnastics, and thread context confuses
  Interceptor on Win32 SEH frames.

## Why not just `WINEDLLOVERRIDES`?

We could put the gadget at any DLL name (e.g. `frida-gadget.dll`)
and force Wine to load it via:

    export WINEDLLOVERRIDES="frida-gadget=n,b"
    wine NeocronClient.exe

But Wine only honours overrides for DLLs the loader is asked to
resolve — the EXE has to import it for the loader to look. The
proxy-DLL trick (renaming gadget to `winmm.dll` etc) is more
reliable because the EXE's import table already references that
name.
