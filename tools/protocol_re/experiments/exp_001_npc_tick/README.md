# EXP-001 — NPC tick byte layout (HYP-001)

**Goal:** identify which bytes of the `0x03/0x2d` S→C NPC tick
record encode position, state (idle / walking / aggro / dying),
HP, identity, target. This is the single biggest unknown — 61%
of all session bytes in RETRY3.

**Method:** five-phase labeled retail capture, then phase-aware
byte diff.

## Prerequisites

- Retail NC2 client running and at the login screen.
- `dumpcap` installed (Wireshark CLI).
- `sudo` access for packet capture.
- Python 3 + scapy (already used by other tools in this repo).
- A character that can survive being attacked in the reactor
  room briefly. Bring drugs/medkits if needed.

## Procedure

**Step 1 — run the capture wrapper.**

```bash
cd /home/javier/Documents/Projects/Neocron
ceres-j/tools/protocol_re/experiments/exp_001_npc_tick/capture.sh
```

You'll be prompted at each phase. The script writes a pcap +
markers file to `/tmp/exp_001_<timestamp>/`.

**Step 2 — follow the phase prompts in retail:**

| Phase | What to do | Duration |
|---|---|---|
| 0 | Login, walk to reactor room, get one mob in view | ~however long |
| 1 | Stand still, observe mob doing nothing | 30s auto |
| 2 | Get the mob to walk/patrol WITHOUT aggro (step toward it) | 30s auto |
| 3 | Walk into aggro range, let mob attack you, DON'T fight back | 30s auto |
| 4 | Kill the mob, press Enter the moment it drops | until kill |
| 5 | Mob dead, stand still, observe despawn/loot state | 30s auto |

If you can't find a single mob → that's fine, capture with 2+
and we'll filter by entity-id in the analysis (the script
auto-detects entity-id candidates).

**Step 3 — run the analyzer:**

```bash
python3 ceres-j/tools/protocol_re/experiments/exp_001_npc_tick/analyze.py \
    --pcap /tmp/exp_001_*/capture.pcap \
    --markers /tmp/exp_001_*/capture.markers \
    --dump-bodies /tmp/exp_001_*/bodies.hex
```

Output is a markdown report identifying:
- **Constant bytes** across the whole session (identity / magic).
- **Top phase-discriminator bytes** (offsets where value tracks
  the phase → state enum / HP / target).
- **High-distinct bytes** (likely position floats).
- **Entity-id candidates** (LE16/LE32 that stay constant per
  packet within a phase).

## What success looks like

We expect to identify, at minimum:
1. A 1-byte state field (idle / walk / aggro / dying / dead).
2. 12 bytes of position (3× float32 LE x/y/z).
3. An HP field (1-4 bytes, monotonic-decreasing in phase 4).
4. An entity-id field (constant per mob, 2 or 4 bytes).
5. A target-entity field (zero in idle, your-id in aggro).

If we get all 5, that's ~20 of 55 bytes mapped — promoting
`0x03/0x2d` from 0% to ~40% named, lifting whole-session named
from 7.2% to ~22%.

## After the experiment

1. Add the new decoders to
   `ceres-j/tools/protocol_re/decoders.py`.
2. Move the corresponding rows from
   `docs/protocol/experiments/hypotheses.md` Active → Decoded.
3. Re-run the annotator to confirm `% named` rose.
4. Implement the same byte layout in Ceres-J's
   `NpcDataBroadcast.java` (already 55B but with the wrong
   field placement — that's why mobs don't render).

## Troubleshooting

- **"could not autodetect server IP"** — pass
  `--server-ip 157.90.195.74` to `analyze.py` explicitly.
- **"No 0x03/0x2d samples in captured phases"** — markers
  didn't fire correctly, or no mob was in view. Re-run capture.
- **Capture is huge (>50MB)** — long phase + lots of NPCs
  nearby. Normal in plaza; reactor should be small.
- **dumpcap permission denied** — run
  `sudo setcap cap_net_raw,cap_net_admin=eip $(which dumpcap)`
  once to skip sudo (optional).
