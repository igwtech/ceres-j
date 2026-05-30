#!/bin/bash
# launch_nc2.sh — Launch the NC2 client under Proton with the Frida
# gadget pre-attached (via Ultimate-ASI-Loader dinput8.dll proxy).
#
# Workflow:
#   1. Verify the gadget DLLs are present in the game directory
#      (otherwise install them from launcher-addon/).
#   2. Wire WINEDLLOVERRIDES so Wine prefers our dinput8.dll proxy
#      over the system one.
#   3. exec Proton with the game's .exe.
#
# After this script starts the game, the gadget binds 127.0.0.1:27042
# in WAIT mode (per frida-gadget.config.json), so the game is paused
# at DllMain until the orchestrator attaches. Run orch_attach.sh next.
#
# Environment overrides:
#   NC2_DIR=/path/to/Neocron2          (default: /home/javier/Neocron2)
#   NC2_EXE=neocronclient.exe          (default)
#   PROTON_BIN=/path/to/proton         (default: auto-detect newest GE-Proton)
#   COMPAT_DATA_PATH=/tmp/nc2_compat   (default: a stable path so prefix
#                                       reuses across runs)
#   FRIDA_RE_DIR=...                   (default: parent of this script)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRIDA_RE_DIR="${FRIDA_RE_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
ADDON_DIR="$FRIDA_RE_DIR/launcher-addon"

NC2_DIR="${NC2_DIR:-/home/javier/Neocron2}"
NC2_EXE="${NC2_EXE:-neocronclient.exe}"
COMPAT_DATA_PATH="${COMPAT_DATA_PATH:-$HOME/.cache/nc2_proton_compat}"

# Required gadget files (mirrors addon.json).
GADGET_FILES=(
    "dinput8.dll"
    "frida-gadget.asi"
    "frida-gadget.config.json"
)

# Resolve Proton: explicit env, else newest GE-Proton.
if [[ -z "$PROTON_BIN" ]]; then
    PROTON_BIN="$(ls -td /home/javier/.local/share/Steam/compatibilitytools.d/GE-Proton*/proton 2>/dev/null | head -1)"
fi
if [[ -z "$PROTON_BIN" || ! -x "$PROTON_BIN" ]]; then
    echo "error: Proton not found; set PROTON_BIN" >&2
    exit 2
fi

if [[ ! -f "$NC2_DIR/$NC2_EXE" ]]; then
    echo "error: NC2 client not at $NC2_DIR/$NC2_EXE" >&2
    echo "       set NC2_DIR to override" >&2
    exit 2
fi

# Install gadget files if any are missing or older than the addon's copy.
needs_install=0
for f in "${GADGET_FILES[@]}"; do
    if [[ ! -f "$NC2_DIR/$f" ]]; then
        needs_install=1
        echo "missing: $NC2_DIR/$f"
    elif [[ -f "$ADDON_DIR/$f" && "$ADDON_DIR/$f" -nt "$NC2_DIR/$f" ]]; then
        needs_install=1
        echo "stale:   $NC2_DIR/$f (addon copy newer)"
    fi
done

if (( needs_install )); then
    if [[ "${FRIDA_RE_AUTO_INSTALL:-1}" != "1" ]]; then
        echo "error: gadget files missing/stale and auto-install disabled" >&2
        exit 3
    fi
    echo "installing gadget files from $ADDON_DIR ..."
    for f in "${GADGET_FILES[@]}"; do
        if [[ -f "$ADDON_DIR/$f" ]]; then
            cp -v "$ADDON_DIR/$f" "$NC2_DIR/$f"
        else
            echo "error: addon missing $f" >&2
            exit 4
        fi
    done
fi

# Stable compat path so the Wine prefix persists across runs.
mkdir -p "$COMPAT_DATA_PATH"

export STEAM_COMPAT_CLIENT_INSTALL_PATH="${STEAM_COMPAT_CLIENT_INSTALL_PATH:-$HOME/.local/share/Steam}"
export STEAM_COMPAT_DATA_PATH="$COMPAT_DATA_PATH"

# Tell Wine to prefer our dinput8.dll proxy (native first, fall back to
# builtin). Without this, Wine loads its builtin dinput8 and the gadget
# never runs.
EXISTING_OVERRIDES="${WINEDLLOVERRIDES:-}"
if [[ -n "$EXISTING_OVERRIDES" ]]; then
    export WINEDLLOVERRIDES="dinput8=n,b;$EXISTING_OVERRIDES"
else
    export WINEDLLOVERRIDES="dinput8=n,b"
fi

echo "==============================================================="
echo "Launching NC2 with Frida gadget"
echo "  Proton:       $PROTON_BIN"
echo "  Game:         $NC2_DIR/$NC2_EXE"
echo "  Compat path:  $COMPAT_DATA_PATH"
echo "  DLL override: $WINEDLLOVERRIDES"
echo "  Gadget will listen on 127.0.0.1:27042 (WAIT mode)"
echo "  Next: run scripts/attach_orch.sh to begin instrumentation"
echo "==============================================================="

cd "$NC2_DIR"
exec "$PROTON_BIN" run "$NC2_DIR/$NC2_EXE" "$@"
