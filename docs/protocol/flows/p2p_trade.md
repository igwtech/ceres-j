# Flow: Player-to-player trade attempt (LE-chip blocked)

**Status:** partial — trade attempt was REJECTED by server (LE chips active)  
**Backing capture:** `RETAIL_CREATION_LEVELING_LONG_20260502_160841`
— marker `OUTSIDE_AREAM5_TRADING_PLAYER` (t=2014.91s).

## Scenario

The player tried to initiate a trade with another player AND
attempted PvP combat. Both players had Law Enforcer (LE) chip
implants active, which on the retail server makes both PvP
damage and direct trades through the world fail server-side.

This means the capture shows the **request side** of P2P trade
and PvP, but **not a successful trade or damage exchange**.
That's still useful evidence — it pins down the request packets
even if the response is a rejection rather than a commit.

## What's in the capture

A single 6-byte packet on a raw outer `0x2d` fires at
t=2016.10 (just after the marker):

```
C→S 0x2d ? — 6B body: "2d 02 00 00 00 3f"
```

This is the only `UDP C→S 0x2d` packet in the entire 12-capture
corpus across the prior catalog. The fact that it ONLY fires
during the trade attempt strongly suggests it's the **trade
request packet**.

## Tentative format

```
Offset  Size  Field         Sample value
0x00    1     0x2d          opcode (raw outer)
0x01    1     0x02          ?
0x02    4     ?             0x00 0x00 0x00 0x3f
```

The `0x3f` at offset 5 is suggestive but not enough samples to
nail the layout.

## Sequence diagram (best-known)

```mermaid
sequenceDiagram
    autonumber
    participant A as Player A (us)
    participant U as GameServer
    participant B as Player B (target)

    Note over A,B: Player A right-clicks Player B, picks "Trade".
    A->>U: 0x2d ? 6B "2d 02 00 00 00 3f" — trade request

    Note over A,B: In retail, server checks LE-chip status.
    Note right of U: LE chip active on either side → reject.

    U-->>A: ??? — rejection notification (not isolated)
    Note over A,U: No trade window opens; capture shows no follow-up<br/>distinct packets in the marker window.
```

## What we'd need to verify a successful trade

A capture of two players WITHOUT LE chips, trading items.
Markers:
- `TRADE_REQUEST_SENT` — A→B trade request
- `TRADE_REQUEST_ACCEPTED` — B accepts
- `TRADE_ITEM_OFFERED_A` / `_B` — items put in trade window
- `TRADE_LOCK_A` / `_B` — both press Lock
- `TRADE_CONFIRM` — items + cash exchanged
- `TRADE_COMPLETE`

This would let us:
1. Decode the `0x2d` request byte format fully.
2. Identify the trade-window protocol (likely a CityCom-DCB-style
   RPC with `Trade*` method names, given how mail and CityCom work).
3. Find the commit/atomicity protocol — does the server use a
   2-phase commit on item swap?

## PvP attempt (also blocked by LE chip)

The user reports trying to shoot the other player. That should
have produced:
- C→S weapon-fire packet (currently unidentified — likely a
  `0x03/0x1f` sub-tag) — but this should fire even if damage is
  rejected
- Server response indicating "no damage" or a friendly-fire
  error — also not isolated

The capture has `PULLOUT_SLOT1_RIFLE` and `RELOAD_RIFLE` markers
just before the PvP attempt. The bulk of the firing happens at
unspecified moments. Identifying the exact "fire" packet still
requires a paired capture: same player firing at PvE target (mob)
vs PvP target with LE chip — the diff isolates the request.

## Open questions

- **`0x2d` byte 5 = `0x3f`.** Could be a target entity ID (`63`
  decimal), an option enum, or unrelated.
- **Why only 6 obs of `0x2d` across all captures?** All 6 are in
  this capture, suggesting they're trade-related but possibly
  also include the rejection ack pattern.
- **Rejection notification packet.** No distinct packet was
  observed at the marker window. Possibly the rejection comes
  via `0x03/0x1f` sub-tag we haven't isolated, OR the client
  silently times out the request UI.

## Backing evidence

Timeline:
[`_data/timelines/nc2_strace_RETAIL_CREATION_LEVELING_LONG_20260502_160841.md`](../_data/timelines/nc2_strace_RETAIL_CREATION_LEVELING_LONG_20260502_160841.md)
lines 84648 (marker) and 84738 (the only `0x2d` C→S in the window).
