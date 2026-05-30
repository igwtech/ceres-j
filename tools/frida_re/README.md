# frida_re — live instrumentation of the retail NC2 client (inside Wine)

Drives the **retail Neocron 2 client** running under Wine/Proton via
[Frida](https://frida.re/), **with the Frida agent dropped INSIDE
the Wine prefix** so it runs natively in the Win32 process space.
This avoids the address-translation issues that come with attaching
Linux-side Frida to `wine64-preloader` (PE module bases via
`/proc/maps`, Linux signal model vs. Win32 SEH, threading mismatches).

Provides both:

1. **Observation** — hooks into known Ghidra-identified functions
   (UDP cipher, session dispatcher, multipart reassembler, HUD ticks,
   …) emit structured events back to a Python recorder over Frida's
   native protocol and to a durable JSONL trace on disk.
2. **Control** — the agent exposes RPC methods (`callFunction`,
   `readMem`, `writeMem`, `sendUdp`, `triggerZoneCross`, …) so the
   orchestrator can *drive* the client through experiments instead
   of relying on a human pilot.

This unblocks the byte-level protocol RE in a way passive pcap
captures never could: we get plaintext on both sides of the cipher,
the exact dispatch-case for every packet, and the ability to replay
single packets with arbitrary mutations.

## Architecture

```
Wine prefix (the same one the launcher already manages)
┌──────────────────────────────────────────────────────────────┐
│ wine64-preloader                                             │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ neocronclient.exe                                        │ │
│ │  • UDP cipher (FUN_00560090 / FUN_0055ff30)             │ │
│ │  • session dispatcher (FUN_0055ec10)                    │ │
│ │  • multipart reassembler (FUN_0055c270)                 │ │
│ │  • CHARSYS / HUD ticks                                  │ │
│ │                                                          │ │
│ │  …/NeocronClient/  (game dir)                            │ │
│ │     ├── NeocronClient.exe                                │ │
│ │     ├── frida-gadget.dll       ← official Frida 16.x x86 │ │
│ │     │   (renamed to a DLL the EXE imports, e.g. winmm)   │ │
│ │     └── frida-gadget.config.json (Listen mode,           │ │
│ │         on_load=wait)                                    │ │
│ └─────────────────────────▲────────────────────────────────┘ │
│                           │ Frida intrinsics                 │
│                           ▼                                  │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ frida-gadget.dll (in-process)                            │ │
│ │  • binds TCP 127.0.0.1:27042                             │ │
│ │  • waits for orchestrator (on_load=wait)                 │ │
│ │  • runs agent JS pushed at attach                        │ │
│ └────────────────────────▲─────────────────────────────────┘ │
└─────────────────────────╂─────────────────────────────────────┘
                          ▼
           ┌─────────────────────────────────────┐
           │ Linux: python -m orchestrator       │
           │  • frida.get_device_manager()       │
           │      .add_remote_device(            │
           │           "127.0.0.1:27042")        │
           │  • pushes agent/_agent.js           │
           │  • recorder → /tmp/frida_nc2.jsonl  │
           │  • CLI: call/read/write/send-udp    │
           └─────────────────────────────────────┘
```

Key point: Wine maps Win32 socket calls to Linux sockets, so a Win32
process binding `127.0.0.1:27042` actually binds Linux's
`127.0.0.1:27042` — the orchestrator connects normally over loopback,
no shim needed.

## Layout

```
frida_re/
├── README.md
├── requirements.txt
├── pytest.ini
├── Makefile
├── setup.sh                   # downloads gadget, names it correctly
├── agent/
│   ├── _agent.js              # Frida agent script (hooks + RPC)
│   ├── frida-gadget.config.json
│   └── README.md              # gadget placement details
├── orchestrator/
│   ├── __init__.py
│   ├── __main__.py            # CLI: attach, observe, control
│   ├── symbols.py             # canonical Ghidra address table
│   ├── decode.py              # event → structured decode
│   ├── recorder.py            # JSONL writer + pretty stdout
│   └── controller.py          # Frida attach + RPC client
├── tests/
│   ├── __init__.py
│   ├── conftest.py            # frida-module stub for unit tests
│   ├── test_symbols.py        # unit (offset arithmetic)
│   ├── test_decode.py         # unit (pure decode functions)
│   ├── test_recorder.py       # unit (JSONL writer)
│   └── test_functional.py     # e2e (mocked Frida session round-trip)
└── docs/
    ├── SYMBOLS.md             # canonical address table
    ├── CONTROL.md             # RPC method reference
    └── WINE_SETUP.md          # gadget installation details
```

## Install

```bash
pip install -r ceres-j/tools/frida_re/requirements.txt

# Download the Windows x86 Frida gadget into the game dir
ceres-j/tools/frida_re/setup.sh ~/Neocron2/NeocronClient
```

`setup.sh` does three things:

1. Downloads `frida-gadget-<ver>-windows-x86.dll.xz` from the
   official Frida releases.
2. Detects which system DLL `NeocronClient.exe` imports that we can
   safely proxy through (prefers `winmm.dll`, falls back to
   `dinput8.dll`, `dwmapi.dll`).
3. Drops the renamed gadget + a `frida-gadget.config.json` next to
   the EXE. Adds the matching `WINEDLLOVERRIDES=<dll>=n,b` line to
   a `frida_re.env` file the user can source before launching.

If the launcher's addon system is in use, the gadget is also
declared as an addon (`frida_re/launcher_addon.json` is generated
for `launcher_addon_priority_model.md` consumption).

## Run

```bash
# Terminal 1: start orchestrator FIRST (gadget waits for it)
python3 -m orchestrator \
    --host 127.0.0.1 --port 27042 \
    --trace /tmp/frida_nc2_$(date +%s).jsonl

# Terminal 2: launch the retail client (existing addon path)
source /tmp/frida_re.env
cd ~/Neocron2 && wine NeocronClient.exe
```

Gadget binds early, blocks on the orchestrator's connection
(`interaction.on_load = "wait"`), then receives the agent JS.
Hooks install before any UDP packet flies.

Interactive control once attached:

```
> read 0x00400000 16             # read 16 bytes at runtime VA
> call 0x00560090 buf=… seed=…   # invoke cipher fn with crafted args
> send-udp 03 1f 01 00 25 …      # encrypt + sendto via the cipher path
> trigger-zone-cross plaza1 p2   # high-level helper
```

## Tests

```bash
cd ceres-j/tools/frida_re
pytest -v
```

Tests **do not require Frida** — `tests/conftest.py` stubs out the
`frida` module so the orchestrator can be unit-tested without a
live client. Functional tests spin up a mocked Frida session and
round-trip synthetic events into the recorder + JSONL.

## Hooks (initial)

| Ghidra addr | Name | Status |
|---|---|---|
| `FUN_00560090` | `udp_cipher_a` | implemented (initial) |
| `FUN_0055ff30` | `udp_cipher_b` | implemented (initial) |
| `FUN_0055ec10` | `session_dispatch` | stub — next iteration |
| `FUN_0055c270` | `multipart_reassemble` | stub |
| `FUN_008447d0` | `charsys_tlv_parse` | stub |
| `FUN_00803cd0` | `fullcharsys_dispatch` | stub |

Addresses are file-VA assuming `ImageBase=0x00400000`. Runtime
resolution subtracts ImageBase and adds the live module base
(`Module.findBaseAddress('NeocronClient.exe')`). See
[docs/SYMBOLS.md](docs/SYMBOLS.md) for the canonical table.

## Status

Bootstrap landed 2026-05-28 (task #256). Inside-Wine architecture
deliberately picked over Linux-side attach to keep all addresses
in native Win32 VA. First hook = UDP cipher.
