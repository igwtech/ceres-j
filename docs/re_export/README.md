# Neocron client — whole-binary Ghidra RE export

Automated decompilation + name-recovery pass over `neocronclient.exe`
(PE32 i386, ImageBase `0x00400000`), persisted into the shared Ghidra project
`Neocron2clien.rep`. Re-runnable via the scripts in
`ceres-j/tools/ghidra/` (see "How this was produced" below).

## What the binary gives us (and what it can't)

The client is **not stripped**: it retains MSVC **RTTI** (1345 class descriptors),
**mangled `@@` symbols** (1492), and **`__FILE__`/assert source paths**. That is
why class names and C++ method signatures recover automatically.

It does **not** ship a PDB, so the ~18.7k internal/inlined `FUN_*` functions have
no symbol and are only recoverable by per-function manual RE. Automated naming is
at its ceiling; this export captures everything the symbols can give.

## Files

| file | contents |
|---|---|
| `SUMMARY.md` | counts + top classes + per-source-file function map (quote-aware) |
| `functions.csv` | every function: `address,name,namespace,signature,src` |
| `classes.txt` | RTTI `GhidraClass` namespaces with their member symbols/methods |
| `vftables.txt` | all vftable/vbtable symbols (class → vtable address) |
| `decomp/*.c` | full C decompilation, one file per class/namespace (`_global.c` = the rest) |
| `analysis.log` / `decomp.log` | headless run logs |

## Key recovered structure

- **Networking**: `GAMENETMGR`, `nclib/net/{GameNetMgr,SRVNetMgr,WinSockMGR}.cpp`
- **World/session**: `WORLDCLIENT`, `BASEWORLDACTOR`, `WORLDACTOR`, `SCRIPTEDPLAYER`,
  `nclib/neocron/{WorldClient,Client,GlobalDatabase}.cpp`, `gamebase/genworldmgr.cpp`
- **Engine (genesis)**: `geEngine`, `nclib/genesis/{Engine,Actor,Bitmap,Terrain,World}/*`
- **UI (RPOS)**: the full `RPOS*` window/widget class family (`BASERPOS`, `RPOS`,
  `RPOSCONTAINERWINDOW`, `RPOSCHATLIST`, `RPOSCOMPASS`, …)
- **Terminal/CityCom**: `nclib/terminal/{MenuConnectMgr,ICVSTerminal}.cpp`

## How this was produced (`ceres-j/tools/ghidra/`)

1. `RunFullAnalysis.java` — enable & re-run RTTI + Microsoft Demangler + Function ID
   + Decompiler Parameter ID over the whole image.
2. `TagBySourceFile.java` — tag functions with their `nclib/...cpp` origin from
   `__FILE__`/assert strings (plate comment `[src: ...]`).
3. `BindVtableMethods.java` — reparent each vftable slot's unnamed target under its
   owning class as `<Class>::vfunc_<index>` (groups virtual methods under classes).
4. `ExportNames.java` / `ExportDecomp.java` — dump the tables and full C.
5. `summarize_export.py` — quote-aware `SUMMARY.md`.

Run any stage headless, e.g.:

```bash
/opt/ghidra/support/analyzeHeadless . Neocron2clien \
  -process neocronclient.exe -noanalysis \
  -scriptPath ceres-j/tools/ghidra -postScript <Script>.java
```

> The `.rep` must not be open in the Ghidra GUI (it holds an exclusive lock).
