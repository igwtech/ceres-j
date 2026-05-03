# Cash investigation — archived dead-ends (2026-04-26)

The verified findings live in `PROTOCOL.md` under "Character Resource
Updates — verified findings". This file preserves the exploration
that didn't pan out, in case future work needs to know what was tried.

## Hypotheses that turned out wrong

### 1. `0x1f→0x25 19` 5×LE32 snapshot ≠ HUD cash

First retail capture (vendor buy, morning of 2026-04-26) showed a
once-only 25-byte unreliable packet:

```
1f 01 00 25 19  64 bf 04 00  d5 93 06 00  e1 4a 02 00  2d 27 07 00  73 45 02 00
                311140        431509        150753        468269        148851
```

The five LE32 values are credit-magnitude integers (~1e5–5e5). I
assumed one of them was the wallet. **In-vivo test on Ceres-J:**
sent the exact byte format via `!setcash` and `!setcashslot 0..4 N`
sweeps. HUD CASH widget stayed blank across every variation.

**Actual purpose of the 5×LE32 snapshot: still unknown.** Likely
inventory weight totals or a multi-container balance snapshot
(wallet + bank + safe + locker + ?), but the wallet specifically
isn't read from this packet.

### 2. 41-byte `0x03→0x1f→0x25 13` reliable was tested at the WRONG offset

The retail capture had a 41-byte `0x25 13` packet right after the
buy. I treated it as a single opaque blob and tested LE32
substitutions in four trailing-block slots:

```
01 00 25 13   d2 2f 04   e3 15 08 00   d3 2f 18 03   ff ff 14 00   db 05   21 c8 0e 00   0e 0e 4d 3d f7 3e 57 57   d3 44 a3 97 51 c9 1e 00
                                                                                          └─ 16-byte item-receipt block I treated as 4 candidate slots
```

I called these "slot A/B/C/D" and ran `!setcashreceipt A 777777`,
etc. **HUD never moved.** Reason became clear after the Mob-Kill
pcap: the cash field is the **first** LE32 (`e3 15 08 00` = 530,915,
which was the wallet at that moment), not any of the trailing item
slots. The 30 bytes after the cash are the bought-item GUID +
metadata. The buy variant of `0x25 13` is just the cash-update
packet with a 30-byte "what was bought" tail.

### 3. LongPlayerInfo's trailing `04 [LE32]` is NOT cash

The legacy `LongPlayerInfo.java` ended with hardcoded
`04 d3 4b 00 00` (= LE32 19411). The `0x04` tag matched the NC1
cash format from `MessageBuilder.cxx::BuildCharMoneyUpdateMsg`. I
modified the class to emit `pc.getCash()` and tested fresh login +
runtime resend. **Wire bytes confirmed correct (= 444444 LE32) but
HUD stayed blank.** The trailing 0x04+LE32 is some other field —
possibly experience or AA points; identity TBD.

The earlier-suspected `bc 02 00 00` (= 700) field in LongPlayerInfo
is `MODEL_HEAD` — a 3D head model id, not cash.

### 4. The `0x3c` outer attribute-update channel doesn't drive HUD CASH alone

The pcap revealed a 12-byte packet with format
`3c 01 00 [TAG] [LE32 a] [LE32 b]` — a unified attribute-update
channel with tags `0x04`=cash, `0x09`=HP/damage, `0x01`/`0x02`
unknown. I implemented `AttributeUpdate3c.java` with
`!attr3c <tag> <a> <b>` and `!setcash` initially routed through it.
**HUD didn't update** for any tag value. The `0x3c` packet appears
to be a secondary broadcast (foreign-entity attribute update? state
mirror for inspect window?) — the actual HUD-driving cash channel
is the `0x25 13` family in PROTOCOL.md.

### 5. Section 8 size mismatch (39 vs 67) didn't explain it either

Decoded retail's CharInfo Section 8 from the FULL_PCAP multipart
reassembly: 67 bytes vs our 39 bytes. Padded to 67 with retail's
post-cash bytes (`05 00 04 01 00`) replacing legacy
(`00 00 04 04 04`). **HUD still showed 0 at fresh login.** So the
size and immediate post-cash layout aren't the activation key.

## Tooling additions that ARE worth keeping

These tools came out of the investigation and are reusable:

- `tools/pcap-decode.py` — scapy-based pcap reader, decrypts UDP
  with the LFSR cipher, dumps TCP raw. Handles the gap that strace
  was filtering out (TCP entirely + occasional UDP packets).
- `tools/capture-retail.sh` — uses `sudo dumpcap` fallback when
  `tcpdump` is missing; auto-screenshots the game window on each
  marker call so we have HUD-value evidence per event.
- `tools/scan_cash_timed.py` — time-windowed pcap search for known
  LE32 values (deltas + balances) within ±N seconds of a marker.
- `tools/find-rare-subs.py`, `tools/timeline-subs.py` — sub-packet
  histograms and chronological views.

## Lessons learned

1. **HUD-screenshot ground truth is non-negotiable.** The breakthrough
   came when we knew exactly: "cash was 527,652; one mob kill later
   it became 527,729". A grep for `71 0d 08 00` in a 1-second window
   returned exactly ONE packet. Without that ground truth, the cash
   value was indistinguishable from hundreds of unrelated LE32s in
   the traffic.
2. **Vendor transactions are NOT the cleanest cash event.** The
   `0x25 13` family carries cash + item-receipt as one packet during
   a buy, which we initially mis-parsed as a single blob. Mob kills
   produced the 11-byte standalone form that made the format obvious.
3. **Strace was lying about TCP** — `-e recvfrom,sendto,recvmsg,sendmsg`
   filters out `recv`/`send` (no suffix), which TCP uses. We'd been
   doing UDP-only analysis on a mixed protocol for weeks. The pcap
   approach surfaces all traffic.
4. **`parse-burst.py`'s outer-type table was incomplete** — `0x3c`
   was actually present in our strace logs from earlier captures, we
   just didn't recognize it because the tool printed "0x3c/unk".
   The user's hypothesis "we might be missing packets" was partially
   right — even when bytes ARE captured, an analyzer that doesn't
   name them looks blind.
