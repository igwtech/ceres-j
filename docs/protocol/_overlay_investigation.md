# SYNCHRONIZING Overlay Hang — Root Cause Analysis

**Status:** investigation-in-progress  
**Date:** 2026-05-09  
**Captured session:** Asd / msn4wolf, zone 5 (pepper_p1) → 6 (pepper_p2)

## Symptom

Modern NCE 2.5 client (running on Linux/Wine) connects, completes
the TCP handshake, completes the UDP 3-way handshake, receives the
full WorldEntry burst, and starts moving — but the
"SYNCHRONIZING INTO CITY ZONE" overlay never clears. The client
sends `0x08 AbortSession` after exactly 21 seconds.

## Retail vs Ceres-J — what's different at IN_WORLD time

Reference capture: `RETAIL_DRSTONE4_20260501_193336.pcap`.
IN_WORLD marker fires at t=24.36 (overlay clears).

The retail flow has a **request/response retransmit channel**
keyed on packet types `0x01` (C→S) and `0x02` (S→C):

```
t=22.69  S→C 0x03/0x2c StartPos × 2 (71B + 810B)        ← initial burst
         S→C 0x03/0x23 InfoResponse (6B)
         S→C 0x03/0x0d TimeSync (12B)
         S→C 0x03/0x33 ChatList (2B)
         S→C 0x03/0x23 InfoResponse (10B)
         S→C 0x03/0x1f GamePackets (5B)
         S→C 0x03/0x1b PosUpdate × 24 (other players)
         S→C 0x1b × 30+ (NPC raw broadcasts)
         S→C 0x03/0x2d NPCData

t=23.21  C→S 0x01 0x0100, 0x0200, ..., 0x1100    ← 17× ACK requests
t=23.28  S→C 0x03/0x25 PlayerInfo (64B)
         S→C 0x02 0x0201, 0x0202, ..., 0x0211    ← 16× retransmits
         S→C 0x03/0x1f (final init burst)

t=23.46  C→S 0x01 × 17   (second round of ACK requests)
t=23.50  S→C 0x02 × 16   (second round of retransmits)

t=24.23  S→C 0x03/0x1f (5B `0100252329`)   ← state-machine flip trigger
         S→C 0x03/0x2d NPCData (54B)

t=24.36  *** IN_WORLD — overlay clears ***
```

## What Ceres-J does wrong

### Bug #1 — C→S 0x01 routes to HandshakeUDP

`GamePacketReaderUDP.decode()` line 62:
```java
case 0x01:
    return new HandshakeUDP(dp);
```

This was correct *during* the UDP 3-way handshake (when the client
sends 10-byte 0x01 packets carrying the interface ID). After
WorldEntry, retail clients send 3-byte 0x01 ACK requests
(`0x01 [seq_lo] 0x00`) to fetch the next packet in the 0x02 stream.
Ceres-J incorrectly re-runs the handshake answer — `skip(9)` on a
3-byte buffer returns 3, `read()` returns -1, `setInterfaceId(-1)`
silently no-ops, but `HandshakeUDPAnswer.execute()` **still sends
a spurious UDPAlive**.

**Fix:** dispatch by packet length and post-handshake state:
- pre-handshake (`!handshakingState && size >= 10`): HandshakeUDP
- post-handshake (`handshakingState && size <= 12`): new
  `ReliableAckRequest` handler that triggers a corresponding
  S→C 0x02 retransmit.

### Bug #2 — No S→C 0x02 retransmit-on-demand

Ceres-J sends a few `0x02`-wrapped packets at WorldEntry time
(`InitInfoResponse02`, `InitWeather02`, `InitUpdateModel02`,
`InitSoullight02`) and that's it. The client expects the server
to maintain a per-session 0x02 stream that it can request packets
from by sequence number via 0x01 ACKs.

In retail every session shows two synchronized C→S 0x01 / S→C 0x02
exchanges of ~17 sequence numbers each, just before IN_WORLD.

**Fix:** introduce a `ReliablePacketStream` that:
- Maintains a per-player ring buffer of recent 0x02-wrapped
  outgoing packets, keyed by 16-bit sequence.
- On C→S 0x01 with seq N, retransmits 0x02 with the same seq
  N's payload. (Or sends `0x04` if the sequence isn't in the
  buffer — `0x04` C→S has 5 hits in DRSTONE4 capture.)
- The 0x02 stream is what carries player-info, npc-info, and
  state-init data the client absolutely needs to advance state
  3/6 → 4 (overlay clear).

### Bug #3 — Multipart CharInfo vs single 0x03/0x2c StartPos

Retail captures show CharInfo delivered as **two single-packet
0x03/0x2c** sends (71B + 810B), not multipart 0x03/0x07. Ceres-J's
WorldEntryEvent emits a single multipart 0x03/0x07 stream with
discriminator 0x01 (CharInfo) + 0x02 (CharsysInfo).

The `nc2_tutorial_shard_finding.md` memory says CharInfo channel is
size-based: ≤900B → single 0x03/0x2c; >900B → multipart 0x03/0x07.
But retail's 810B sample is sent as 0x03/0x2c, and our payload is
~430B which would also fit single. We may be unnecessarily
multiparting and triggering the multipart-reassembly state which
doesn't satisfy the same client-side state-machine path.

**Action:** confirm whether using `0x03/0x2c` single (≤900B) closes
the overlay independently of bugs #1/#2.

## Recommended order

1. **Bug #3 first** (small change, immediate test): make CharInfo
   send via 0x03/0x2c single when payload ≤900B; only fall back
   to multipart 0x03/0x07 above 900B. Test login.
2. **Bug #1 + #2 together** (real fix for the post-WorldEntry
   protocol gap): split the 0x01 dispatcher and add the 0x02
   retransmit ring.

## Open questions

- What does `0x04` C→S signal exactly? 5 hits in DRSTONE4 — possible
  "no-such-packet" reply paired with 0x01.
- Are the 16 retransmitted 0x02 packets identical content per
  session, or do they reflect player state at retransmit time?
  (Determines whether they're just buffered initial sends or
  re-rendered fresh.)
- The retail captures show C→S 0x01 also fires later in normal
  gameplay (not just at world-entry). Same retransmit semantics?
