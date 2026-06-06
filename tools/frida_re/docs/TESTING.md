# Testing the NC2 client with Frida-in-Wine (Ceres-J and retail)

How to drive the **real Neocron 2 client** under Wine/Proton with a Frida
gadget, against either the local **Ceres-J** server or the **retail**
server, and capture decrypted wire + screenshots + client state.

All client interaction (input, screen capture, state reads) goes through
**Frida inside the Wine process** — never host-side automation tools.

Everything below is verified against the current on-disk install
(2026-06-06).

---

## 0. The pieces (and where they live)

| Piece | Path | Notes |
|---|---|---|
| Game client | `/home/javier/Neocron2/neocronclient.exe` | `$NC2_DIR` = `/home/javier/Neocron2` |
| ASI loader (proxy) | `/home/javier/Neocron2/dinput8.dll` | Ultimate-ASI-Loader; forwards real dinput8, loads `*.asi` |
| Frida gadget | `/home/javier/Neocron2/frida-gadget.asi` | unmodified gadget renamed `.asi` |
| Gadget config | `/home/javier/Neocron2/frida-gadget.config.json` | `listen 127.0.0.1:27042`, `on_load=wait` |
| Agent JS (hooks+RPC) | `frida_re/agent/_agent.js` | pushed by the orchestrator at attach |
| Orchestrator | `python -m orchestrator` (in `frida_re/`) | Linux side; connects to the gadget over loopback |
| Harness driver | `frida_re/scripts/run_pass.py` (`run_pass.sh`) | one-shot: launch→login→capture |
| Proton | `…/compatibilitytools.d/GE-Proton10-34/proton` | auto-detected (newest GE-Proton) |
| venv python | `/home/javier/Documents/Projects/Neocron/bin/python` | override with `NC2_PY` |

**Why inside-Wine, not Linux-side `frida -p`:** running the gadget in the
Win32 process space keeps every address at the Ghidra ImageBase
(`0x00400000`, NC2 has no ASLR), and `Interceptor` runs on the real Win32
thread. See `docs/WINE_SETUP.md` for the rationale.

**Gadget load chain:** Wine loads our `dinput8.dll` (ASI loader) →
on `DllMain` it `LoadLibrary`s every `*.asi` in the game dir →
`frida-gadget.asi` binds `127.0.0.1:27042` and, because
`on_load=wait`, **blocks until the orchestrator connects**. So the
orchestrator must attach for the client to finish starting.

---

## 1. One-time setup (already done on this box)

The gadget files are already installed (dated 2026-05-28). You only need
this on a fresh machine / after a client reinstall:

```bash
cd /home/javier/Documents/Projects/Neocron/ceres-j/tools/frida_re
pip install -r requirements.txt
# Install the ASI-loader + gadget into the game dir.
# (manual: copy launcher-addon/{dinput8.dll,frida-gadget.asi,frida-gadget.config.json}
#  into $NC2_DIR — or install the "frida-instrument" launcher addon.)
cp launcher-addon/dinput8.dll launcher-addon/frida-gadget.asi \
   launcher-addon/frida-gadget.config.json /home/javier/Neocron2/
```

Verify:

```bash
ls -la /home/javier/Neocron2/{dinput8.dll,frida-gadget.asi,frida-gadget.config.json}
```

---

## 2. The easy path — one command per server (`run_pass`)

`run_pass.py` does the whole pass deterministically: kill stale procs →
**point the client at the chosen server** → launch under Proton → wait
for the gadget → attach the orchestrator → wait for the live D3D9 device
→ drive the **ENTER long-login** (`msn3wolf` / `sprlnk2kk`) → walk a few
steps → open inventory → snapshot. Artifacts land in
`frida_re/runs/<run_id>/`.

```bash
cd /home/javier/Documents/Projects/Neocron/ceres-j/tools/frida_re/scripts

# vs Ceres-J (local, docker bridge 172.18.0.3)
./run_pass.sh ceres                 # run_id auto = ceres_<timestamp>
# vs retail (157.90.195.74)
./run_pass.sh retail                 # run_id auto = retail_<timestamp>

# give it an explicit run_id so two runs diff cleanly:
./run_pass.sh ceres  apples_ceres
./run_pass.sh retail apples_retail
```

**Before a Ceres run:** make sure the server is up on the docker bridge:

```bash
cd /home/javier/Documents/Projects/Neocron/ceres-j
docker compose up -d            # ceres + postgres; Ceres must be 172.18.0.3
docker logs -f neocron-server   # watch
```

### What `run_pass` writes — `frida_re/runs/<run_id>/`

| File | Contents |
|---|---|
| `trace.jsonl` | **decrypted** wire (`udp_recv`/`udp_send` with `plain_hex`), input, d3d events |
| `00_menu.png … 04_inventory.png` | D3D9 backbuffer frames at each checkpoint |
| `NN_state.json` | CHARSYS/HUD state snapshots (HP/PSI/STA, position) |
| `marks.log` | timestamped narration of each step |
| `launch.log`, `orch.log` | client + orchestrator stdout |

The login steps live at the bottom of `run_pass.py` (STEP LIST). **Keep
them identical across `ceres` and `retail`** so the two `trace.jsonl`
diff apples-to-apples.

---

## 3. Switching servers (what `run_pass` automates)

The target is just two INI edits (done by `Lifecycle.set_server`):

```
$NC2_DIR/neocron.ini          NETBASEIP = "<ip>:7000"
$NC2_DIR/ini/updater.ini      SERVERIP=<ip>
                              GAMESERVERIP=<ip>
```

