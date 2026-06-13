# Neocron client — Ghidra RE export summary
- Binary: `neocronclient.exe` (PE32 i386, ImageBase 0x00400000)
- Functions total: **40326**  | named: **21606**  | unnamed `FUN_`: **18720**  | thunks: 14421
- C++ classes (RTTI): see `classes.txt`  | vftables: see `vftables.txt`
- Functions tagged with source file: **379**
- Namespaced functions (methods under a class/namespace): **354**

## Top game classes/namespaces by member count
(toolchain/STL noise filtered out)

| count | class/namespace |
|---:|:---|
| 32 | `MemMapReadOnly` |
| 22 | `Mod1` |
| 20 | `VCRUNTIME140.DLL` |
| 20 | `API-MS-WIN-CRT-MATH-L1-1-0.DLL` |
| 18 | `MSVCP140.DLL::std::basic_streambuf<char,struct_std::char_traits<char>_>` |
| 15 | `API-MS-WIN-CRT-HEAP-L1-1-0.DLL` |
| 13 | `API-MS-WIN-CRT-RUNTIME-L1-1-0.DLL` |
| 12 | `MSVCP140.DLL::std` |
| 10 | `OBJImpl` |
| 9 | `MSVCP140.DLL` |
| 8 | `KERNEL32.DLL` |
| 6 | `MemMapBase` |
| 6 | `switchD_007fb4d8` |
| 5 | `D3DX9_43.DLL` |
| 4 | `CDebugSOldSectionReader` |
| 4 | `TPI1` |
| 4 | `TMR` |
| 4 | `IMM32.DLL` |
| 2 | `CDocObjectServerItem` |
| 2 | `API-MS-WIN-CRT-UTILITY-L1-1-0.DLL` |
| 2 | `CPropertySet` |
| 2 | `CPreviewDC` |
| 2 | `MSVCP140.DLL::std::locale` |
| 2 | `MSVCP140.DLL::std::locale::facet` |
| 1 | `switchD_00543aef` |
| 1 | `WS2_32.DLL` |
| 1 | `DINPUT8.DLL` |
| 1 | `D3D9.DLL` |
| 1 | `SHELL32.DLL` |

## Functions per source file (from __FILE__/assert strings)

| count | source file |
|---:|:---|
| 34 | `nclib/nclib/genesis/Bitmap/bitmap.cpp` |
| 22 | `nclib/nclib/genesis/Engine/engine.cpp` |
| 17 | `nclib/nclib/base/csection.h` |
| 16 | `nclib/nclib/genesis/Actor/actor.cpp` |
| 16 | `nclib/nclib/net/GameNetMgr.cpp` |
| 14 | `nclib/nclib/net/SRVNetMgr.cpp` |
| 12 | `nclib/nclib/terminal/MenuConnectMgr.cpp` |
| 8 | `nclib/nclib/genesis/World/World.cpp` |
| 5 | `nclib/nclib/genesis/Bitmap/bitmap_blitdata.cpp` |
| 4 | `nclib/nclib/neocron/WorldClient.cpp` |
| 3 | `nclib/nclib/base/CallStack.cpp` |
| 3 | `nclib/nclib/neocron/Client.cpp` |
| 3 | `nclib/nclib/genesis/Engine/BitmapList.cpp` |
| 3 | `Gbspfile.cpp` |
| 3 | `nclib/nclib/neocron/GlobalDatabase.cpp` |
| 3 | `nclib/nclib/net/WinSockMGR.cpp` |
| 3 | `nclib/nclib/genesis/Actor/tkarray.cpp` |
| 3 | `nclib/nclib/procedurals/proceng.cpp` |
| 3 | `nclib/nclib/terminal/ICVSTerminal.cpp` |
| 2 | `nclib/nclib/genesis/Actor/path.cpp` |
| 2 | `nclib/nclib/genesis/Engine/System.cpp` |
| 2 | `nclib/nclib/gamebase/genworldmgr.cpp` |
| 2 | `nclib/nclib/genesis/Actor/vkframe.cpp` |
| 2 | `nclib/nclib/genesis/Actor/QKFrame.cpp` |
| 1 | `Host.cpp` |
| 1 | `nclib/nclib/gamebase/InputMgr.cpp` |
| 1 | `nclib/nclib/genesis/Terrain/Terrain.cpp` |
| 1 | `nclib/nclib/genesis/Terrain/TContent.cpp` |
| 1 | `nclib/nclib/genesis/World/User.cpp` |
| 1 | `nclib/nclib/genesis/Actor/puppet.cpp` |
| 1 | `nclib/nclib/genesis/Actor/motion.cpp` |
| 1 | `nclib/nclib/genesis/Actor/body.cpp` |
| 1 | `nclib/nclib/genesis/Bitmap/bitmap_gamma.cpp` |
| 1 | `nclib/nclib/genesis/Actor/tkevents.cpp` |
| 1 | `nclib/nclib/gamebase/WorldActor.cpp` |
| 1 | `nclib/nclib/procedurals/plasma.cpp` |
| 1 | `nclib/nclib/procedurals/particles.cpp` |
| 1 | `nclib/nclib/procedurals/ElectricFx.cpp` |
| 1 | `nclib/nclib/procedurals/setformat.cpp` |
| 1 | `nclib/nclib/worldactor/CharSystem.cpp` |
| 1 | `nclib/nclib/procedurals/gebmutil.cpp` |
| 1 | `nclib/nclib/procedurals/procutil.cpp` |
