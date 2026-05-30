#!/usr/bin/env bash
# capture.sh — labeled retail pcap capture for HYP-001 (NPC tick).
#
# Walks you through five phases of mob observation, writing a
# timestamped marker to .markers each time you press Enter. The
# analyze.py companion slices the pcap by these markers.
#
# Usage:
#   tools/protocol_re/experiments/exp_001_npc_tick/capture.sh
#
# Requires: dumpcap (wireshark-cli) and sudo. Hardcodes server
# 157.90.195.74 (retail GameServer) — change SERVER_IP if needed.

set -euo pipefail

# ── Config ─────────────────────────────────────────────────────────────
SERVER_IP="${SERVER_IP:-157.90.195.74}"
IFACE="${IFACE:-any}"   # `any` captures on every interface incl tun0
OUT_DIR="${1:-/tmp/exp_001_$(date +%Y%m%d_%H%M%S)}"

# ── Setup ──────────────────────────────────────────────────────────────
mkdir -p "$OUT_DIR"
PCAP="$OUT_DIR/capture.pcap"
MARKERS="$OUT_DIR/capture.markers"
echo "# HYP-001 NPC tick capture — $(date -Iseconds)" > "$MARKERS"
echo "# server_ip=$SERVER_IP iface=$IFACE" >> "$MARKERS"
echo "# format: <epoch_seconds.us>  <label>" >> "$MARKERS"

# ── Helpers ────────────────────────────────────────────────────────────
mark() {
    # epoch seconds.us + label, atomically appended.
    printf '%s  %s\n' "$(date +%s.%6N)" "$1" >> "$MARKERS"
    echo ">>> marker: $1"
}

prompt() {
    # Wait for Enter. Banner first so it's obvious what to do.
    echo
    echo "=== $1 ==="
    echo "$2"
    read -rp "[press Enter when ready, or 'q' to abort] " r
    if [[ "$r" == "q" ]]; then
        echo "aborted"; exit 1
    fi
}

# ── Pre-flight ─────────────────────────────────────────────────────────
echo "Capture will write to: $OUT_DIR"
echo "Server filter:         host $SERVER_IP"
echo "Interface:             $IFACE"
echo
echo "BEFORE WE START:"
echo "  1. Make sure your retail NC2 client is open at the LOGIN SCREEN."
echo "     (Don't be logged in yet — we want to capture login + everything.)"
echo "  2. Have credentials ready; we'll capture from login forward."
echo "  3. Plan: log in → walk into reactor room → wait for one mob in"
echo "     view → step through phases below."
echo
read -rp "Press Enter to start capture, or Ctrl+C to abort. "

# ── Start dumpcap in background ────────────────────────────────────────
echo "Starting dumpcap (sudo required)..."
sudo dumpcap -i "$IFACE" -f "host $SERVER_IP" -w "$PCAP" \
    -q >/dev/null 2>&1 &
DUMPCAP_PID=$!
trap 'sudo kill "$DUMPCAP_PID" 2>/dev/null || true' EXIT

sleep 1
if ! sudo kill -0 "$DUMPCAP_PID" 2>/dev/null; then
    echo "ERROR: dumpcap failed to start — check sudo + iface + perms"
    exit 1
fi
echo "dumpcap pid=$DUMPCAP_PID, capturing → $PCAP"
mark "CAPTURE_START"

# ── Phase 0: login ─────────────────────────────────────────────────────
prompt "PHASE 0 — Login" \
    "Login to retail now. Select your character, enter the world.
You should land in Plaza P1 or wherever you last logged out.
Press Enter once you're IN-WORLD and HUD is fully drawn."
mark "P0_LOGIN_DONE"

prompt "PHASE 0b — Walk to reactor room" \
    "Walk to the reactor room entrance (Hover Cab to Industrial → reactor,
or however you normally go). Enter the reactor.
Press Enter when you're STANDING INSIDE THE REACTOR ROOM."
mark "P0_AT_REACTOR"

# ── Phase 1: mob idle ──────────────────────────────────────────────────
prompt "PHASE 1 — Mob IDLE (30 s)" \
    "Find ONE mob in line of sight. Stay STILL. Do not move, do not
attack. Just look at it for 30 seconds. We're capturing the
'mob exists, nothing's happening' baseline.
Press Enter to begin the 30s idle window."
mark "P1_IDLE_START"
echo "    [wait 30s — observing idle mob...]"
sleep 30
mark "P1_IDLE_END"

# ── Phase 2: mob walking (patrolling) ──────────────────────────────────
prompt "PHASE 2 — Mob WALKING (30 s)" \
    "Same mob. We want it to MOVE without aggroing. If the mob has a
patrol pattern, watch it walk. If it's stationary, take ONE step
TOWARDS it (just enough to make it turn / start moving but NOT
aggro). Stay still after that. We need 30s of mob-movement-without-
combat.
Press Enter to begin the 30s walk window."
mark "P2_WALK_START"
echo "    [wait 30s — observing mob walking...]"
sleep 30
mark "P2_WALK_END"

# ── Phase 3: aggro / mob attacking ─────────────────────────────────────
prompt "PHASE 3 — Mob AGGRO / ATTACKING (30 s)" \
    "Walk INTO aggro range so the mob attacks you. Do NOT fight back yet
— let it hit you for 30s. We're capturing 'mob in combat, attacking'.
Heal yourself if you must, but DON'T attack the mob.
Press Enter when the mob is actively attacking you."
mark "P3_AGGRO_START"
echo "    [wait 30s — observing mob attacking...]"
sleep 30
mark "P3_AGGRO_END"

# ── Phase 4: killing the mob ───────────────────────────────────────────
prompt "PHASE 4 — KILLING the mob" \
    "Now fight back. Kill the mob. Take as long as you need.
We're capturing the HP-decreasing-toward-zero phase + the death tick.
Press Enter the moment BEFORE you start firing."
mark "P4_KILL_START"
echo "Kill it now — and press Enter THE MOMENT IT DROPS."
read -rp ""
mark "P4_KILL_END"

# ── Phase 5: post-death ────────────────────────────────────────────────
prompt "PHASE 5 — POST-DEATH (30 s)" \
    "Mob is dead. Stay still for 30s. We want to capture the
despawn / corpse-tick / loot-window state."
mark "P5_POSTDEATH_START"
echo "    [wait 30s — observing post-death state...]"
sleep 30
mark "P5_POSTDEATH_END"

# ── Done ───────────────────────────────────────────────────────────────
mark "CAPTURE_END"
echo
echo "Stopping dumpcap..."
sudo kill "$DUMPCAP_PID" 2>/dev/null || true
sleep 1

echo
echo "=== Capture complete ==="
echo "  pcap:    $PCAP  ($(du -h "$PCAP" | cut -f1))"
echo "  markers: $MARKERS"
echo
echo "Next step — run the analyzer:"
echo "  python3 ceres-j/tools/protocol_re/experiments/exp_001_npc_tick/analyze.py \\"
echo "      --pcap '$PCAP' --markers '$MARKERS'"
