# Reliable-UDP receive window — client-side commit rules (RE'd via live Frida probe)

**Status: authoritative.** Derived by live-instrumenting `neocronclient.exe`'s
reliable handler (`nclib/net/GameNetMgr.cpp`, `ProcessGuaranteedMsg`) with Frida
while logging into Ceres-J, then byte-diffing against retail. Supersedes earlier
static-disasm guesses (several of which were wrong — noted inline).

## Wire framing recap

A decrypted `0x13` gamedata datagram:

```
[0x13][outer_ctr LE2][outer_ctr+sessionkey LE2]  ( [subLen LE2][wrapper][...] )+
```

Reliable sub-packet: `[0x03][seq LE2][op][body]`. The `0x02` "simplified
reliable" wrapper has the same `[0x02][seq LE2][op][body]` shape.

## How the client commits a reliable (the three rules Ceres-J was breaking)

The client routes both `0x03` and `0x02` sub-packets to `ProcessGuaranteedMsg`
with an `isResponse` flag = `(wrapper == 0x02)`. Live-probe findings:

1. **`0x02` does NOT advance the reliable window; only `0x03` does.**
   `isResponse == 1` (i.e. a `0x02` packet) is treated as a *retransmit/recovered*
   delivery — it is accepted but does **not** move the expected-seq pointer
   forward. So **login STATE must be carried on `0x03`**, never `0x02`, or the
   window develops permanent gaps the client re-requests forever. Retail sends
   **zero `0x02` sub-packets in the login burst** — everything reliable is `0x03`.

2. **Reliable seq must start at 1. The client DROPS seq 0.**
   `ProcessGuaranteedMsg` returns `0` (drop) for `seq == 0` and `1` (accept) for
   `seq >= 1`. seq 0 is invalid/sentinel (`seq & 0x7ff == 0`). Retail's first
   reliable is seq 1. (An earlier static-disasm reading claimed the client seeds
   `expected = 0` and the server should start at 0 — **refuted by the probe**; it
   made the storm worse.)

3. **The login burst must not lead with a non-windowed control op.**
   A `0x08` (ZoningEnd/AbortSession) as the first reliable does not advance the
   window. Login must lead with real windowed data (CharInfo `0x2c`).

## Client connection-record layout (for future probing)

`ProcessGuaranteedMsg` is `__thiscall`, `this` = ECX = the GameNetMgr.
Args (stack): `[esp+4]` clientNum, `[esp+8]` seq (`& 0x7ff`), `[esp+12]`
isResponse. Return in **AL**: `1` accept / `0` drop.

Per-client record: `record = *(*(ECX + 0x14) + clientNum*4)`.
- `record + 0x0` — active flag (`== 1` when the client slot is live).
- `record + 0x6` — **expected/next reliable seq** (advances by 1 per accepted
  `0x03`). *(NOT `+0xc`; `+0xc` is a heap pointer — the earlier disasm was wrong.)*

When a gap is seen the client queues the missing seq(s) via `AddMsgToOOOList` and
a timer re-emits `[0x13]…[subLen][0x01][seq LE2]` retransmit-requests for them.

## Ceres-J fixes (this changeset)

| Symptom | Root cause | Fix |
|---|---|---|
| Client never commits login state; raw-0x01 storm | Init packets sent on `0x02` (doesn't advance window) | `Init*02` now `extends PacketBuilderUDP1303` → emitted on `0x03` |
| First reliable dropped → permanent seq-0 gap | Reliable seq started at 0 | `udpSessionCounter` init `0`, pre-increment → first seq **1** |
| Window never re-bases | `0x08` primer led the login burst | Removed the primer; login leads with CharInfo |

**Result (live probe, all three fixes):** the window field `record+0x6` advances
cleanly `0 → 1 → 2 → … → 19`, every sub-packet accepted, `AddMsgToOOOList` never
called — the reliable layer is healthy. Apartment-idle client retransmit-requests
dropped from ~34 to ~14 (the residual ~14 are not reliable-gap re-requests — the
window is gap-free).

## How to re-run the live probe

`tools/frida_re/probes/probe_reliable.py <ceres|retail>` injects
`reliable_probe.js` on the harness Frida session. **Address resolution gotcha:**
`base + (GhidraVA - 0x400000)` does NOT map correctly for NeocronClient.exe
internal functions. Resolve by **string-xref** instead: `Memory.scanSync` for a
unique log string (e.g. `"Packet loss"`), find its `68 <strAddr LE4>` push, walk
back to the nearest `55 8b ec 6a ff 68` prologue = the function entry. Frida
export API is `Process.getModuleByName(mod).findExportByName(sym)`.
