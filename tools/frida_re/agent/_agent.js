/*
 * frida_re agent — runs INSIDE the Wine prefix as part of
 * frida-gadget.dll (renamed to a DLL the EXE imports).
 *
 * The orchestrator pushes this script after attaching. A preamble
 * injected by the orchestrator sets:
 *
 *   globalThis.__FRIDA_RE_HOOKS__ = [
 *     { name: "udp_cipher_a", offset: 0x160090, purpose: "..." },
 *     ...
 *   ]
 *
 * (the canonical table lives in orchestrator/symbols.py).
 *
 * Responsibilities:
 *   1. Resolve neocronclient.exe module base.
 *   2. Install Interceptor hooks for each entry in the hook table.
 *      The dispatch table below maps hook name -> handler factory.
 *   3. Expose rpc.exports for control:
 *        - getStatus()
 *        - readMem(addr, n)        ->  number[]
 *        - writeMem(addr, bytes[]) ->  number  (bytes written)
 *        - callFunction(addr, args[], retType?)  -> any
 *        - sendUdp(bytes[])        ->  number  (bytes sent post-cipher)
 *
 * Events emitted via send():
 *   { ev: "ready", base: "0x...", hooks: [...] }
 *   { ev: "cipher_enter" | "cipher_leave", hook, seed_lo, seed_hi,
 *     buf_hex, buf_len, tid, ts }
 *   { ev: "hook_error", hook, error }
 */

'use strict';

// ---------------------------------------------------------------------
// Configuration / utility
// ---------------------------------------------------------------------

const MODULE_NAME = 'NeocronClient.exe';

/** Convert a NativePointer-backed buffer to a hex string preview.
 *  The agent never dumps more than DUMP_LIMIT bytes per event to keep
 *  the send() channel from being saturated by huge payloads.
 */
const DUMP_LIMIT = 128;

