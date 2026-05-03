# Flow: Apartment entry + Home terminal mail (CityCom DCB)

**Status:** verified  
**Backing capture:** `RETAIL_CREATION_LEVELING_LONG_20260502_160841`
— markers `APARTMENT` (t=2679.70s),
`OPEN_HOMETERM` (t=2716.17s),
`OPEN_HOMETERM_READMAIL` (t=2724.66s),
`OPEN_HOMETERM_DELETEMAIL` (t=2747.58s),
`CLOSED_HOMETERM` (t=2851.06s).

## Scenario

Player walks to their apartment, enters via the door → loaded
into the private apartment zone. Walks to their home terminal
("Hometerm"), opens it, reads an email, deletes the email, closes
the terminal.

## Two key findings

### 1. Apartment is just a regular zone

Apartment entry uses the **standard zone-walk handshake** — no
private-zone protocol. The Location packet shows the new zone:

```
0x830c 53 c6 4c 05 01 00 00 00 00 00 00 00 00 00 61 70 …
```

Bytes 1-4 are the zone ID (`05 4c c6 53` — large value indicates
private/instanced zone). Bytes 15-16 are `61 70` = ASCII "ap"
(start of "apartment/" path string).

In this capture the player actually re-entered via session resume
(KICKED_OUT_SERVER → RESUME → AuthB → UDPServerData → Location to
apartment), so we see the login-style entry rather than a walk-in
zone change. The protocol elements are identical either way.

### 2. Mail is implemented over the CityCom DCB RPC channel

The Hometerm "mail" UI is **not its own channel** — it speaks the
same `0x03/0x2b CityCom` RPC protocol that public CityCom
terminals use. The mail-specific RPC methods are passed as
**ASCII strings inside the request body**.

ASCII method names observed in this capture's mail session:

| Method (ASCII in body) | Side | Where seen |
|---|---|---|
| `DCBSetup`        | S→C | Setup before any RPC |
| `DCBGetLastEmail` | C→S | Header of "list emails" call |
| `Archive`         | C→S | Mail archive folder selector |
| `Emaillist`       | S→C | Server reply with email list payload |

Other strings expected (from CityCom in general) but not
exhaustively decoded: `Welcome`, plus mail body content.

## Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant U as GameServer (UDP)
    participant T as GameServer (TCP)

    rect rgb(245,250,255)
    Note over C,T: Phase A — Apartment entry
    Note over C,T: Same as zone walk — Location body bytes 15-16 = "ap" prefix.
    T->>C: 0x830d GameinfoReady
    T->>C: 0x830c Location 32B "830c [zone_id LE4] … 'ap…'"
    U->>C: 0x04 ? — UDP cipher reseed
    C->>U: 0x03 Reliable — cipher reseed reply
    C->>U: 0x03/0x08 ZoningEnd
    Note over C,U: Player spawns in their apartment.
    end

    rect rgb(255,250,240)
    Note over C,U: Phase B — Open Hometerm
    Note over C,U: Player walks to terminal, presses E.
    C->>U: 0x03/0x1f sub=0x17 (use object — interactions.md universal pattern)
    U->>C: 0x03/0x2b CityCom 7B "1a 01 00 01 00 00 00" (DCB session begin)
    U->>C: 0x03/0x2b CityCom 15B "1a 09 00 01 00 00 [DCBSetup]"
    Note right of C: ASCII "DCBSetup" — DCB stack initialization.
    C->>U: 0x03/0x2b CityCom 22B (client ack/handshake)
    Note over C,U: Hometerm UI opens, displays mailbox.
    end

    rect rgb(255,245,245)
    Note over C,U: Phase C — Read mail
    C->>U: 0x03/0x2b CityCom 22B (client RPC call)
    C->>U: 0x03/0x2b CityCom 66B (RPC body)
    C->>U: 0x03/0x2b CityCom 53B (RPC continuation)
    U->>C: 0x03/0x2b CityCom 28B "1a 16 … [DCBGetLastEmail]"
    U->>C: 0x03/0x2b CityCom 14B "1a 08 … [Archive]"
    U->>C: 0x03/0x2b CityCom 41B "17 16 … [DCBGetLastEmail reply]"
    U->>C: 0x03/0x2b CityCom 27B "17 08 … [Archive reply]"
    C->>U: 0x03/0x2b CityCom 22B (read-specific email request)
    C->>U: 0x03/0x2b CityCom 67B (request body)
    C->>U: 0x03/0x2b CityCom 56B (request continuation)
    U->>C: 0x03/0x2b CityCom 16B "1a 0a … [Emaillist]"
    U->>C: 0x03/0x2b CityCom 604B "17 0a … [Emaillist reply with email contents]"
    Note right of C: 604B reply contains the mail body — readable strings.
    end

    rect rgb(245,255,245)
    Note over C,U: Phase D — Delete mail
    C->>U: 0x03/0x2b CityCom 38B (delete request)
    C->>U: 0x03/0x2b CityCom 22B (request body)
    C->>U: 0x03/0x2b CityCom 49B (request continuation)
    U->>C: 0x03/0x2b CityCom (delete ack — exact size varies)
    end

    rect rgb(245,250,250)
    Note over C,U: Phase E — Close Hometerm
    Note over C,U: Player presses Esc / walks away.
    C->>U: 0x03/0x1f sub=0x?? (close interaction)
    U->>C: 0x03/0x2b CityCom (DCB session end ack)
    end