- **Ceres-J:** `172.18.0.3`
- **retail:**  `157.90.195.74`

Check the current target:

```bash
grep NETBASEIP /home/javier/Neocron2/neocron.ini
grep -E 'SERVERIP|GAMESERVERIP' /home/javier/Neocron2/ini/updater.ini
```

> Always use the **ENTER long-login** (full server + char select), not
> RESUME. RESUME caches `LastChar`/`LastServer`, which go stale when you
> switch retail↔Ceres and silently send you to the wrong server.

---

## 4. The manual path — interactive session

Use this when you want to pilot the client by hand (or drive it from your
own script) instead of the canned `run_pass` flow.

**Terminal A — launch the client (gadget waits for the orchestrator):**

```bash
cd /home/javier/Documents/Projects/Neocron/ceres-j/tools/frida_re
# set the server first (or edit the INIs as in §3)
scripts/launch_nc2.sh
# Proton starts the client; the gadget binds 127.0.0.1:27042 and blocks.
```

**Terminal B — attach the orchestrator (unblocks startup, starts tracing):**

```bash
cd /home/javier/Documents/Projects/Neocron/ceres-j/tools/frida_re
scripts/attach_orch.sh
# prints the trace path; tails orch.log. Drive RPC by appending to the
# commands file it created (path echoed as /tmp/current_cmd_file):
echo 'ri 0x57 800'  >> /tmp/orch_cmds.txt    # hold 'W' 800ms (RawInput)
echo 'capture'      >> /tmp/orch_cmds.txt    # grab a D3D9 frame
```

Direct orchestrator invocation (what the script runs):

```bash
cd /home/javier/Documents/Projects/Neocron/ceres-j/tools/frida_re
/home/javier/Documents/Projects/Neocron/bin/python -m orchestrator \
    --port 27042 \
    --trace /tmp/frida_nc2_$(date +%s).jsonl \
    --commands /tmp/orch_cmds.txt \
    --duration 1800 -v
```

Interactive RPC (see `docs/CONTROL.md` for the full set):

```
read 0x00400000 16              # read 16 bytes at a runtime VA
call 0x00560090 buf=… seed=…    # invoke the UDP cipher fn
send-udp 03 1f 01 00 25 …       # encrypt + sendto through the cipher path
ri <vk> <ms>                    # RawInput keystroke (login/movement)
```

Input note: NC2 reads via RawInput/DirectInput, so `SendInput`/`WM_CHAR`
are unreliable under Wine — drive keys through the agent's `ri` command
(that is what `harness/session.py` and `Lifecycle.ri_*` use).

---

## 5. Reading the capture

`trace.jsonl` already has **decrypted** payloads (the agent hooks both
sides of the UDP cipher), one JSON object per line. Each `udp_recv`/
`udp_send` carries `direction`, `wire_len`, `plain_len`, `opcode`, and
`plain_hex`. Quick look at what the client received:

```bash
cd frida_re/runs/<run_id>
python3 - <<'PY'
import json, collections
c = collections.Counter()
for ln in open('trace.jsonl'):
    d = json.loads(ln)
    if d.get('ev')=='udp_recv' and d.get('direction')=='s2c' and d.get('decrypt_ok'):
        b = bytes.fromhex(d['plain_hex'])
        if len(b) >= 11 and b[0]==0x13:        # 0x13 app wrapper
            c[(f'{b[7]:02x}', f'{b[10]:02x}')] += 1   # (reliable op, subtype)
for (op,sub),n in c.most_common():
    print(f'op={op} sub={sub}  x{n}')
PY
```

Reliable framing inside the `0x13` wrapper: byte 7 = reliable opcode
(`0x03` reliable / `0x02` ackchan-retransmit), byte 8–9 = seq LE2,
byte 10 = subtype (`0x2c` single CharInfo, `0x07` multipart, …).

Helper scripts: `scripts/trace_diff.py` (diff two runs), `scripts/decode_pos.py`,
`scripts/nc2_state.py`, and the `pcap_*` tools for tcpdump-side captures.

---

## 6. Capturing the wire with a pcap too (optional, server-side truth)

The Frida trace is client-side ground truth. To also capture on the wire
(e.g. to confirm what the server actually emitted), use the docker-bridge
direction-by-IP capture documented in the project notes — Ceres on
`172.18.0.3`, client on `172.18.0.1`. See `ceres-j/tools/pcap-decode.py`
(scapy, decrypts UDP with the LFSR) and `capture-retail.sh`.

---

## 7. Teardown / troubleshooting

```bash
pkill -f neocronclient.exe
pkill -f orchestrator
```

- **Gadget never binds 27042** → ASI loader didn't load. Confirm
  `WINEDLLOVERRIDES` contains `dinput8=n,b` (set by `launch_nc2.sh`) and
  that `dinput8.dll`/`frida-gadget.asi` are in `$NC2_DIR`.
  `ss -tlnp | grep 27042` should show the wine process listening.
- **Client window frozen at startup** → the gadget is in `wait` mode and
  no orchestrator attached. Start the orchestrator (§4 Terminal B).
- **`run_pass` says "no device candidates"** → the D3D9 hook didn't see a
  present; the client may have failed earlier (check `launch.log`).
- **Logged into the wrong server / wrong char** → you used RESUME; redo
  with the ENTER long-login (§3).
- **Stale Wine prefix** → it persists at `~/.cache/nc2_proton_compat`
  (`COMPAT_DATA_PATH`); delete it to force a clean prefix.
```
