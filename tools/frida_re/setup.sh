#!/usr/bin/env bash
# Downloads frida-gadget for Windows x86 and drops it inside the
# game directory under a name the EXE imports, so Wine's loader
# picks it up at process start.
#
# Usage:
#   setup.sh <game-dir> [--frida-version 16.5.6] [--dll-name winmm]
#
# Defaults:
#   --frida-version  : latest tag fetched from GitHub
#   --dll-name       : autodetect via objdump (winmm > dinput8 > dwmapi)
#
# Idempotent: re-running replaces the gadget binary but does not
# touch a hand-edited *.config.json (we back it up first).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GAME_DIR=""
FRIDA_VERSION=""
FORCED_DLL_NAME=""

die() { echo "error: $*" >&2; exit 2; }
info() { printf '[setup] %s\n' "$*"; }

usage() {
    sed -n '2,12p' "$0"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --frida-version) FRIDA_VERSION="$2"; shift 2 ;;
        --dll-name) FORCED_DLL_NAME="$2"; shift 2 ;;
        --*) die "unknown flag: $1" ;;
        *)
            if [[ -z "$GAME_DIR" ]]; then GAME_DIR="$1"; shift
            else die "unexpected positional: $1"; fi
            ;;
    esac
done

[[ -n "$GAME_DIR" ]] || { usage; die "missing <game-dir>"; }
GAME_DIR="${GAME_DIR/#~/$HOME}"
[[ -d "$GAME_DIR" ]] || die "game dir not found: $GAME_DIR"

EXE_PATH=""
for candidate in NeocronClient.exe neocronclient.exe; do
    if [[ -f "$GAME_DIR/$candidate" ]]; then
        EXE_PATH="$GAME_DIR/$candidate"
        break
    fi
done
[[ -n "$EXE_PATH" ]] || die "NeocronClient.exe not in $GAME_DIR"

# ---------------------------------------------------------------------
# Resolve target DLL name
# ---------------------------------------------------------------------
detect_dll_name() {
    if ! command -v objdump >/dev/null 2>&1; then
        info "objdump not available — defaulting to winmm.dll"
        echo "winmm.dll"; return
    fi
    local imports
    imports="$(objdump -p "$EXE_PATH" 2>/dev/null \
        | awk '/DLL Name:/ {print tolower($3)}')"
    for preferred in winmm.dll dinput8.dll dwmapi.dll d3d9.dll; do
        if grep -qx "$preferred" <<<"$imports"; then
            echo "$preferred"; return
        fi
    done
    info "no preferred DLL imported by EXE — falling back to winmm.dll"
    info "(you may need WINEDLLOVERRIDES=winmm=n,b for Wine to load it)"
    echo "winmm.dll"
}

DLL_NAME="${FORCED_DLL_NAME:-$(detect_dll_name)}"
DLL_STEM="${DLL_NAME%.dll}"
DST_DLL="$GAME_DIR/$DLL_NAME"
DST_CFG="$GAME_DIR/${DLL_STEM}.config.json"

info "target EXE      : $EXE_PATH"
info "target DLL name : $DLL_NAME"

# ---------------------------------------------------------------------
# Resolve gadget version + download URL
# ---------------------------------------------------------------------
if [[ -z "$FRIDA_VERSION" ]]; then
    info "looking up latest Frida release tag…"
    # Capture the full response first; piping into ``grep -m1`` closes
    # the pipe early and trips ``set -o pipefail`` with curl error 23.
    _release_json="$(curl -fsSL \
        https://api.github.com/repos/frida/frida/releases/latest)"
    FRIDA_VERSION="$(printf '%s\n' "$_release_json" \
        | grep -m1 '"tag_name"' \
        | sed -E 's/.*"tag_name":[[:space:]]*"([^"]+)".*/\1/')"
    [[ -n "$FRIDA_VERSION" ]] \
        || die "could not resolve latest Frida release"
fi
info "frida version   : $FRIDA_VERSION"

GADGET_NAME="frida-gadget-${FRIDA_VERSION}-windows-x86.dll.xz"
GADGET_URL="https://github.com/frida/frida/releases/download/${FRIDA_VERSION}/${GADGET_NAME}"

CACHE_DIR="$HERE/.cache"
mkdir -p "$CACHE_DIR"
GADGET_XZ="$CACHE_DIR/$GADGET_NAME"

if [[ ! -f "$GADGET_XZ" ]]; then
    info "downloading $GADGET_URL"
    curl -fsSL --retry 3 -o "$GADGET_XZ.part" "$GADGET_URL"
    mv "$GADGET_XZ.part" "$GADGET_XZ"
fi

# ---------------------------------------------------------------------
# Decompress + install
# ---------------------------------------------------------------------
if [[ -f "$DST_DLL" && ! -f "$DST_DLL.bak" ]]; then
    info "backing up existing $DLL_NAME → ${DLL_NAME}.bak"
    cp "$DST_DLL" "$DST_DLL.bak"
fi

info "installing gadget → $DST_DLL"
xz -dc "$GADGET_XZ" > "$DST_DLL"

if [[ -f "$DST_CFG" ]]; then
    info "preserving existing config: $DST_CFG (skipping overwrite)"
else
    info "installing config → $DST_CFG"
    cp "$HERE/agent/frida-gadget.config.json" "$DST_CFG"
fi

# ---------------------------------------------------------------------
# Emit env shim for the user to source before launching the client
# ---------------------------------------------------------------------
ENV_FILE="/tmp/frida_re.env"
cat > "$ENV_FILE" <<EOF
# source this before launching NeocronClient.exe
export WINEDLLOVERRIDES="${DLL_STEM}=n,b"
echo "[frida_re] WINEDLLOVERRIDES set: ${DLL_STEM}=n,b"
EOF
info "wrote $ENV_FILE"

info "done. start orchestrator FIRST, then:"
info "    source $ENV_FILE && (cd \"$GAME_DIR\" && wine $(basename "$EXE_PATH"))"