```

## CityCom DCB packet header (preliminary)

The bodies start with a 2-byte type/op header followed by length
and an ASCII method name:

```
Offset  Size  Field
0x00    1     direction tag    (0x1a = server "begin", 0x17 = server "reply", 0x1f = client request, 0x18/0x1b = client RPC, 0x16 = ?)
0x01    1     length-ish byte
0x02    2     0x00 0x01        (constant)
0x04    2     0x00 0x00        (constant or seq)
0x06    var   ASCII method     (e.g. "DCBSetup", "Emaillist", "Archive")
…       var   payload          (mail body for replies)
```

Cleanly distinguishing **request** vs **reply** is: byte 0 = 0x17
for replies (with a length-prefix at 0x02-0x05), 0x1a for server
session-control, 0x1f / 0x18 / 0x1b for client requests.

## Other CityCom DCB use cases

The same channel is used by every "kiosk-style" UI in the game:

| Marker | Likely DCB method |
|---|---|
| `OPEN_HOMETERM_READMAIL` | `Emaillist`, `Archive`, `DCBGetLastEmail` |
| `TERMINAL_CITYCOM` (in ZONING_AND_ITEMS_LONG) | `Welcome`, plus public DCB calls |
| `APARTMENT` Hometerm | mail + character info + bookmarks |

The catalog shows `0x03/0x2b CityCom` with sizes 7-66 (S→C) and
22-66 (C→S) in our prior analysis; this capture extends the
upper bound to **604B** (the email-list reply with body).

## Open questions

- **DCB header byte 0 enum.** Observed values: 0x1a (server
  begin/announce), 0x17 (server reply), 0x16 (server data),
  0x1b/0x1f/0x18 (client). Need full enumeration with markers
  for each operation.
- **Method dispatch.** Are method names hashed server-side, or
  parsed as strings? The fact that they're plain ASCII suggests
  the server has a string-keyed table.
- **Mail attachment / item delivery.** This capture only covers
  text mail. Item-attachment mail (sending an item by mail) would
  exercise additional DCB calls — needs a follow-up capture.
- **Public CityCom terminals (street-side)** vs Hometerm —
  same protocol, different access scope?

## Backing evidence

Timeline:
[`_data/timelines/nc2_strace_RETAIL_CREATION_LEVELING_LONG_20260502_160841.md`](../_data/timelines/nc2_strace_RETAIL_CREATION_LEVELING_LONG_20260502_160841.md)
lines 133616-133620 (apartment Location), 135415-136186 (mail).
