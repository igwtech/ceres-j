# Packet-flow timelines

`tools/pcap_timeline.py` turns a Neocron 2 pcap into a **fully-parsed,
time-ordered packet-exchange timeline** — rendered as a Mermaid
`sequenceDiagram` (default) or a plain-text trace. It is the visual
counterpart to the statistical catalog produced by `catalog_extract.py`.

It decodes the entire protocol stack and names each packet from the
reverse-engineering catalog, so you can *see* the order packets are
exchanged in (login handshakes, world-entry burst, steady-state loops):

```
UDP wire cipher (LFSR/CFB)              ← shared with decrypt-retail.py
  └─ UDP transport  (0x01/0x04/0x08/0x0b…)
      └─ 0x13 gamedata mux  (counter + length-prefixed sub-packets)
          └─ 0x03 reliable  (seq + sub-type)
              ├─ 0x1f GamePackets → act-tag  (0x3d FramePoll, 0x17 Use, …)
              │     └─ 0x25 StateAck → sub-tag (0x04 Cash, 0x22 PoolMaxes, …)
              └─ 0x07 multipart → disc (0x01 CharInfo, 0x02 CharsysInfo)
TCP 0xFE frames  (0x8480 Auth, 0x8305 UDPServerData, 0x830c Location, …)
```

The cipher comes from `decrypt-retail.py` and the gamedata sub-packet
walk from `parse-burst.py`, so the timeline stays byte-for-byte in sync
with the rest of the tooling.

## Usage

```bash
cd ceres-j

# Mermaid timeline of the login + world-entry window (legible, renderable)
python3 tools/pcap_timeline.py -i strace/<cap>.pcap --until 8 > flow.md

# Plain-text trace of the whole capture + packet-type totals
python3 tools/pcap_timeline.py -i strace/<cap>.pcap --format text --max 0 --summary

# Zoom into a time window (e.g. a zone cross around t+40s)
python3 tools/pcap_timeline.py -i strace/<cap>.pcap --since 38 --until 46

# Every packet, no flood-collapsing (verbose)
python3 tools/pcap_timeline.py -i strace/<cap>.pcap --no-collapse --udp-only
```

Render Mermaid with mermaid-cli, or paste into any Markdown viewer:

```bash
/tmp/mermaid-tool/node_modules/.bin/mmdc -i flow.md -o flow.png -s 2
```

### Options

| flag | meaning |
|------|---------|
| `-i, --input` | pcap to parse (required) |
| `--server-ip` | override server-IP autodetect (TCP 7000/12000, else busiest non-local peer) |
| `--proto {both,tcp,udp}` | restrict to one transport |
| `--format {mermaid,text,both}` | output format (default `mermaid`) |
| `--max N` | cap timeline rows/arrows, `0` = all (default `300`) |
| `--no-collapse` | show every packet instead of merging consecutive identical runs |
| `--since` / `--until` | relative-time (s) window filter |
| `--summary` | append a packet-type totals table |

### Collapsing

Steady-state play floods the wire (≈50 movement / frame-poll packets
per second). By default the timeline **merges consecutive same
direction+type packets** into a single arrow annotated `×count over
Δt`, so a 2-minute capture stays readable. Use `--no-collapse` for the
raw per-packet view.

## Example artifacts

- [`login_worldentry_NORMAN.md`](login_worldentry_NORMAN.md) — Mermaid
  diagram of the retail 3-stage login → UDP handshake → world-entry
  burst (from `strace/nc2_strace_RETAIL_NORMAN_20260426_200458.pcap`).
- [`NORMAN_full.txt`](NORMAN_full.txt) — full plain-text trace of the
  same session with packet-type totals.

These are regenerable; re-run the commands above against any capture in
`strace/`.
