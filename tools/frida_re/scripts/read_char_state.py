#!/usr/bin/env python3
"""Locate the live CHARSYS / pool struct in neocronclient.exe by
scanning client memory for known character values, then read the HUD
pool fields off it via Frida.

Hooking the HUD tick fns (FUN_007e87d0 / FUN_0080c660) proved
unreliable (they only run on redraw/change). Instead we memory-scan
writable regions for a distinctive known value — max-HP 9999.0 (f32)
— and dump a window around each hit so the pool struct (buckets
summing to current HP, plus PSI/STA pairs) can be recognised against
DB ground truth.

Usage:
    read_char_state.py scan  [--value 9999 --as f32|u32]
    read_char_state.py dump  --addr 0x... [--before 64 --after 128]
"""
from __future__ import annotations
import argparse
import struct
import sys

try:
    import frida
except ImportError:
    print("frida not installed (pip install frida)", file=sys.stderr)
    sys.exit(3)

JS = r"""
const MODULE = 'NeocronClient.exe';
function modBase() {
    let m = Process.findModuleByName(MODULE);
    if (m) return m.base;
    for (const mm of Process.enumerateModules())
        if (mm.name.toLowerCase() === MODULE.toLowerCase()) return mm.base;
    return null;
}
function bytesToHexPattern(arr) {
    return arr.map(b => ('0' + (b & 0xff).toString(16)).slice(-2)).join(' ');
}
rpc.exports = {
    base() { const b = modBase(); return b ? '0x'+b.toString(16) : null; },
    // Scan all RW regions for a 4-byte pattern; return hit addresses.
    scan(patternHex, cap) {
        const ranges = Process.enumerateRanges('rw-');
        const hits = [];
        for (const r of ranges) {
            try {
                const found = Memory.scanSync(r.base, r.size, patternHex);
                for (const f of found) {
                    hits.push('0x' + f.address.toString(16));
                    if (hits.length >= cap) return hits;
                }
            } catch (e) { /* skip unreadable */ }
        }
        return hits;
    },
    // Dump a window around addr; return decoded u32 + f32 arrays.
    dump(addrHex, before, after) {
        const p = ptr(addrHex).sub(before);
        const n = before + after;
        const u32 = [], f32 = [];
        for (let i = 0; i < n; i += 4) {
            try {
                u32.push(p.add(i).readU32());
                f32.push(p.add(i).readFloat());
            } catch (e) { u32.push(null); f32.push(null); }
        }
        return { start: '0x'+p.toString(16), before: before, u32: u32, f32: f32 };
    },
};
send({ ev: 'ready' });
"""


def connect(host, port):
    dm = frida.get_device_manager()
    dev = dm.add_remote_device(f"{host}:{port}")
    session = dev.attach("Gadget")
    script = session.create_script(JS)
    script.load()
    return session, script


def f32_pattern(v: float) -> str:
    return " ".join(f"{b:02x}" for b in struct.pack("<f", v))


def u32_pattern(v: int) -> str:
    return " ".join(f"{b:02x}" for b in struct.pack("<I", v))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["scan", "dump"])
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=27042)
    ap.add_argument("--value", default="9999")
    ap.add_argument("--as", dest="astype",
                    choices=["f32", "u32", "ascii"], default="f32")
    ap.add_argument("--cap", type=int, default=40)
    ap.add_argument("--addr")
    ap.add_argument("--before", type=int, default=64)
    ap.add_argument("--after", type=int, default=160)
    args = ap.parse_args()

    session, script = connect(args.host, args.port)
    print("module base:", script.exports_sync.base())

    if args.cmd == "scan":
        if args.astype == "f32":
            pat = f32_pattern(float(args.value))
        elif args.astype == "ascii":
            pat = " ".join(f"{b:02x}" for b in args.value.encode("latin1"))
        else:
            pat = u32_pattern(int(args.value))
        print(f"scanning for {args.astype} {args.value}  pattern=[{pat}]")
        hits = script.exports_sync.scan(pat, args.cap)
        print(f"{len(hits)} hits:")
        for h in hits:
            print(" ", h)
        if len(hits) >= args.cap:
            print(f"  (capped at {args.cap})")

    elif args.cmd == "dump":
        d = script.exports_sync.dump(args.addr, args.before, args.after)
        start = int(d["start"], 16)
        print(f"dump around {args.addr} (start {d['start']}, "
              f"target at +0x{args.before:x}):")
        for i, (u, f) in enumerate(zip(d["u32"], d["f32"])):
            off = i * 4 - args.before
            mark = " <== target" if off == 0 else ""
            fr = f"{f:12.3f}" if f is not None else "        None"
            ur = f"{u:11d}" if u is not None else "       None"
            print(f"  +{off:+#06x}  u32={ur}  f32={fr}{mark}")

    session.detach()


if __name__ == "__main__":
    main()
