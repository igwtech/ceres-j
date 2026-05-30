#!/bin/bash
# attach_orch.sh — Wait for the Frida gadget on 127.0.0.1:27042 (after
# launch_nc2.sh starts the game) and then attach the orchestrator.
#
# Two output files for the operator:
#   /tmp/current_trace      — path to the JSONL trace this session writes
#   /tmp/current_cmd_file   — path to the control file accepting RPC cmds
#
# To drive the orchestrator from external scripts:
#   echo capture >> /tmp/orch_cmds.txt
# (See frida_input.py for the higher-level API.)
#
# Environment overrides:
#   DURATION=<seconds>   default 1800 (30 min)
#   PORT=<n>             default 27042
#   FRIDA_RE_DIR=...     default: parent of this script

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRIDA_RE_DIR="${FRIDA_RE_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"

DURATION="${DURATION:-1800}"
PORT="${PORT:-27042}"
PYTHON="${PYTHON:-/home/javier/Documents/Projects/Neocron/bin/python}"

TRACE="/tmp/frida_nc2_$(date +%s).jsonl"
CMD_FILE="/tmp/orch_cmds.txt"
LOG_FILE="/tmp/orch_session.log"

echo "$TRACE"    > /tmp/current_trace
echo "$CMD_FILE" > /tmp/current_cmd_file
: > "$CMD_FILE"

echo "waiting for gadget on 127.0.0.1:$PORT — launch the game first via scripts/launch_nc2.sh"
for i in $(seq 1 300); do
    if ss -tln 2>/dev/null | grep -q ":$PORT "; then
        echo "[T+${i}s] gadget bound — attaching orchestrator"
        break
    fi
    sleep 1
done
if ! ss -tln 2>/dev/null | grep -q ":$PORT "; then
    echo "timeout: gadget never appeared in 5 minutes"
    exit 1
fi
sleep 0.2

cd "$FRIDA_RE_DIR"
echo "trace:    $TRACE"
echo "cmds in:  $CMD_FILE (echo lines to send commands)"
echo "log:      $LOG_FILE"
exec "$PYTHON" -m orchestrator \
    --port "$PORT" \
    --trace "$TRACE" \
    --commands "$CMD_FILE" \
    --duration "$DURATION" \
    -v 2>&1 | tee "$LOG_FILE"
