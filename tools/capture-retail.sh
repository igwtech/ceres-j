#!/usr/bin/env bash
# Capture a retail Neocron 2 session under strace + (optionally) tcpdump.
#
# Usage:  ./capture-retail.sh <scenario>
#
# Examples:
#   ./capture-retail.sh CASH_VENDOR_BUY
#   ./capture-retail.sh FALL_DAMAGE_JUMP
#
# Outputs (in $OUT_DIR, default ceres-j/strace/):
#   nc2_strace_RETAIL_<SCENARIO>_<TS>.log    — strace recv/send (analysis pipeline input)
#   nc2_strace_RETAIL_<SCENARIO>_<TS>.pcap   — tcpdump on tun0, filtered to retail server
#   nc2_strace_RETAIL_<SCENARIO>_<TS>.markers — per-event timestamps you wrote
#
# Marker workflow: in a SECOND terminal, while the client is running, run:
#   ./mark.sh <event_label>
# (the script prints a one-liner you can paste). Each marker line has
# microsecond precision and matches strace's -tt output exactly.

set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <scenario>"
    echo "  e.g. $0 CASH_VENDOR_BUY"
    exit 1
fi

SCENARIO="${1^^}"          # uppercase
TS="$(date +%Y%m%d_%H%M%S)"
TAG="RETAIL_${SCENARIO}_${TS}"

PROTON=/home/javier/.local/share/neocron-launcher/proton/GE-Proton10-34
WINE="$PROTON/files/bin/wine"
export WINEPREFIX=/home/javier/.local/share/neocron-launcher/prefix/pfx
export WINEARCH=win64
export WINEDLLOVERRIDES="quartz=n,b"
export WINEDEBUG="err-all,fixme-all"

GAME_DIR=/home/javier/Neocron2
GAME_EXE=neocronclient.exe

OUT_DIR="$(dirname "$(readlink -f "$0")")/../strace"
mkdir -p "$OUT_DIR"
STRACE_LOG="$OUT_DIR/nc2_strace_${TAG}.log"
PCAP="$OUT_DIR/nc2_strace_${TAG}.pcap"
MARKERS="$OUT_DIR/nc2_strace_${TAG}.markers"

# Retail server (per memory cipher_cracked.md): 157.90.195.74 UDP 5005.
# Capture all traffic to/from that host — covers TCP login + UDP gamedata.
RETAIL_HOST=157.90.195.74
IFACE=tun0

# Set up marker helper for the sidecar terminal — each mark records a
# timestamp AND captures a screenshot of the game window so we have
# visual evidence (HUD values, dialog state) tied to each event.
MARK_HELPER="/tmp/nc2_mark.sh"
SHOT_DIR="$OUT_DIR/${TAG}_shots"
mkdir -p "$SHOT_DIR"
cat > "$MARK_HELPER" <<EOF
#!/usr/bin/env bash
# usage: nc2_mark.sh <label>
LABEL="\${1:-MARK}"
TS=\$(date '+%H:%M:%S.%6N')
SAFE_LABEL=\$(echo "\$LABEL" | tr -c 'A-Za-z0-9_-' '_')
SHOT="$SHOT_DIR/\${TS}_\${SAFE_LABEL}.png"
echo "\$TS  \$LABEL" >> "$MARKERS"
# Capture the Neocron Evolution window (X11 via XWayland)
WID=\$(xdotool search --name "Neocron Evolution" 2>/dev/null | head -1)
if [[ -n "\$WID" ]]; then
    import -window "\$WID" "\$SHOT" 2>/dev/null && \
        echo "marked + shot: \$LABEL → \$SHOT" || \
        echo "marked (shot failed): \$LABEL"
else
    echo "marked (no window): \$LABEL"
fi
EOF
chmod +x "$MARK_HELPER"
: > "$MARKERS"

cleanup() {
    echo
    echo "── stopping captures ──"
    if [[ -n "${TCPDUMP_PID:-}" ]] && kill -0 "$TCPDUMP_PID" 2>/dev/null; then
        sudo kill -INT "$TCPDUMP_PID" 2>/dev/null || true
        wait "$TCPDUMP_PID" 2>/dev/null || true
    fi
    echo
    echo "── outputs ──"
    [[ -s "$STRACE_LOG" ]]   && ls -lh "$STRACE_LOG"
    [[ -s "$PCAP" ]]         && ls -lh "$PCAP"
    [[ -s "$MARKERS" ]]      && { echo "Markers:"; cat "$MARKERS"; }
    echo
    echo "── next steps ──"
    echo "Decrypt outgoing (C->S):"
    echo "  python3 $(dirname "$0")/decrypt-retail.py -d send '$STRACE_LOG' | less"
    echo "Decrypt incoming (S->C):"
    echo "  python3 $(dirname "$0")/decrypt-retail.py -d recv '$STRACE_LOG' | less"
    echo "Sub-packet structure:"
    echo "  python3 $(dirname "$0")/parse-burst.py '$STRACE_LOG' | less"
    if [[ -s "$PCAP" ]]; then
        echo "Wireshark:"
        echo "  wireshark '$PCAP' &"
    fi
}
trap cleanup EXIT INT TERM