function bufHex(addr, len) {
    if (addr.isNull() || len === 0) return '';
    const n = Math.min(len, DUMP_LIMIT);
    let bytes;
    try {
        bytes = new Uint8Array(addr.readByteArray(n));
    } catch (e) {
        return `<read-failed:${e.message}>`;
    }
    const parts = new Array(bytes.length);
    for (let i = 0; i < bytes.length; ++i) {
        parts[i] = (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
    }
    return parts.join(' ');
}

function nowNs() {
    // Frida exposes a hrtime via the QuickJS runtime; fall back to
    // ms*1e6 if not present (older Frida builds).
    const ms = Date.now();
    return Math.floor(ms * 1e6);
}

/** Resolve the PE module base. Returns null if not loaded yet. */
function findModule() {
    const m = Process.findModuleByName(MODULE_NAME);
    if (m !== null) return m;
    // Case-insensitive fallback — Wine sometimes presents lowercase.
    const all = Process.enumerateModules();
    for (const mod of all) {
        if (mod.name.toLowerCase() === MODULE_NAME.toLowerCase()) {
            return mod;
        }
    }
    return null;
}

// ---------------------------------------------------------------------
// Hook factories
// ---------------------------------------------------------------------
//
// One entry per name registered in orchestrator/symbols.py. Factory
// returns the InterceptorCallback object passed to Interceptor.attach.
//
// First-iteration handlers for the UDP cipher dump:
//   - args[0] (ECX/EAX/stack[0]) is *almost certainly* a pointer to
//     the buffer to (en|de)crypt; we don't know the exact ABI without
//     a control-flow trace, so we read both ESP+4 .. ESP+12 (cdecl)
//     and pass them along as raw register snapshots. Once we see one
//     packet decoded we'll lock the signature.
//   - The buffer's first 2 bytes on the wire are the per-packet LFSR
//     seed (seed_lo, seed_hi); we surface them from the buffer head
//     so the orchestrator can correlate enter/leave by seed.
//

function cipherHandler(hookName) {
    return {
        onEnter(args) {
            try {
                // Heuristic: try args[0] as a pointer first.
                const pBuf = args[0];
                const ctx = this.context;
                const len = (args[1] !== undefined)
                    ? args[1].toInt32() & 0xffff : 0;
                let seedLo = 0, seedHi = 0;
                let hex = '';
                if (pBuf !== undefined && !pBuf.isNull()) {
                    try {
                        const head = new Uint8Array(pBuf.readByteArray(
                            Math.min(2, len > 0 ? len : 2)));
                        if (head.length >= 1) seedLo = head[0];
                        if (head.length >= 2) seedHi = head[1];
                    } catch (_) { /* fall through */ }
                    hex = bufHex(pBuf, len > 0 ? len : DUMP_LIMIT);
                }
                this._pBuf = pBuf;
                this._len = len;
                send({
                    ev: 'cipher_enter',
                    hook: hookName,
                    seed_lo: seedLo,
                    seed_hi: seedHi,
                    buf_hex: hex,
                    buf_len: len,
                    eax: ctx.eax !== undefined
                        ? '0x' + ctx.eax.toString(16) : null,
                    ecx: ctx.ecx !== undefined
                        ? '0x' + ctx.ecx.toString(16) : null,
                    tid: this.threadId,
                    ts: nowNs(),
                });
            } catch (e) {
                send({ ev: 'hook_error', hook: hookName,
                       phase: 'enter', error: String(e) });
            }
        },
        onLeave(retval) {
            try {
                const pBuf = this._pBuf;
                const len = this._len;
                let seedLo = 0, seedHi = 0;
                let hex = '';
                if (pBuf !== undefined && !pBuf.isNull()) {
                    try {
                        const head = new Uint8Array(pBuf.readByteArray(
                            Math.min(2, len > 0 ? len : 2)));
                        if (head.length >= 1) seedLo = head[0];
                        if (head.length >= 2) seedHi = head[1];
                    } catch (_) { /* fall through */ }
                    hex = bufHex(pBuf, len > 0 ? len : DUMP_LIMIT);
                }
                send({
                    ev: 'cipher_leave',
                    hook: hookName,
                    seed_lo: seedLo,
                    seed_hi: seedHi,
                    buf_hex: hex,
                    buf_len: len,
                    retval: retval !== undefined && !retval.isNull()
                        ? '0x' + retval.toString(16) : null,
                    tid: this.threadId,
                    ts: nowNs(),
                });
            } catch (e) {
                send({ ev: 'hook_error', hook: hookName,
                       phase: 'leave', error: String(e) });
            }
        }
    };
}

// ── Winsock UDP capture handlers ─────────────────────────────────────
//
// recvfrom prototype:
//   int recvfrom(SOCKET s, char* buf, int len, int flags,
//                sockaddr* from, int* fromlen)
// onLeave: retval is bytes_read (-1 on error, 0 on graceful close).
// We dump exactly retval bytes from the buf pointer captured onEnter.
//
// sendto prototype:
//   int sendto(SOCKET s, const char* buf, int len, int flags,
//              const sockaddr* to, int tolen)
// onEnter: dump exactly `len` bytes from buf. Truncated at DUMP_LIMIT
// to keep the send() channel sane on jumbo packets.

function udpRecvHandler() {
    return {
        onEnter(args) {
            this._sock = args[0].toInt32();
            this._buf  = args[1];
        },
        onLeave(retval) {
            try {
                const n = retval.toInt32();
                if (n <= 0) return;
                send({
                    ev: 'udp_recv',
                    sock: this._sock,
                    len: n,
                    hex: bufHex(this._buf, Math.min(n, DUMP_LIMIT)),
                    tid: this.threadId,
                    ts: nowNs(),
                });
            } catch (e) {
                send({ ev: 'hook_error', hook: 'udp_recv',
                       phase: 'leave', error: String(e) });
            }
        },
    };
}

function udpSendHandler() {
    return {
        onEnter(args) {
            try {
                const sock = args[0].toInt32();
                const len  = args[2].toInt32();
                if (len <= 0 || len > 0x10000) return;
                send({
                    ev: 'udp_send',
                    sock: sock,
                    len: len,
                    hex: bufHex(args[1], Math.min(len, DUMP_LIMIT)),
                    tid: this.threadId,
                    ts: nowNs(),
                });
            } catch (e) {
                send({ ev: 'hook_error', hook: 'udp_send',
                       phase: 'enter', error: String(e) });
            }
        },
    };
}

// ── TCP capture (NC2 doesn't use ws2_32 recv/send/WSARecv/WSASend) ──
//
// 2026-05-28: hooked all four ws2_32 stream-socket APIs during live
// gameplay, captured zero TCP frames in 20s. NC2 must be reaching
// the socket via a deeper API. Three candidates worth hooking, in
// order of likelihood:
//   1. ntdll!NtDeviceIoControlFile with AFD IOCTL codes (the
//      lowest user-mode socket I/O on Windows / Wine).
//   2. kernel32!ReadFile / WriteFile on the socket handle (some
//      games route through these when overlapped I/O is in use).
//
// We hook all three. Filter on the orchestrator side by content:
// NC2 TCP frames start with the byte 0xfe (FE-framing marker), so
// any chunk where the first byte is 0xfe is interesting.

// v0.7.0: VERIFIED via NC2 import table (pefile dump 2026-05-28):
// NC2 uses Win32 GetKeyboardState (256-byte state buffer) AND
// GetAsyncKeyState for keyboard polling. We hook both and inject our
// state directly into the returned buffer / return value.
//
// NC2 imports `send` (TCP send) + `recvfrom` (covers both UDP+TCP
// since `recv` is NOT in the import table) + `sendto` (UDP send).
// So TCP-send via ws2_32!send is a NEW hook for v0.7.0.

globalThis.__INJECT_KEYS = {};  // {vk_code: 0x80 | 0}

function keyboardStateHandler() {
    return {
        onEnter(args) {
            this._buf = args[0];  // lpKeyState (LPBYTE) — 256 bytes
        },
        onLeave(retval) {
            // BOOL retval — must be nonzero for game to consume.
            if (retval.toInt32() === 0) return;
            const inj = globalThis.__INJECT_KEYS;
            const keys = Object.keys(inj);
            if (keys.length === 0) return;
            try {
                for (const vk of keys) {
                    this._buf.add(parseInt(vk)).writeU8(inj[vk] ? 0x80 : 0);
                }
            } catch (e) {
                send({ ev: 'hook_error', hook: 'GetKeyboardState',
                       error: String(e) });
            }
        },
    };
}

function asyncKeyStateHandler() {
    return {
        onEnter(args) {
            this._vk = args[0].toInt32() & 0xff;
        },
        onLeave(retval) {
            const inj = globalThis.__INJECT_KEYS;
            if (inj[this._vk]) {
                // Return value: high bit of low word set = pressed now.
                retval.replace(0x8001);
            } else if (this._vk in inj) {
                // We explicitly cleared this key.
                retval.replace(0);
            }
            // Else: pass through original value.
        },
    };
}

// TCP send capture — NC2 imports ws2_32!send for outbound TCP.
function tcpSendHandler() {
    return {
        onEnter(args) {
            const sock = args[0].toInt32();
            const buf = args[1];
            const len = args[2].toInt32();
            if (len <= 0 || len > 0x10000) return;
            try {
                send({
                    ev: 'tcp_send',
                    sock: sock,
                    len: len,
                    hex: bufHex(buf, Math.min(len, DUMP_LIMIT)),
                    ts: nowNs(),
                });
            } catch (e) {}
        },
    };
}

// `ws2_32!connect` — fires once per outgoing TCP dial. NC2 calls
// this for both the game server connection and the launcher's
// api.github.com TLS phone-home. The sockaddr_in layout (x86, IPv4)
// is: u16 sa_family, u16 sin_port (BE), u32 sin_addr, 8B padding.
//
// We log family, port (BE→host), and dotted-quad IP. Socket FD goes
// out as `sock` so later `tcp_send` events can be attributed to a
// specific connection.
function connectHandler() {
    return {
        onEnter(args) {
            try {
                const sock = args[0].toInt32();
                const pSockaddr = args[1];
                const namelen = args[2].toInt32();
                if (pSockaddr.isNull() || namelen < 8) return;
                const fam = pSockaddr.readU16();
                // AF_INET = 2; others (AF_INET6=23, etc.) we still
                // log but don't decode the address payload.
                let portHost = -1, ipDotted = null;
                if (fam === 2) {
                    const portBE = pSockaddr.add(2).readU16();
                    portHost = ((portBE & 0xff) << 8) | (portBE >>> 8);
                    const addrLE = pSockaddr.add(4).readU32();
                    ipDotted = [
                        (addrLE >>> 0)  & 0xff,
                        (addrLE >>> 8)  & 0xff,
                        (addrLE >>> 16) & 0xff,
                        (addrLE >>> 24) & 0xff,
                    ].join('.');
                }
                send({
                    ev: 'tcp_connect',
                    sock: sock,
                    family: fam,
                    port: portHost,
                    ip: ipDotted,
                    namelen: namelen,
                    ts: nowNs(),
                });
            } catch (e) {
                send({ ev: 'hook_error', hook: 'connect',
                       error: String(e) });
            }
        },
    };
}

// recvfrom — same hook as udp_recv but renamed for clarity since it
// also catches TCP receives (NC2 doesn't import recv, only recvfrom).
// We re-emit as `udp_recv` for orchestrator-side compat; the decoder
// will mark decrypt_ok:false for TCP frames (which aren't cipher-
// encrypted) and the orchestrator can route those as TCP.

// Probe — useful to confirm which APIs are active.
function rawInputProbeHandler(name) {
    return {
        onLeave() {
            send({ ev: 'input_api_call', api: name, ts: nowNs() });
        }
    };
}

// v0.7.0 → v0.7.1: GetRawInputData injection.
//
// NC2 uses RawInput (verified — 98 GetRawInputData calls during
// startup). To inject keyboard events: hook GetRawInputData,
// observe the RAWINPUT layout it returns, optionally replace the
// keyboard portion with our synthetic event.
//
// Signature: UINT GetRawInputData(HRAWINPUT hRawInput,
//                                  UINT uiCommand,
//                                  LPVOID pData,
//                                  PUINT pcbSize,
//                                  UINT cbSizeHeader);
//   args[0] = hRawInput
//   args[1] = uiCommand (RID_INPUT=0x10000003, RID_HEADER=0x10000005)
//   args[2] = pData (output buffer)
//   args[3] = pcbSize (in/out size)
//   args[4] = cbSizeHeader (size of RAWINPUTHEADER, 16 on x86)
//
// RAWINPUT layout (x86):
//   RAWINPUTHEADER (16 bytes):
//     DWORD dwType         (offset 0; 0=mouse, 1=keyboard, 2=hid)
//     DWORD dwSize
//     HANDLE hDevice
//     WPARAM wParam
//   then union by dwType. For keyboard (RAWKEYBOARD, 16 bytes):
//     USHORT MakeCode      (offset 16)
//     USHORT Flags         (offset 18; 0=down, 1=up)
//     USHORT Reserved      (offset 20)
//     USHORT VKey          (offset 22)
//     UINT   Message       (offset 24; WM_KEYDOWN=0x100)
//     ULONG  ExtraInformation (offset 28)
//
// Injection strategy: when our inject map is non-empty, replace the
// returned struct's keyboard fields with our synthetic event. This
// will be seen by NC2's WM_INPUT handler as a real key event.

globalThis.__RI_INJECT_QUEUE = [];  // synthetic events to inject
let riDataLogged = 0;

function getRawInputDataHandler() {
    return {
        onEnter(args) {
            this._cmd     = args[1].toInt32();
            this._pData   = args[2];
            this._pSize   = args[3];
            this._hdrSize = args[4].toInt32();
        },
        onLeave(retval) {
            const n = retval.toInt32();
            // -1 means error.
            if (n === -1 || n === 0xffffffff) return;
            if (this._pData.isNull()) return;
            try {
                const dwType = this._pData.readU32();
                // For first 5 keyboard events, log layout for inspection.
                if (dwType === 1 && riDataLogged < 5) {
                    riDataLogged++;
                    const hexHead = bufHex(this._pData, 32);
                    send({ ev: 'ri_keyboard_sample', n: n,
                           cmd: '0x' + this._cmd.toString(16),
                           hex: hexHead });
                }
                // Inject from queue if present.
                if (dwType === 1 && globalThis.__RI_INJECT_QUEUE.length > 0) {
                    const inj = globalThis.__RI_INJECT_QUEUE.shift();
                    // pData + 16 = RAWKEYBOARD.
                    // MakeCode = scan code, VKey = virtual key.
                    this._pData.add(18).writeU16(inj.up ? 1 : 0);  // Flags
                    this._pData.add(22).writeU16(inj.vk);          // VKey
                    this._pData.add(24).writeU32(inj.up ? 0x101 : 0x100);  // Message
                    send({ ev: 'ri_injected', vk: '0x' + inj.vk.toString(16),
                           up: !!inj.up });
                }
            } catch (e) {
                send({ ev: 'hook_error', hook: 'GetRawInputData',
                       error: String(e) });
            }
        },
    };
}

function tcpReadFileHandler() {
    return {
        onEnter(args) {
            this._handle = args[0];
            this._buf    = args[1];
        },
        onLeave(retval) {
            // ReadFile returns BOOL — but the actual bytes-read is in
            // the lpNumberOfBytesRead OUT param (args[3] on x86).
            try {
                // We don't have args here in onLeave; use captured this.
                // Heuristic: read the first 256 bytes from buf and emit
                // if it looks like an FE-framed packet.
                const head = new Uint8Array(this._buf.readByteArray(256));
                if (head.length >= 3 && head[0] === 0xfe) {
                    const size = head[1] | (head[2] << 8);
                    send({
                        ev: 'tcp_read',
                        handle: '0x' + this._handle.toString(16),
                        hex: bufHex(this._buf, Math.min(size + 3, DUMP_LIMIT)),
                        ts: nowNs(),
                    });
                }
            } catch (e) { /* not a readable buffer */ }
        },
    };
}

function tcpWriteFileHandler() {
    return {
        onEnter(args) {
            const handle = args[0];
            const buf = args[1];
            const len = args[2].toInt32();
            if (len <= 0 || len > 0x10000) return;
            try {
                const head = new Uint8Array(buf.readByteArray(Math.min(len, 3)));
                if (head.length >= 1 && head[0] === 0xfe) {
                    send({
                        ev: 'tcp_write',
                        handle: '0x' + handle.toString(16),
                        len: len,
                        hex: bufHex(buf, Math.min(len, DUMP_LIMIT)),
                        ts: nowNs(),
                    });
                }
            } catch (e) { /* skip */ }
        },
    };
}

// Wine's AFD driver IOCTL codes for socket I/O.
const AFD_RECV  = 0x12017;
const AFD_SEND  = 0x1201f;
const AFD_RECV2 = 0x12047;   // alternate seen in some Wine builds
const AFD_SEND2 = 0x12053;
const AFD_RECV_DGRAM = 0x12023;
const AFD_SEND_DGRAM = 0x1201b;

// v0.5.0: filter by IOCTL code (AFD recv/send) instead of by 0xfe
// prefix. The AFD layer wraps the application data in an internal
// header so the raw fe-framing wasn't where my v0.4.0 filter expected.
function tcpIoctlHandler() {
    return {
        onEnter(args) {
            this._handle = args[0];
            this._ioctl  = args[5].toInt32();
            this._inBuf  = args[6];
            this._inLen  = args[7].toInt32();
            this._outBuf = args[8];
            this._outLen = args[9].toInt32();
        },
        onLeave(retval) {
            const ioctl = this._ioctl;
            let kind = null;
            if (ioctl === AFD_RECV || ioctl === AFD_RECV2
                || ioctl === AFD_RECV_DGRAM) {
                kind = 'recv';
            } else if (ioctl === AFD_SEND || ioctl === AFD_SEND2
                       || ioctl === AFD_SEND_DGRAM) {
                kind = 'send';
            } else {
                return;
            }
            try {
                let hex = '';
                let bufLen = 0;
                if (kind === 'send' && this._inLen > 0
                        && this._inLen < 0x10000) {
                    hex = bufHex(this._inBuf,
                                 Math.min(this._inLen, DUMP_LIMIT));
                    bufLen = this._inLen;
                } else if (kind === 'recv' && this._outLen > 0
                        && this._outLen < 0x10000) {
                    hex = bufHex(this._outBuf,
                                 Math.min(this._outLen, DUMP_LIMIT));
                    bufLen = this._outLen;
                }
                if (hex !== '') {
                    send({
                        ev: 'tcp_ioctl',
                        ioctl: '0x' + ioctl.toString(16),
                        kind: kind,
                        handle: '0x' + this._handle.toString(16),
                        len: bufLen,
                        hex: hex,
                        ts: nowNs(),
                    });
                }
            } catch (e) {}
        },
    };
}

// v0.5.0: ntdll!NtReadFile / NtWriteFile. The kernel-transition
// path. If kernel32!ReadFile is bypassed (overlapped I/O, direct
// callers), these still fire. Filtered by 0xfe-prefix in the
// buffer since they fire for ALL file I/O including PAK reads and
// log writes; without filtering we'd drown in non-socket traffic.
function ntReadHandler() {
    return {
        onEnter(args) {
            // NtReadFile(handle, event, apc, apcCtx, iosb, buffer, len, byteOffset, key)
            this._handle = args[0];
            this._buf    = args[5];
            this._len    = args[6].toInt32();
        },
        onLeave(retval) {
            if (this._len <= 0 || this._len > 0x10000) return;
            try {
                const head = new Uint8Array(
                    this._buf.readByteArray(Math.min(3, this._len)));
                if (head.length >= 1 && head[0] === 0xfe) {
                    send({
                        ev: 'nt_read',
                        handle: '0x' + this._handle.toString(16),
                        len: this._len,
                        hex: bufHex(this._buf,
                                    Math.min(this._len, DUMP_LIMIT)),
                        ts: nowNs(),
                    });
                }
            } catch (e) {}
        },
    };
}

function ntWriteHandler() {
    return {
        onEnter(args) {
            // NtWriteFile(handle, event, apc, apcCtx, iosb, buffer, len, byteOffset, key)
            this._handle = args[0];
            this._buf    = args[5];
            this._len    = args[6].toInt32();
            if (this._len <= 0 || this._len > 0x10000) {
                this._skip = true;
                return;
            }
            try {
                const head = new Uint8Array(
                    this._buf.readByteArray(Math.min(3, this._len)));
                if (head.length >= 1 && head[0] === 0xfe) {
                    send({
                        ev: 'nt_write',
                        handle: '0x' + this._handle.toString(16),
                        len: this._len,
                        hex: bufHex(this._buf,
                                    Math.min(this._len, DUMP_LIMIT)),
                        ts: nowNs(),
                    });
                }
            } catch (e) {}
        },
    };
}

// ── DirectInput8 chain hook for input control ────────────────────────
//
// With on_load:wait (gadget v0.4.0+), the agent JS runs BEFORE NC2
// calls DirectInput8Create. We chain-hook the COM creation path so
// we capture the keyboard device's vtable when it's created.
//
// Once captured, rpc.exports.dik_press(dik, on) toggles a key in our
// inject map. Our GetDeviceData / GetDeviceState hook modifies the
// returned state buffer to set/clear that key.

globalThis.__DI8_INJECT = {};   // {dik: 0x80 | 0}
let DI8_DEVICE_VT = null;       // captured at CreateDevice time

function attachDI8Chain() {
    const di8 = Process.findModuleByName('dinput8.dll')
             || Process.findModuleByName('DINPUT8.DLL');
    if (di8 === null) {
        send({ ev: 'di_unavailable' });
        return;
    }
    const DI8Create = di8.findExportByName('DirectInput8Create');
    if (DI8Create === null) return;

    Interceptor.attach(DI8Create, {
        onEnter(args) { this._ppv = args[4]; },
        onLeave(retval) {
            if (retval.toInt32() !== 0) return;
            try {
                const pIDI8 = this._ppv.readPointer();
                if (pIDI8.isNull()) return;
                const vt = pIDI8.readPointer();
                // IDirectInput8 vtable slot 3 = CreateDevice (x86 cdecl).
                const pCD = vt.add(3 * 4).readPointer();
                Interceptor.attach(pCD, {
                    onEnter(args) {
                        // **thiscall**: args[0] = REFGUID rguid (first
                        // STACK arg after `this` in ECX).
                        this._rguid  = args[0];
                        this._ppDev  = args[1];
                    },
                    onLeave(r) {
                        if (r.toInt32() !== 0) return;
                        try {
                            const pDev = this._ppDev.readPointer();
                            if (pDev.isNull()) return;
                            const dvt = pDev.readPointer();
                            // Slot 9 = GetDeviceState; slot 10 = GetDeviceData.
                            // Hook BOTH — whichever NC2 uses for keyboard.
                            const pGDS = dvt.add(9 * 4).readPointer();
                            const pGDD = dvt.add(10 * 4).readPointer();
                            DI8_DEVICE_VT = '0x' + dvt.toString(16);
                            send({ ev: 'di_device_created',
                                   vtable: DI8_DEVICE_VT,
                                   getDeviceState: '0x' + pGDS.toString(16),
                                   getDeviceData:  '0x' + pGDD.toString(16) });
                            installGDSHook(pGDS);
                            installGDDHook(pGDD);
                        } catch (e) {
                            send({ ev: 'di_hook_err', phase: 'CreateDevice',
                                   err: String(e) });
                        }
                    },
                });
                send({ ev: 'di_idi8_hooked' });
            } catch (e) {
                send({ ev: 'di_hook_err', phase: 'DI8Create',
                       err: String(e) });
            }
        },
    });
    send({ ev: 'di_chain_armed' });
}

function installGDSHook(addr) {
    Interceptor.attach(addr, {
        onEnter(args) {
            // thiscall: args[0]=cbData, args[1]=lpvData (this is in ECX).
            this._cb  = args[0].toInt32();
            this._lpv = args[1];
        },
        onLeave(retval) {
            if (retval.toInt32() !== 0) return;
            if (this._cb !== 256) return;   // keyboard state size
            const inj = globalThis.__DI8_INJECT;
            const keys = Object.keys(inj);
            if (keys.length === 0) return;
            try {
                for (const dik of keys) {
                    this._lpv.add(parseInt(dik)).writeU8(inj[dik] ? 0x80 : 0);
                }
            } catch (e) {}
        },
    });
}

function installGDDHook(addr) {
    // GetDeviceData buffer-based — used when the device is in
    // buffered mode. Each element is a DIDEVICEOBJECTDATA (16 bytes).
    // For injection we'd need to ADD events to the buffer, which is
    // more invasive. For v0.4.0 we just observe.
    Interceptor.attach(addr, {
        onLeave(retval) {
            // Just count calls so we know if NC2 uses buffered mode.
            send({ ev: 'di_get_data_called' });
        },
    });
}

attachDI8Chain();

// ── D3D9 device chain — screen capture (v0.7.2) ──────────────────────
//
// IDirect3D9 vtable slot 16 = CreateDevice (after IUnknown's 3 + 13
// IDirect3D9 methods up to CreateDevice).
// IDirect3DDevice9 useful vtable slots (x86, 4-byte ptrs):
//   2 = Release
//   17 = Present
//   18 = GetBackBuffer
//   32 = GetRenderTargetData
//   33 = GetRenderTarget
//   36 = CreateOffscreenPlainSurface
// IDirect3DSurface9 useful slots:
//   2 = Release
//   12 = GetDesc
//   13 = LockRect
//   14 = UnlockRect
//
// All COM methods are __stdcall but `this` is in ECX → Frida args[0]
// is the FIRST stack arg AFTER `this`.

globalThis.__D3D9_DEVICE = null;

function attachD3D9Chain() {
    const d3d = Process.findModuleByName('d3d9.dll')
             || Process.findModuleByName('D3D9.DLL');
    if (d3d === null) { send({ ev: 'd3d9_unavailable' }); return; }
    const D3DCreate9 = d3d.findExportByName('Direct3DCreate9');
    if (D3DCreate9 === null) return;

    Interceptor.attach(D3DCreate9, {
        onLeave(retval) {
            if (retval.isNull()) return;
            try {
                const pIDirect3D9 = retval;
                const vt = pIDirect3D9.readPointer();
                const pCreateDevice = vt.add(16 * 4).readPointer();
                Interceptor.attach(pCreateDevice, {
                    onEnter(args) {
                        // v0.7.2 used args[5] and got 0x400 (which is
                        // D3DCREATE_DISABLE_DRIVER_MANAGEMENT_EX —
                        // confirming args[5] is BehaviorFlags under
                        // DXVK's actual ABI). The OUT param is at a
                        // different index. v0.8.0: save args[0..9]
                        // and inspect each one in onLeave to find
                        // the writable OUT pointer.
                        this._args = [];
                        for (let i = 0; i <= 9; i++) {
                            this._args.push(args[i]);
                        }
                    },
                    onLeave(r) {
                        if (r.toInt32() !== 0) return;
                        // For each arg, treat as potential pointer and
                        // see if dereferencing yields a non-null
                        // pointer-shaped value (top byte typically
                        // 0x10-0x7f for heap addresses under Wine).
                        const candidates = [];
                        for (let i = 0; i < this._args.length; i++) {
                            try {
                                const aPtr = this._args[i];
                                // The OUT slot is a writable local
                                // variable address. Dereference and
                                // check if the value is a plausible
                                // pointer.
                                const v = aPtr.readPointer();
                                const vNum = parseInt(v.toString(), 10) ||
                                             parseInt('0x' + v.toString(16), 16);
                                if (vNum > 0x10000 && vNum < 0xffff0000) {
                                    candidates.push({
                                        arg_idx: i,
                                        ppDev: '0x' + aPtr.toString(16),
                                        pDev:  '0x' + v.toString(16),
                                    });
                                }
                            } catch (e) {}
                        }
                        // The OUT pointer is the one where the saved
                        // value is a fresh device pointer. Save ALL
                        // candidates; orchestrator picks the one with
                        // a vtable that has 100+ ptrs into d3d9-ish
                        // memory.
                        globalThis.__D3D9_DEVICE_CANDIDATES = candidates;
                        if (candidates.length > 0) {
                            globalThis.__D3D9_DEVICE = ptr(candidates[0].pDev);
                        }
                        send({ ev: 'd3d9_device_candidates',
                               candidates: candidates });
                    },
                });
                send({ ev: 'd3d9_i9_hooked' });
            } catch (e) {
                send({ ev: 'd3d9_hook_err',
                       phase: 'D3DCreate9', err: String(e) });
            }
        },
    });
    send({ ev: 'd3d9_chain_armed' });
}

attachD3D9Chain();

// ── Fallback: scan d3d9.dll's data for an existing IDirect3DDevice9 ──
// vtable. Used when the agent attaches AFTER NC2 has already called
// Direct3DCreate9 + CreateDevice (so our chain missed the live
// device). The IDirect3DDevice9 vtable has 119 methods — we scan
// for >=100 consecutive pointers into d3d9 text.

// Helper: call IUnknown::Release on a COM object given its vtable.
function releaseCom(vt, pObj) {
    try {
        const Release = new NativeFunction(
            vt.add(2 * 4).readPointer(), 'uint', ['pointer']);
        Release(pObj);
    } catch (e) {}
}

function findExistingD3D9Device() {
    if (globalThis.__D3D9_DEVICE !== null) return globalThis.__D3D9_DEVICE;
    const d3d = Process.findModuleByName('d3d9.dll')
             || Process.findModuleByName('D3D9.DLL');
    if (d3d === null) return null;
    const ranges = d3d.enumerateRanges('r-x');
    if (ranges.length === 0) return null;
    const textBase = ranges[0].base;
    const textEnd  = ranges[0].base.add(ranges[0].size);
    function inText(p) {
        return p.compare(textBase) >= 0 && p.compare(textEnd) < 0;
    }
    const dataRanges = d3d.enumerateRanges('r--').filter(r =>
        r.base.compare(d3d.base) >= 0
        && r.base.compare(d3d.base.add(d3d.size)) < 0);
    // IDirect3DDevice9 has 119 methods.
    let foundVT = null;
    for (const r of dataRanges) {
        const end = r.base.add(r.size).sub(4 * 120);
        let p = r.base;
        while (p.compare(end) < 0) {
            let n = 0;
            try {
                for (let i = 0; i < 120; i++) {
                    const pi = p.add(i * 4).readPointer();
                    if (inText(pi)) n++; else break;
                }
            } catch (e) { break; }
            if (n >= 100) {
                foundVT = p;
                send({ ev: 'd3d9_vt_scan',
                       vt: '0x' + p.toString(16), n: n });
                break;
            }
            p = p.add(4);
        }
        if (foundVT) break;
    }
    return foundVT;  // vtable, not device. Caller wraps.
}

// Map from hook-name to a factory. Adding a new hook == adding it to
// orchestrator/symbols.py (with implemented=true) AND registering it
// here. Legacy in-EXE-offset hooks (udp_cipher_a/b) used cipherHandler
// — kept available for future use even though the canonical path is
// now Winsock-based.
const HOOK_FACTORIES = {
    udp_recv: udpRecvHandler,
    udp_send: udpSendHandler,
    tcp_send: tcpSendHandler,                // v0.7.0: ws2_32!send
    tcp_connect: connectHandler,             // v0.8.1: ws2_32!connect
    tcp_ioctl: tcpIoctlHandler,              // ntdll!NtDeviceIoControlFile, AFD filter
    // v0.7.0: REAL input hooks. Verified via NC2 import table.
    input_keyboard_state: keyboardStateHandler,   // user32!GetKeyboardState
    input_async_key:      asyncKeyStateHandler,   // user32!GetAsyncKeyState
    // Probes kept just to confirm whatever else fires.
    input_raw_register: rawInputProbeHandler,
    input_raw_get:      getRawInputDataHandler,   // v0.7.1: real injector
    input_get_message:  rawInputProbeHandler,
    udp_cipher_a: cipherHandler,
    udp_cipher_b: cipherHandler,
};

// ---------------------------------------------------------------------
// Installation
// ---------------------------------------------------------------------

const HOOK_TABLE = globalThis.__FRIDA_RE_HOOKS__ || [];
const INSTALLED = [];

function resolveHookAddress(entry, moduleBase) {
    if (entry.mode === 'export') {
        const mod = Process.findModuleByName(entry.module)
                 || Process.findModuleByName(entry.module.toUpperCase());
        if (mod === null) {
            throw new Error(`module not loaded: ${entry.module}`);
        }
        const addr = mod.findExportByName(entry.symbol);
        if (addr === null) {
            throw new Error(`export not found: ${entry.module}!${entry.symbol}`);
        }
        return addr;
    }
    if (entry.mode === 'offset' || entry.offset !== undefined) {
        if (moduleBase === null) {
            throw new Error(`${MODULE_NAME} not loaded — cannot resolve offset`);
        }
        return moduleBase.add(entry.offset);
    }
    throw new Error(`unknown hook mode: ${entry.mode}`);
}

function installHooks(moduleBase) {
    for (const entry of HOOK_TABLE) {
        const factory = HOOK_FACTORIES[entry.name];
        if (factory === undefined) {
            send({ ev: 'hook_error', hook: entry.name,
                   phase: 'install',
                   error: 'no factory registered in agent JS' });
            continue;
        }
        let addr;
        try {
            addr = resolveHookAddress(entry, moduleBase);
        } catch (e) {
            send({ ev: 'hook_error', hook: entry.name,
                   phase: 'resolve', error: String(e) });
            continue;
        }
        try {
            const listener = Interceptor.attach(addr, factory(entry.name));
            INSTALLED.push({ name: entry.name, addr: addr,
                             listener: listener });
        } catch (e) {
            send({ ev: 'hook_error', hook: entry.name,
                   phase: 'install', error: String(e) });
        }
    }
}

const mod = findModule();
if (mod === null) {
    send({ ev: 'ready',
           module: MODULE_NAME, base: null,
           hooks: [],
           error: 'module not loaded yet; agent will idle until first '
                + 'RPC call retries resolution' });
} else {
    installHooks(mod.base);
    send({
        ev: 'ready',
        module: MODULE_NAME,
        base: '0x' + mod.base.toString(16),
        hooks: INSTALLED.map(h => ({
            name: h.name,
            addr: '0x' + h.addr.toString(16),
        })),
    });
}

// ---------------------------------------------------------------------
// RPC surface (control)
// ---------------------------------------------------------------------

function ensureModule() {
    const m = findModule();
    if (m === null) {
        throw new Error('NeocronClient.exe not loaded');
    }
    return m;
}

// Standard D3D9 screenshot pattern for one device pointer. Returns
// { ok:true, w, h, pitch, fmt } and emits a 'd3d9_frame' event with
// the raw pixel bytes, or { err: '...' }. Works under DXVK because
// LockRect is on the sysmem copy, not on the GPU backbuffer.
function _captureOneDevice(device) {
    try {
        const dvt = device.readPointer();
        const GetBackBuffer = new NativeFunction(
            dvt.add(18 * 4).readPointer(), 'int',
            ['pointer','uint','uint','uint','pointer']);
        const CreateOffscreenPlainSurface = new NativeFunction(
            dvt.add(36 * 4).readPointer(), 'int',
            ['pointer','uint','uint','uint','uint','pointer','pointer']);
        const GetRenderTargetData = new NativeFunction(
            dvt.add(32 * 4).readPointer(), 'int',
            ['pointer','pointer','pointer']);
        const ppBB = Memory.alloc(4);
        let hr = GetBackBuffer(device, 0, 0, 0, ppBB);
        if (hr !== 0) return { err: 'GetBackBuffer',
                               hr: '0x' + (hr >>> 0).toString(16) };
        const pBB = ppBB.readPointer();
        const bbvt = pBB.readPointer();
        const GetDesc = new NativeFunction(
            bbvt.add(12 * 4).readPointer(), 'int',
            ['pointer','pointer']);
        const desc = Memory.alloc(0x20);
        hr = GetDesc(pBB, desc);
        if (hr !== 0) {
            releaseCom(bbvt, pBB);
            return { err: 'GetDesc', hr: hr };
        }
        // D3DSURFACE_DESC: fmt@+0, Type@+4, Usage@+8, Pool@+12,
        // MSType@+16, MSQual@+20, Width@+24, Height@+28.
        const fmt = desc.readU32();
        const w = desc.add(0x18).readU32();
        const h = desc.add(0x1c).readU32();
        if (w < 64 || w > 8192 || h < 64 || h > 8192) {
            releaseCom(bbvt, pBB);
            return { err: 'bogus dims', w: w, h: h };
        }
        const ppSys = Memory.alloc(4);
        hr = CreateOffscreenPlainSurface(device, w, h, fmt, 2, ppSys, NULL);
        if (hr !== 0) {
            releaseCom(bbvt, pBB);
            return { err: 'CreateOffscreenPlainSurface',
                     hr: '0x' + (hr >>> 0).toString(16),
                     w: w, h: h, fmt: '0x' + fmt.toString(16) };
        }
        const pSys = ppSys.readPointer();
        const sysvt = pSys.readPointer();
        hr = GetRenderTargetData(device, pBB, pSys);
        if (hr !== 0) {
            releaseCom(sysvt, pSys);
            releaseCom(bbvt, pBB);
            return { err: 'GetRenderTargetData',
                     hr: '0x' + (hr >>> 0).toString(16) };
        }
        const LockRect = new NativeFunction(
            sysvt.add(13 * 4).readPointer(), 'int',
            ['pointer','pointer','pointer','uint']);
        const UnlockRect = new NativeFunction(
            sysvt.add(14 * 4).readPointer(), 'int', ['pointer']);
        const lr = Memory.alloc(8);
        hr = LockRect(pSys, lr, NULL, 0x10);
        if (hr !== 0) {
            releaseCom(sysvt, pSys);
            releaseCom(bbvt, pBB);
            return { err: 'LockRect',
                     hr: '0x' + (hr >>> 0).toString(16) };
        }
        const pitch = lr.readS32();
        const pBits = lr.add(4).readPointer();
        const size = Math.abs(pitch) * h;
        const buf = pBits.readByteArray(size);
        UnlockRect(pSys);
        releaseCom(sysvt, pSys);
        releaseCom(bbvt, pBB);
        send({ ev: 'd3d9_frame', w: w, h: h, pitch: pitch,
               fmt: '0x' + fmt.toString(16) }, buf);
        return { ok: true, w: w, h: h, pitch: pitch,
                 fmt: '0x' + fmt.toString(16) };
    } catch (e) {
        return { err: 'exception', msg: String(e) };
    }
}

rpc.exports = {

    // Status / introspection
    getStatus() {
        const m = findModule();
        return {
            module: MODULE_NAME,
            base: m === null ? null : '0x' + m.base.toString(16),
            installed: INSTALLED.map(h => ({
                name: h.name,
                addr: '0x' + h.addr.toString(16),
            })),
            hookTable: HOOK_TABLE,
            frida: Frida.version,
            arch: Process.arch,
            pageSize: Process.pageSize,
        };
    },

    // Memory I/O
    readMem(addr, n) {
        const p = ptr(addr);
        const bytes = new Uint8Array(p.readByteArray(n));
        // Frida marshals typed arrays as plain arrays of ints to
        // Python, which is what we want for the JSONL.
        return Array.from(bytes);
    },

    writeMem(addr, bytes) {
        const p = ptr(addr);
        p.writeByteArray(bytes);
        return bytes.length;
    },

    // Generic function invocation. ``argTypes`` defaults to all
    // 'pointer' (which under x86-32 is just an int passed by value);
    // callers that need ints/floats supply types explicitly.
    callFunction(addr, args, retType, argTypes) {
        ensureModule();
        const types = argTypes || args.map(() => 'pointer');
        const ret = retType || 'pointer';
        const fn = new NativeFunction(ptr(addr), ret, types);
        const native = args.map((a, i) => {
            if (types[i] === 'pointer') return ptr(a);
            return a;
        });
        const result = fn.apply(null, native);
        if (ret === 'pointer') return '0x' + result.toString(16);
        return result;
    },

    // High-level helper that goes through the (still-unconfirmed)
    // cipher path so the client's Winsock layer sees an encrypted
    // datagram identical to what the game would send. Until we
    // pin both arg signatures, this is a placeholder that returns
    // -1 and surfaces a hook_error event.
    sendUdp(_bytes) {
        send({ ev: 'hook_error', hook: 'sendUdp',
               phase: 'rpc',
               error: 'sendUdp not yet wired — pin cipher signature first' });
        return -1;
    },

    // ── D3D9 screen capture (v0.7.2) ──────────────────────────────
    //
    // Standard screenshot pattern:
    //   GetBackBuffer(0,0,MONO,&bb)
    //   CreateOffscreenPlainSurface(w,h,fmt,SYSTEMMEM,&sys)
    //   GetRenderTargetData(bb, sys)   // copies GPU→sysmem
    //   sys->LockRect(D3DLOCK_READONLY, &lr)
    //   copy lr.pBits[0..pitch*h]
    //   sys->UnlockRect()
    //   sys->Release()
    //   bb->Release()
    //
    // Works under DXVK because LockRect is on the system-mem copy,
    // not on the GPU backbuffer.
    captureD3D9() {
        // v0.8.0: try each candidate from the CreateDevice hook.
        // The OUT-pointer arg index isn't stable across DXVK builds —
        // we just try each pDev candidate until one yields a usable
        // backbuffer.
        const cands = globalThis.__D3D9_DEVICE_CANDIDATES || [];
        if (cands.length === 0) {
            return { err: 'no candidates yet — CreateDevice not seen' };
        }
        const tried = [];
        for (const c of cands) {
            const result = _captureOneDevice(ptr(c.pDev));
            tried.push({ arg_idx: c.arg_idx, pDev: c.pDev,
                         result: result });
            if (result.ok) {
                return { ok: true, used: c, tried: tried };
            }
        }
        return { err: 'all candidates failed', tried: tried };
    },

    // ── RawInput keyboard injection (v0.7.1) ──────────────────────
    //
    // NC2 uses RawInput (verified: 98 GetRawInputData calls in
    // startup). Queue a synthetic keyboard event to be returned by
    // the next GetRawInputData(RID_INPUT, RIM_TYPEKEYBOARD) call.
    //
    // Usage: riPress(vk, durationMs) — queues a down + up pair.
    riPress(vk, durationMs) {
        // Down event first, then up after delay. Each event consumed
        // by ONE GetRawInputData call. We need to time them.
        globalThis.__RI_INJECT_QUEUE.push({ vk: vk, up: false });
        // Schedule up event after durationMs.
        setTimeout(function() {
            globalThis.__RI_INJECT_QUEUE.push({ vk: vk, up: true });
        }, durationMs);
        return { queued: 'down+up', vk: '0x' + vk.toString(16),
                 ms: durationMs };
    },
    riClear() {
        globalThis.__RI_INJECT_QUEUE = [];
        return 0;
    },

    // ── Win32 virtual-key injection (v0.7.0) ──────────────────────
    //
    // Sets a Win32 VK code as pressed/released in the buffer NC2's
    // calls to GetKeyboardState + GetAsyncKeyState see. This is the
    // path NC2 actually uses (verified via import table).
    //
    // Common VK codes:
    //   0x57 = W   0x53 = S   0x41 = A   0x44 = D   0x20 = SPACE
    //   0x0D = RETURN  0x1B = ESCAPE  0x09 = TAB
    vkPress(vk, on) {
        globalThis.__INJECT_KEYS[vk] = on ? 1 : 0;
        return {
            active_keys: Object.keys(globalThis.__INJECT_KEYS)
                .filter(k => globalThis.__INJECT_KEYS[k])
                .map(k => '0x' + parseInt(k).toString(16)),
        };
    },
    vkClear() {
        globalThis.__INJECT_KEYS = {};
        return 0;
    },

    // ── DirectInput keyboard injection ────────────────────────────
    //
    // Press/release a DirectInput key. The keyboard device must have
    // been created AFTER the agent loaded (true under on_load:wait).
    // Common DIK codes:
    //   0x11 = W   0x1F = S   0x1E = A   0x20 = D   0x39 = Space
    //   0x1C = Return  0x01 = Escape
    // See: dinputd.h DIK_* constants.
    dikPress(dik, on) {
        globalThis.__DI8_INJECT[dik] = on ? 1 : 0;
        return {
            device_vtable: DI8_DEVICE_VT,
            active_keys: Object.keys(globalThis.__DI8_INJECT)
                .filter(k => globalThis.__DI8_INJECT[k]),
        };
    },
    dikClear() {
        globalThis.__DI8_INJECT = {};
        return 0;
    },

    // ── Window focus + SendInput (Frida-only, inside Wine) ────────
    //
    // Per [[frida-input-injection-pinned]]: SendInput reaches the
    // Win32 message pump used by the splash menu, where vkPress
    // (GetKeyboardState) and riPress (RawInput) do not. Pairing
    // SendInput with AttachThreadInput + SetForegroundWindow is the
    // only path that works at the splash menu.
    findGameWindow(titleSubstr) {
        const user32 = Process.findModuleByName('user32.dll')
                    || Process.findModuleByName('USER32.DLL');
        if (user32 === null) return { err: 'user32 not loaded' };
        const EnumWindows = new NativeFunction(
            user32.findExportByName('EnumWindows'),
            'int', ['pointer', 'pointer']);
        const GetWindowTextW = new NativeFunction(
            user32.findExportByName('GetWindowTextW'),
            'int', ['pointer', 'pointer', 'int']);
        const IsWindowVisible = new NativeFunction(
            user32.findExportByName('IsWindowVisible'),
            'int', ['pointer']);
        const needle = (titleSubstr || 'Neocron').toLowerCase();
        const found = [];
        const buf = Memory.alloc(512 * 2);
        const cb = new NativeCallback(function(hwnd, _lparam) {
            try {
                if (IsWindowVisible(hwnd) === 0) return 1;
                const n = GetWindowTextW(hwnd, buf, 512);
                if (n > 0) {
                    const title = buf.readUtf16String(n);
                    if (title && title.toLowerCase().indexOf(needle) >= 0) {
                        found.push({ hwnd: '0x' + hwnd.toString(16),
                                     title: title });
                    }
                }
            } catch (e) {}
            return 1;
        }, 'int', ['pointer', 'pointer']);
        EnumWindows(cb, NULL);
        return { found: found };
    },

    focusGameWindow(titleSubstr) {
        const user32 = Process.findModuleByName('user32.dll')
                    || Process.findModuleByName('USER32.DLL');
        const kernel32 = Process.findModuleByName('kernel32.dll')
                      || Process.findModuleByName('KERNEL32.DLL');
        if (user32 === null) return { err: 'user32 not loaded' };
        if (kernel32 === null) return { err: 'kernel32 not loaded' };
        const list = this.findGameWindow(titleSubstr);
        if (list.err) return list;
        if (!list.found || list.found.length === 0) {
            return { err: 'no NC2 window found' };
        }
        const hwnd = ptr(list.found[0].hwnd);
        const GetWindowThreadProcessId = new NativeFunction(
            user32.findExportByName('GetWindowThreadProcessId'),
            'uint32', ['pointer', 'pointer']);
        const GetCurrentThreadId = new NativeFunction(
            kernel32.findExportByName('GetCurrentThreadId'),
            'uint32', []);
        const AttachThreadInput = new NativeFunction(
            user32.findExportByName('AttachThreadInput'),
            'int', ['uint32', 'uint32', 'int']);
        const SetForegroundWindow = new NativeFunction(
            user32.findExportByName('SetForegroundWindow'),
            'int', ['pointer']);
        const BringWindowToTop = new NativeFunction(
            user32.findExportByName('BringWindowToTop'),
            'int', ['pointer']);
        const ShowWindow = new NativeFunction(
            user32.findExportByName('ShowWindow'),
            'int', ['pointer', 'int']);
        const SetFocus = new NativeFunction(
            user32.findExportByName('SetFocus'),
            'pointer', ['pointer']);
        const myTid = GetCurrentThreadId();
        const targetTid = GetWindowThreadProcessId(hwnd, NULL);
        AttachThreadInput(myTid, targetTid, 1);
        ShowWindow(hwnd, 9);  // SW_RESTORE
        BringWindowToTop(hwnd);
        const fg = SetForegroundWindow(hwnd);
        SetFocus(hwnd);
        AttachThreadInput(myTid, targetTid, 0);
        globalThis.__NC2_HWND = hwnd;
        return { ok: true, hwnd: list.found[0].hwnd,
                 title: list.found[0].title, setForeground: fg };
    },

    // SendInput with a VK code. dwFlags bit 0x0002 = KEYEVENTF_KEYUP.
    // On x86 INPUT is 28B: DWORD type @0, KEYBDINPUT @4 (wVk@4
    // wScan@6 dwFlags@8 time@12 dwExtraInfo@16), pad @20..27.
    sendInputKey(vk, durationMs) {
        const user32 = Process.findModuleByName('user32.dll')
                    || Process.findModuleByName('USER32.DLL');
        if (user32 === null) return { err: 'user32 not loaded' };
        const SendInput = new NativeFunction(
            user32.findExportByName('SendInput'),
            'uint32', ['uint32', 'pointer', 'int']);
        function writeInput(buf, vkc, up) {
            buf.writeU32(1);                       // type = INPUT_KEYBOARD
            buf.add(4).writeU16(vkc & 0xffff);    // wVk
            buf.add(6).writeU16(0);               // wScan
            buf.add(8).writeU32(up ? 0x0002 : 0); // dwFlags
            buf.add(12).writeU32(0);              // time
            buf.add(16).writeU32(0);              // dwExtraInfo
            buf.add(20).writeU32(0);              // pad
            buf.add(24).writeU32(0);              // pad
        }
        const bufDown = Memory.alloc(28);
        writeInput(bufDown, vk, false);
        const sentDown = SendInput(1, bufDown, 28);
        const ms = durationMs || 50;
        const result = { sentDown: sentDown, vk: '0x' + vk.toString(16),
                         ms: ms };
        setTimeout(function() {
            const bufUp = Memory.alloc(28);
            writeInput(bufUp, vk, true);
            const sentUp = SendInput(1, bufUp, 28);
            send({ ev: 'send_input_up', vk: '0x' + vk.toString(16),
                   sentUp: sentUp });
        }, ms);
        return result;
    },

    // SendInput sequence of raw VK codes. Each entry is [vk, holdMs].
    // Sends KEYDOWN, sleeps, KEYUP, then pauses before next entry.
    // Use this for forms where WM_CHAR via KEYEVENTF_UNICODE isn't
    // picked up but WM_KEYDOWN/WM_KEYUP is. Refocuses window each
    // char.
    sendInputVkSeq(vks, holdMs, gapMs) {
        const user32 = Process.findModuleByName('user32.dll')
                    || Process.findModuleByName('USER32.DLL');
        if (user32 === null) return { err: 'user32 not loaded' };
        const SendInput = new NativeFunction(
            user32.findExportByName('SendInput'),
            'uint32', ['uint32', 'pointer', 'int']);
        function writeVk(buf, vk, up) {
            buf.writeU32(1);
            buf.add(4).writeU16(vk & 0xffff);
            buf.add(6).writeU16(0);
            buf.add(8).writeU32(up ? 0x0002 : 0);
            buf.add(12).writeU32(0);
            buf.add(16).writeU32(0);
            buf.add(20).writeU32(0);
            buf.add(24).writeU32(0);
        }
        const hMs = holdMs || 30;
        const gMs = gapMs || 30;
        let idx = 0;
        function step() {
            if (idx >= vks.length) {
                send({ ev: 'send_input_vk_done', n: vks.length });
                return;
            }
            const vk = vks[idx];
            const down = Memory.alloc(28);
            writeVk(down, vk, false);
            SendInput(1, down, 28);
            setTimeout(function() {
                const up = Memory.alloc(28);
                writeVk(up, vk, true);
                SendInput(1, up, 28);
                idx++;
                setTimeout(step, gMs);
            }, hMs);
        }
        step();
        return { queued: vks.length, holdMs: hMs, gapMs: gMs };
    },

    // PostMessage WM_LBUTTONDOWN+UP at client-area (x,y). Bypasses
    // focus. Use to click DirectX-rendered buttons that ignore VK.
    // WM_LBUTTONDOWN=0x201, WM_LBUTTONUP=0x202, lParam = MAKELONG(x,y).
    postMessageClick(hwndHex, x, y) {
        const user32 = Process.findModuleByName('user32.dll')
                    || Process.findModuleByName('USER32.DLL');
        if (user32 === null) return { err: 'user32 not loaded' };
        const PostMessageW = new NativeFunction(
            user32.findExportByName('PostMessageW'),
            'int', ['pointer', 'uint32', 'uint32', 'uint32']);
        const SetCursorPos = new NativeFunction(
            user32.findExportByName('SetCursorPos'),
            'int', ['int', 'int']);
        const ClientToScreen = new NativeFunction(
            user32.findExportByName('ClientToScreen'),
            'int', ['pointer', 'pointer']);
        const hwnd = ptr(hwndHex);
        // Move cursor first so the hovered control is correct.
        const ptBuf = Memory.alloc(8);
        ptBuf.writeS32(x);
        ptBuf.add(4).writeS32(y);
        ClientToScreen(hwnd, ptBuf);
        SetCursorPos(ptBuf.readS32(), ptBuf.add(4).readS32());
        const lparam = ((y & 0xffff) << 16) | (x & 0xffff);
        const r1 = PostMessageW(hwnd, 0x0201, 0x0001, lparam);  // WM_LBUTTONDOWN
        const r2 = PostMessageW(hwnd, 0x0202, 0x0000, lparam);  // WM_LBUTTONUP
        return { ok: r1 !== 0 && r2 !== 0, x: x, y: y };
    },

    postMessageRightClick(hwndHex, x, y) {
        const user32 = Process.findModuleByName('user32.dll')
                    || Process.findModuleByName('USER32.DLL');
        if (user32 === null) return { err: 'user32 not loaded' };
        const PostMessageW = new NativeFunction(
            user32.findExportByName('PostMessageW'),
            'int', ['pointer', 'uint32', 'uint32', 'uint32']);
        const SetCursorPos = new NativeFunction(
            user32.findExportByName('SetCursorPos'),
            'int', ['int', 'int']);
        const ClientToScreen = new NativeFunction(
            user32.findExportByName('ClientToScreen'),
            'int', ['pointer', 'pointer']);
        const hwnd = ptr(hwndHex);
        const ptBuf = Memory.alloc(8);
        ptBuf.writeS32(x);
        ptBuf.add(4).writeS32(y);
        ClientToScreen(hwnd, ptBuf);
        SetCursorPos(ptBuf.readS32(), ptBuf.add(4).readS32());
        const lparam = ((y & 0xffff) << 16) | (x & 0xffff);
        const r1 = PostMessageW(hwnd, 0x0204, 0x0002, lparam);  // WM_RBUTTONDOWN
        const r2 = PostMessageW(hwnd, 0x0205, 0x0000, lparam);  // WM_RBUTTONUP
        return { ok: r1 !== 0 && r2 !== 0, x: x, y: y };
    },

    postMessageDoubleClick(hwndHex, x, y) {
        const r1 = this.postMessageClick(hwndHex, x, y);
        const user32 = Process.findModuleByName('user32.dll')
                    || Process.findModuleByName('USER32.DLL');
        const PostMessageW = new NativeFunction(
            user32.findExportByName('PostMessageW'),
            'int', ['pointer', 'uint32', 'uint32', 'uint32']);
        const lparam = ((y & 0xffff) << 16) | (x & 0xffff);
        // WM_LBUTTONDBLCLK = 0x0203 — sent between DOWN-UP-DOWN
        PostMessageW(ptr(hwndHex), 0x0203, 0x0001, lparam);
        PostMessageW(ptr(hwndHex), 0x0202, 0x0000, lparam);
        return { ok: true, x: x, y: y };
    },

    // Returns window client rect dimensions for sizing clicks.
    getWindowClientRect(hwndHex) {
        const user32 = Process.findModuleByName('user32.dll')
                    || Process.findModuleByName('USER32.DLL');
        if (user32 === null) return { err: 'user32 not loaded' };
        const GetClientRect = new NativeFunction(
            user32.findExportByName('GetClientRect'),
            'int', ['pointer', 'pointer']);
        const rect = Memory.alloc(16);
        const r = GetClientRect(ptr(hwndHex), rect);
        return { ok: r !== 0,
                 left: rect.readS32(),
                 top: rect.add(4).readS32(),
                 right: rect.add(8).readS32(),
                 bottom: rect.add(12).readS32() };
    },

    // PostMessage to a specific HWND — bypasses focus entirely.
    // Use to inject keystrokes directly to the NC2 window's queue.
    // type: 0x0100 = WM_KEYDOWN, 0x0101 = WM_KEYUP, 0x0102 = WM_CHAR
    postMessageToWindow(hwndHex, msg, wparam, lparam) {
        const user32 = Process.findModuleByName('user32.dll')
                    || Process.findModuleByName('USER32.DLL');
        if (user32 === null) return { err: 'user32 not loaded' };
        const PostMessageW = new NativeFunction(
            user32.findExportByName('PostMessageW'),
            'int', ['pointer', 'uint32', 'uint32', 'uint32']);
        const r = PostMessageW(ptr(hwndHex), msg, wparam, lparam);
        return { ok: r !== 0, ret: r };
    },

    // Send a string by PostMessage WM_CHAR to a HWND. Avoids focus.
    // Each char becomes a single WM_CHAR with wparam = unicode point.
    postMessageText(hwndHex, text, perCharMs) {
        const user32 = Process.findModuleByName('user32.dll')
                    || Process.findModuleByName('USER32.DLL');
        if (user32 === null) return { err: 'user32 not loaded' };
        const PostMessageW = new NativeFunction(
            user32.findExportByName('PostMessageW'),
            'int', ['pointer', 'uint32', 'uint32', 'uint32']);
        const hwnd = ptr(hwndHex);
        const ms = perCharMs || 25;
        let idx = 0;
        function step() {
            if (idx >= text.length) {
                send({ ev: 'post_message_text_done', n: text.length });
                return;
            }
            const ch = text.charCodeAt(idx);
            // WM_CHAR only — KEYDOWN's wParam is a VK code, not a
            // char code; sending wParam=ord(c) would inject a wrong
            // virtual key first. WM_CHAR carries the actual char.
            PostMessageW(hwnd, 0x0102, ch, 0x00000001);
            idx++;
            setTimeout(step, ms);
        }
        step();
        return { queued: text.length, perCharMs: ms };
    },

    // SendInput-based Unicode text injection. Sends each character
    // as a separate INPUT_KEYBOARD with KEYEVENTF_UNICODE so the
    // target receives WM_CHAR for each one regardless of layout.
    // dwFlags: 0x0004 = KEYEVENTF_UNICODE, 0x0006 = UNICODE|KEYUP.
    sendInputText(text, perCharMs) {
        const user32 = Process.findModuleByName('user32.dll')
                    || Process.findModuleByName('USER32.DLL');
        if (user32 === null) return { err: 'user32 not loaded' };
        const SendInput = new NativeFunction(
            user32.findExportByName('SendInput'),
            'uint32', ['uint32', 'pointer', 'int']);
        const ms = perCharMs || 25;
        const result = { chars: text.length, perCharMs: ms };
        function writeUnicodeInput(buf, ch, up) {
            buf.writeU32(1);                              // INPUT_KEYBOARD
            buf.add(4).writeU16(0);                       // wVk = 0
            buf.add(6).writeU16(ch & 0xffff);             // wScan = unicode
            buf.add(8).writeU32(up ? 0x0006 : 0x0004);   // dwFlags
            buf.add(12).writeU32(0);
            buf.add(16).writeU32(0);
            buf.add(20).writeU32(0);
            buf.add(24).writeU32(0);
        }
        let idx = 0;
        function pumpNext() {
            if (idx >= text.length) {
                send({ ev: 'send_input_text_done', text: text });
                return;
            }
            const ch = text.charCodeAt(idx);
            const bufDown = Memory.alloc(28);
            writeUnicodeInput(bufDown, ch, false);
            SendInput(1, bufDown, 28);
            const bufUp = Memory.alloc(28);
            writeUnicodeInput(bufUp, ch, true);
            SendInput(1, bufUp, 28);
            idx++;
            setTimeout(pumpNext, ms);
        }
        pumpNext();
        return result;
    },

    // Expose the existing scan-fallback. CaptureD3D9() only works
    // when CreateDevice was seen after attach; this RPC scans
    // d3d9.dll for the IDirect3DDevice9 vtable when CreateDevice
    // fired before agent loaded.
    findExistingD3D9DeviceRpc() {
        const vt = findExistingD3D9Device();
        if (vt === null) return { err: 'no IDirect3DDevice9 vtable found' };
        return { vt: '0x' + vt.toString(16) };
    },
};

// Frida v17's Python binding normalizes RPC method names to all-
// lowercase on the wire (e.g. `script.exports_sync.captureD3D9()`
// becomes `captured3d9` over the wire). The agent runtime looks up
// `rpc.exports[name]` with the EXACT wire name, so camelCase keys
// like `captureD3D9` are unreachable. Mirror every camelCase key to
// its lowercase form so callers using either spelling work.
(function _addLowercaseRpcAliases() {
    const ex = rpc.exports;
    for (const k of Object.keys(ex)) {
        const lc = k.toLowerCase();
        if (lc !== k && ex[lc] === undefined) {
            ex[lc] = ex[k];
        }
    }
})();
