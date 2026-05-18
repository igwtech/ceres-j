#!/usr/bin/env bash
# capture-ceres-loopback.sh — capture the LIVE Ceres-J <-> client UDP
# datagrams on loopback so the "@WWORLDMGR : Corrupted Message Type:15,
# Size:21" offending datagram can be decoded byte-exactly.
#
# WHY THIS EXISTS (task #193): the ClientFrameDecoder harness proves
# every Ceres NPC/zone-state/world-entry emitter frames correctly
# (no dequeued message has body[0]==0x0F; see
# src/test/java/server/networktools/NpcZoneStateNoType15Test.java).
# The retail capture (strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap)
# has exactly one 21-byte S->C message — w=0x03 op=0x1f body
# "1f 01 00 18 d5 01 00 00 74 a1 88 5f 00 00 00 00 4e 43 50 44 00"
# ("...NCPD\0"), body[0]=0x1f, a valid Type. So the Type-15 offending
# emit is on a path the unit fixture cannot drive — it must be
# captured live and decoded.
#
# USAGE (server + client both on 127.0.0.1):
#   1. Start Ceres-J locally (docker compose up) and the NC2 client
#      pointed at 127.0.0.1.
#   2. In a terminal:  sudo ./tools/capture-ceres-loopback.sh PLAZA_SIT_NPC
#   3. Log in, walk to a zone with scripted NPCs (plaza_p1/p3), sit
#      near an NPC for ~30 s (reproduce the client log
#      "@WWORLDMGR : Corrupted Message Type:15, Size:21"), then Ctrl-C.
#   4. Decode:
#        python3 tools/pcap-decode.py <out.pcap>
#        python3 tools/npc-lifecycle.py -i <out.pcap> \
#                --server-ip 127.0.0.1 --dir s2c \
#            | grep -E 'op=0x0f|len= *21'
#      The 21-byte S->C sub whose dequeued body[0]==0x0F (or the
#      multi-sub datagram whose declared subLen lands the next sub on
#      0x0F) is the offending emitter — feed its exact bytes back into
#      a ClientFrameDecoder test to pin and fix it non-speculatively.
#
# Loopback note: NC2 uses per-zone UDP ports (commonly 5004/5008/12000)
# plus the per-session source port the server allocates. We capture ALL
# loopback UDP and let pcap-decode.py (LFSR cipher) sort it out, rather
# than guessing a port filter and missing the zone the bug fires in.

set -euo pipefail

SCENARIO="${1:-CERES_LOOPBACK}"
SCENARIO="${SCENARIO^^}"
TS="$(date +%Y%m%d_%H%M%S)"
OUT_DIR="${OUT_DIR:-strace}"
OUT="${OUT_DIR}/nc2_ceres_${SCENARIO}_${TS}.pcap"
MARK="${OUT_DIR}/nc2_ceres_${SCENARIO}_${TS}.markers"

mkdir -p "$OUT_DIR"

if [[ $EUID -ne 0 ]]; then
    echo "Run as root (raw loopback capture): sudo $0 $SCENARIO" >&2
    exit 1
fi

# Prefer dumpcap (drops privileges cleanly); fall back to tcpdump.
CAP_BIN=""
if command -v dumpcap >/dev/null 2>&1; then
    CAP_BIN="dumpcap"
elif command -v tcpdump >/dev/null 2>&1; then
    CAP_BIN="tcpdump"
else
    echo "Need dumpcap or tcpdump installed." >&2
    exit 1
fi

echo "[*] Scenario : $SCENARIO"
echo "[*] Output   : $OUT"
echo "[*] Markers  : $MARK   (echo a label into this file to timestamp)"
echo "[*] Capturing ALL loopback UDP (lo) — Ctrl-C to stop."
echo "[*] Reproduce the client log:"
echo "      @WWORLDMGR : Corrupted Message Type:15, Size:21"
echo "    (zone into plaza_p1/p3, sit near a scripted NPC ~30 s)."

date +%s.%N > "$MARK"
echo "capture_start $SCENARIO" >> "$MARK"

if [[ "$CAP_BIN" == "dumpcap" ]]; then
    exec dumpcap -i lo -f "udp" -w "$OUT"
else
    exec tcpdump -i lo -n -s 0 -U -w "$OUT" "udp"
fi
