#!/usr/bin/env bash
# run_pass.sh — thin launcher for the Python harness (run_pass.py).
#
# Resolves its own location (so it works from ANY directory, even if
# symlinked onto your PATH), selects the project venv interpreter, and
# delegates all arguments to run_pass.py.
#
#   run_pass.sh ceres                # full pass vs Ceres-J
#   run_pass.sh retail my_run        # same pass vs retail
#   NC2_PY=/usr/bin/python3 run_pass.sh ceres   # override interpreter
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

exec "$PY" "$SCRIPT_DIR/run_pass.py" "$@"
