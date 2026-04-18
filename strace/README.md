# Strace captures

Raw `strace -f -e trace=recvmsg,sendmsg,recvfrom,sendto,bind,connect,socket
-s 4096 -xx -tt -yy` captures of the NC2 retail client against retail
servers and against Ceres-J, used to diagnose the
"Connection to worldserver failed" timeout. Keep; they're the primary
ground-truth source for the investigation.

**These files are gitignored** (`*.log` in the root `.gitignore`).

## Files

### Retail baselines

Captured against the official retail server at `157.90.195.74:7000`.
Full login + in-world walk + clean exit.

| File | Size | Account | Character |
|---|---|---|---|
| `nc2_strace_RETAIL_ACC1_CHAR1.log` | 153 MB | 1 | 1 |
| `nc2_strace_RETAIL_ACC1_CHAR2.log` | 178 MB | 1 | 2 |
| `nc2_strace_RETAIL_ACC2_CHAR1.log` | 182 MB | 2 | 1 |
| `nc2_strace_RETAIL_ACC2_CHAR2.log` | 173 MB | 2 | 2 |

The four retail captures are the reference for what a healthy login
looks like on the wire. All 4 show identical patterns:

- Overall entropy ~7.99 bits/byte (encrypted, per the decoding attempts
  in `../tools/decode-retail-packets.py`)
- 88–146 UDP S→C packets (diverse sizes, 446B dominant for gamedata bulk)
- 64–72 UDP C→S packets (40B, 16B, 84B, 28B, 14B — diverse stimulus)

### Ceres-J captures

Captured against the local Ceres-J docker container at `127.0.0.1:7000`.

| File | Size | Context |
|---|---|---|
| `nc2_strace_CERESJ.log` | 157 MB | Baseline before tonight's session. Pre-HandshakeUDPAnswer2 500ms cooldown fix — this one has multiple WorldEntryEvent bursts because the old code re-scheduled on every incoming handshake. Useful as a "what the burst looks like" reference. |
| `nc2_strace_CERESJ_OPTB.log` | 246 MB | Option B-1 run. `NetHostWorldName` raw `0x83 0x0c` UDP packet was sent. 13 S→C packets received by client, 3 C→S handshakes only. Strace captured the `\x83\x0c\x00\x00...` bytes arriving at the client, proving the kernel delivered it — but state machine did not advance. |
| `nc2_strace_CERESJ_OPTB2.log` | 147 MB | Option B-2 run. `SyncResponse` inner-`0x03` 13-byte payload wrapped in `0x13 → 0x03`. Same result — 13 S→C, 3 C→S handshakes. State 2 doesn't process sync responses (requires state 3 or 6). |
| `nc2_strace_CERESJ_OPTB3.log` | 176 MB | Option B-3 run. `WorldInfoSrv` `0x19 0x04` "New World info" (30 bytes). Same result. Wrong target field and probably wrong wire framing layer. |

## Why the Ceres-J captures are so large (~150 MB)

Each capture contains the full strace output from Wine's multiprocess
client — every syscall from every thread, not just UDP. Each test is
only ~30–60 seconds of wall clock but produces hundreds of megabytes.

The signal-to-noise ratio is low. Use the analyzer tools in
`../tools/` rather than opening these files directly:

- `tools/analyze-retail-packets.py <file>` — entropy/histogram stats
- `tools/decode-retail-packets.py <file>` — try to decrypt via
  PacketObfuscator (spoiler: it doesn't work, entropy proof that
  retail uses a different cipher)
- `tools/compare-packet-sizes.py <f1> <f2> ...` — diff packet size
  distributions

## Running a fresh capture

```bash
# Point neocron.ini at target:
#   NETBASEIP = "127.0.0.1:7000"   (local Ceres-J)
#   NETBASEIP = "157.90.195.74:7000"  (retail)

cd /home/javier/Documents/Projects/Neocron/ceres-j
./tools/debug-client-strace.sh        # starts the client under strace,
                                      # output at /tmp/nc2_strace.log

# After the test completes, rename into this directory:
mv /tmp/nc2_strace.log strace/nc2_strace_<LABEL>.log
```

Where `<LABEL>` is something like `CERESJ_YYYYMMDD_HHMM` or
`RETAIL_ACCx_CHARy` or `CERESJ_OPTc_N` for option experiments.

## See also

- `../docs/progress_login_timeout.md` — full investigation log of what
  each capture told us and what hypotheses it confirmed/falsified.
- `../docs/PROTOCOL.md` — wire protocol reference.
- `../tools/ghidra/` — Ghidra headless scripts for the client binary.
