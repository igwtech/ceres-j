#!/usr/bin/env bash
# charselect_probe.sh — thin launcher for the fast char-select probe
# (charselect_probe.py). Logs in, screenshots char-select, reports any
# client error-log delta / crash, then exits — no world entry.
#
# Resolves its own location (so it works from ANY directory, even if
# symlinked onto your PATH), selects the project venv interpreter, and
# delegates all arguments to charselect_probe.py.
#
#   charselect_probe.sh ceres                              # msn3wolf vs Ceres-J
#   charselect_probe.sh ceres --user testbot --password testpw
#   charselect_probe.sh retail --run-id rt_charsel         # vs retail
#   NC2_PY=/usr/bin/python3 charselect_probe.sh ceres      # override interpreter
set -euo pipefail

# Absolute dir of this script, following symlinks.
SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do
  DIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"
  SOURCE="$(readlink "$SOURCE")"
  [[ "$SOURCE" != /* ]] && SOURCE="$DIR/$SOURCE"
done
SCRIPT_DIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"

# Interpreter: $NC2_PY > project venv > system python3.
PY="${NC2_PY:-/home/javier/Documents/Projects/Neocron/bin/python}"
[ -x "$PY" ] || PY="$(command -v python3)"
[ -n "$PY" ] || { echo "run_pass.sh: no python interpreter found" >&2; exit 3; }

exec "$PY" "$SCRIPT_DIR/charselect_probe.py" "$@"
