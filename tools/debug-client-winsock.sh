#!/usr/bin/env bash
# Launch neocronclient.exe through the launcher's Proton runtime with
# WINEDEBUG=+winsock tracing enabled. Output goes to
# /tmp/nc2_winsock.log — every Winsock API call the client makes will be
# logged there (recvfrom / sendto / bind / socket / etc.).
#
# Use this to verify that server-sent UDP packets are actually reaching
# the client's user space and to see what recvfrom() returns. This
# complements tcpdump (which shows the wire) by showing what the client
# application sees.

set -euo pipefail

PROTON=/home/javier/.local/share/neocron-launcher/proton/GE-Proton10-34
WINE="$PROTON/files/bin/wine"
export WINEPREFIX=/home/javier/.local/share/neocron-launcher/prefix/pfx
export WINEDLLOVERRIDES="quartz=n,b"
export WINEARCH=win64
export WINEDEBUG="+winsock,+ws2_32,fixme-all,err-all"

GAME_DIR=/home/javier/Neocron2
GAME_EXE=neocronclient.exe
LOG=/tmp/nc2_winsock.log

echo "Launching $GAME_EXE through $WINE"
echo "Prefix: $WINEPREFIX"
echo "Log:    $LOG"
echo ""
echo "Log will grow quickly; press Ctrl+C in this terminal to stop the client."
echo ""

cd "$GAME_DIR"
exec "$WINE" "$GAME_EXE" 2> "$LOG"