# Pre-flight clean: drop old client logs so anything in ~/Neocron2/logs
# is from THIS session
find "$HOME/Neocron2" -name "*.log" -delete 2>/dev/null || true

echo "════════════════════════════════════════════════════════════════"
echo " Retail capture: $TAG"
echo "════════════════════════════════════════════════════════════════"
echo "  strace    → $STRACE_LOG"
echo "  pcap      → $PCAP"
echo "  markers   → $MARKERS"
echo
echo "  In a SECOND terminal, mark each test event (each mark"
echo "  auto-screenshots the game window so we capture HUD values):"
echo "    $MARK_HELPER PRE_KILL          # before mob kill, HUD CASH visible"
echo "    $MARK_HELPER POST_KILL         # after kill, HUD CASH should jump"
echo "    $MARK_HELPER PRE_DEPOSIT       # before CityCom deposit"
echo "    $MARK_HELPER POST_DEPOSIT      # after deposit"
echo "    $MARK_HELPER NPC_DIALOG_OPEN   # job-NPC dialog"
echo
echo "  Screenshots → $SHOT_DIR/"
echo "  Stop capture: close the game OR ctrl+c here."
echo "════════════════════════════════════════════════════════════════"

# Start packet capture. Prefer tcpdump if installed; otherwise use
# dumpcap (ships with wireshark, has cap_net_raw set but only group
# `wireshark` can run it without sudo). Either way we need sudo if the
# user isn't in the wireshark group. Filter is broad ("any non-local
# host") so we don't miss TCP traffic to peers we haven't profiled.
TCPDUMP_PID=""
CAPTURE_TOOL=""
if command -v tcpdump >/dev/null 2>&1; then
    CAPTURE_TOOL="tcpdump"
elif [[ -x /usr/bin/dumpcap ]]; then
    CAPTURE_TOOL="dumpcap"
fi

if [[ -n "$CAPTURE_TOOL" ]]; then
    echo "[*] starting $CAPTURE_TOOL on $IFACE (sudo)…"
    if sudo -n true 2>/dev/null || sudo -v; then
        case "$CAPTURE_TOOL" in
            tcpdump)
                sudo tcpdump -i "$IFACE" -U -s 0 -w "$PCAP" \
                    "not (host 127.0.0.1 or net 192.168.0.0/16 or net 172.16.0.0/12 or net 10.0.0.0/8) or host $RETAIL_HOST" \
                    >/tmp/nc2_tcpdump.stderr 2>&1 &
                ;;
            dumpcap)
                sudo /usr/bin/dumpcap -i "$IFACE" -s 0 -w "$PCAP" \
                    -f "not (host 127.0.0.1 or net 192.168.0.0/16 or net 172.16.0.0/12 or net 10.0.0.0/8) or host $RETAIL_HOST" \
                    >/tmp/nc2_tcpdump.stderr 2>&1 &
                ;;
        esac
        TCPDUMP_PID=$!
        sleep 0.8
        if ! kill -0 "$TCPDUMP_PID" 2>/dev/null; then
            echo "[!] $CAPTURE_TOOL exited immediately. stderr:"
            cat /tmp/nc2_tcpdump.stderr
            TCPDUMP_PID=""
        else
            echo "[*] $CAPTURE_TOOL pid=$TCPDUMP_PID → $PCAP"
        fi
    else
        echo "[!] no sudo — skipping packet capture (strace only)"
    fi
else
    echo "[!] neither tcpdump nor dumpcap found — skipping packet capture"
fi

cd "$GAME_DIR"
echo "[*] launching $GAME_EXE under strace…"
echo
strace -f -e trace=recvfrom,sendto,recvmsg,sendmsg,bind,connect,socket \
    -s 4096 -xx -tt -yy \
    -o "$STRACE_LOG" \
    "$WINE" "$GAME_EXE" || true
