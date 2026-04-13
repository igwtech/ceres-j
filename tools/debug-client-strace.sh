#!/usr/bin/env bash
# Launch neocronclient.exe under strace to capture every UDP recv/send
# syscall the client makes — with FULL buffer contents as hex.
#
# strace shows the bytes at the syscall level, bypassing Wine's Winsock
# trace layer. This gives us a ground-truth record of every byte the
# client read from or wrote to its UDP sockets, including payload
# fragments we cannot see from the tcpdump alone (where retail S→C is
# encrypted, or where we want to correlate client-side timing with
# server-side sends).
#
# Output: /tmp/nc2_strace.log (grows fast; ctrl+c to stop)

set -euo pipefail

PROTON=/home/javier/.local/share/neocron-launcher/proton/GE-Proton10-34
WINE="$PROTON/files/bin/wine"
export WINEPREFIX=/home/javier/.local/share/neocron-launcher/prefix/pfx
export WINEDLLOVERRIDES="quartz=n,b"
export WINEARCH=win64
export WINEDEBUG="err-all,fixme-all"

GAME_DIR=/home/javier/Neocron2
GAME_EXE=neocronclient.exe
LOG=/tmp/nc2_strace.log

echo "Launching $GAME_EXE under strace"
echo "Log: $LOG (syscalls: recvfrom, sendto, recvmsg, sendmsg)"
echo ""

cd "$GAME_DIR"
# -f follow forks (wine spawns child processes)
# -e trace=... only relevant syscalls
# -s 4096 show up to 4KB of buffer content
# -xx hex-dump non-printable
# -tt relative timestamps with microsecond precision
# -yy show socket details (addresses, protocols)
exec strace -f -e trace=recvfrom,sendto,recvmsg,sendmsg,bind,connect,socket \
    -s 4096 -xx -tt -yy \
    -o "$LOG" \
    "$WINE" "$GAME_EXE"
