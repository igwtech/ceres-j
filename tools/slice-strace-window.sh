#!/usr/bin/env bash
# Slice an strace log to a [start_ts, end_ts] window.
# Usage: slice-strace-window.sh <log> <start HH:MM:SS.uuuuuu> <end HH:MM:SS.uuuuuu> <out>
set -euo pipefail
LOG="$1"; START="$2"; END="$3"; OUT="$4"
awk -v S="$START" -v E="$END" '$2 >= S && $2 <= E' "$LOG" > "$OUT"
wc -l "$OUT"
