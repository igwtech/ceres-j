# `TCP C->S 0x873c` — GetUDPConnection

**Transport:** TCP  
**Direction:** C->S  
**Identifier:** `0x873c`  
**Status:** partial  

## Evidence

<!-- catalog-evidence (auto-generated; do not edit manually — regenerate via `tools/catalog_extract.py --update-evidence`) -->

- Total observations: **6**
- Captures with this packet: **6/17**
- Size (bytes): min **6**, avg **6**, max **6**
- Per-capture counts:
  - `RETAIL_FULL_PCAP_TRACE_20260426_154648` × 1
  - `RETAIL_ODA_20260426_202428` × 1
  - `RETAIL_AUGUSTO_20260426_201952` × 1
  - `RETAIL_HANNIBAL_20260426_201501` × 1
  - `RETAIL_NORMAN_20260426_200458` × 1
  - `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` × 1

Samples (first 32 bytes inner data):

```
#1: 873c00000000
```
```
#2: 873c00000000
```
```
#3: 873c00000000
```

<!-- /catalog-evidence -->

## Structure

TCP C→S 0x873c — `GetUDPConnection`. Client probes for UDP
connection setup. Fixed 6-byte body, all-zero trailer.
Verified 2026-05-10 against all 6 retail samples from 6/17
captures.

```
[0..1]   87 3c                  TCP opcode (constant)
[2..5]   00 00 00 00            CONSTANT padding
```

All 6 observations are byte-identical: `87 3c 00 00 00 00`.
NO content variation.

## Variants

Single 6-byte form. Pure constant probe.

## Observed contexts

Emitted ONCE per session, after the world-entry sequence
(`Gamedata` + `UDPServerData` + `Location`) has completed.
Likely a "I'm ready for UDP traffic, please confirm" probe.

The 11 captures that don't emit this are likely sessions where
the resume-login flow bypassed this probe.

## Open questions

- Per `project_zone_handoff_fixed.md`: the server's response
  to this packet was found to cause a 25s disconnect bug. The
  fix was to make `GetUDPConnection` a no-op (don't send a
  duplicate `UDPServerData`).
- The 4 trailing zero bytes: reserved (unused).

## Server-side handler

`server.gameserver.packets.client_tcp.GetUDPConnection` —
explicitly NO-OP per the zone-handoff fix:

```java
public void execute(Player pl) {
    // No-op. GetGamedata already sends UDPServerData + Location.
    // Sending a SECOND UDPServerData here causes the client to
    // [...] detect a session mismatch [...]
    // the second UDPServerData to arrive after the session is
    // established, triggering a 25-second disconnect.
    Out.writeln(Out.Info, "GetUDPConnection: no-op (UDPServerData already sent by GetGamedata)");
}
```

This is the canonical example of the
`project_zone_handoff_fixed.md` finding: the modern NC2
client sends 0x873c to verify UDP setup is complete, but the
server should NOT respond with a second `UDPServerData` —
that triggers a timeout disconnect after 25s.

