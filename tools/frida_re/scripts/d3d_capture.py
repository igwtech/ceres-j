#!/usr/bin/env python3
"""Capture the live D3D9 backbuffer of the running neocronclient.exe
(under DXVK) for visual verification, WITHOUT needing the agent to be
attached at device-creation time.

Approach (safe — no blind vtable calls on guessed pointers):
  1. Locate the IDirect3DDevice9 vtable by scanning d3d9.dll data for
     a run of ~119 pointers into d3d9.dll .text.
  2. Hook vtable[17] (Present). On the next frame, args[0] is the live
     device pointer — capture and store it, then detach.
  3. From the RPC thread, run the standard screenshot path on that
     device: GetBackBuffer(18) -> CreateOffscreenPlainSurface(36) ->
     GetRenderTargetData(32) -> Surface::LockRect(13) -> read pixels.
  4. Save as PNG (PIL) / BMP fallback.

Usage: d3d_capture.py [--host 127.0.0.1] [--port 27042] [--out frame.png]
"""
from __future__ import annotations
import argparse
import struct
import sys
import time

try:
    import frida
except ImportError:
    print("frida not installed", file=sys.stderr)
    sys.exit(3)

JS = r"""
const D3D9 = 'd3d9.dll';
globalThis.__DEV = null;
globalThis.__presentListener = null;
// One-shot "capture on the next Present" state. Reading the backbuffer
// from INSIDE a Present call guarantees a complete frame and a device
// that is NOT mid-Reset() — so GetRenderTargetData/LockRect can't fault
// on invalidated surfaces (the crash that destroyed the Frida session).
globalThis.__capPending = false;
globalThis.__capListener = null;

function findDeviceVtable() {
    // DXVK's d3d9.dll has many r-x ranges and its device-method
    // pointers are scattered across all of them, so "is code" =
    // "anywhere inside the module span" rather than just the first
    // .text range. Try every loaded d3d9.dll (game-dir DXVK + system).
    const mods = Process.enumerateModules().filter(
        m => m.name.toLowerCase() === 'd3d9.dll');
    for (const d3d of mods) {
        const mBase = d3d.base, mEnd = d3d.base.add(d3d.size);
        const inCode = (p) => p.compare(mBase) >= 0 && p.compare(mEnd) < 0;
        const data = d3d.enumerateRanges('r--');
        for (const r of data) {
            const end = r.base.add(r.size).sub(4 * 120);
            let p = r.base;
            while (p.compare(end) < 0) {
                let n = 0;
                try {
                    for (let i = 0; i < 120; i++) {
                        if (inCode(p.add(i * 4).readPointer())) n++; else break;
                    }
                } catch (e) { break; }
                if (n >= 100) {
                    send({ ev:'vt_found', mod:d3d.path,
                           vt:'0x'+p.toString(16), n:n });
                    return p;
                }
                p = p.add(4);
            }
        }
    }
    return null;
}

// True only if p points inside a loaded d3d9.dll image. Used to
// validate a candidate device pointer's vtable BEFORE making any
// native vtable call — calling through a bogus vtable segfaults the
// whole client (a JS try/catch cannot catch a native fault).
function vtableInD3d9(vt) {
    for (const m of Process.enumerateModules()) {
        if (m.name.toLowerCase() !== 'd3d9.dll') continue;
        if (vt.compare(m.base) >= 0 && vt.compare(m.base.add(m.size)) < 0)
            return true;
    }
    return false;
}

function captureOneDevice(device) {
    try {
        // Guard: a real IDirect3DDevice9's first dword is its vtable,
        // which must live in d3d9.dll. If not, this isn't a device —
        // bail BEFORE any native call so we don't crash the client.
        let dvtProbe;
        try { dvtProbe = device.readPointer(); }
        catch (e) { return { err: 'unreadable device ptr' }; }
        if (!vtableInD3d9(dvtProbe)) {
            return { err: 'not a device — vtable 0x' +
                     dvtProbe.toString(16) + ' not in d3d9.dll' };
        }
        const dvt = device.readPointer();
        const GetBackBuffer = new NativeFunction(dvt.add(18*4).readPointer(),
            'int', ['pointer','uint','uint','uint','pointer']);
        const CreateOffscreen = new NativeFunction(dvt.add(36*4).readPointer(),
            'int', ['pointer','uint','uint','uint','uint','pointer','pointer']);
        const GetRTData = new NativeFunction(dvt.add(32*4).readPointer(),
            'int', ['pointer','pointer','pointer']);
        const ppBB = Memory.alloc(4);
        let hr = GetBackBuffer(device, 0, 0, 0, ppBB);
        if (hr !== 0) return { err:'GetBackBuffer', hr:'0x'+(hr>>>0).toString(16) };
        const pBB = ppBB.readPointer(), bbvt = pBB.readPointer();
        const GetDesc = new NativeFunction(bbvt.add(12*4).readPointer(),
            'int', ['pointer','pointer']);
        const desc = Memory.alloc(0x20);
        hr = GetDesc(pBB, desc);
        if (hr !== 0) return { err:'GetDesc', hr:hr };
        const fmt = desc.readU32();
        const w = desc.add(0x18).readU32(), h = desc.add(0x1c).readU32();
        if (w < 64 || w > 8192 || h < 64 || h > 8192)
            return { err:'bogus dims', w:w, h:h };
        const ppSys = Memory.alloc(4);
        hr = CreateOffscreen(device, w, h, fmt, 2, ppSys, NULL);
        if (hr !== 0) return { err:'CreateOffscreen', hr:'0x'+(hr>>>0).toString(16),
                               w:w, h:h, fmt:'0x'+fmt.toString(16) };
        const pSys = ppSys.readPointer(), sysvt = pSys.readPointer();
        hr = GetRTData(device, pBB, pSys);
        if (hr !== 0) return { err:'GetRenderTargetData', hr:'0x'+(hr>>>0).toString(16) };
        const LockRect = new NativeFunction(sysvt.add(13*4).readPointer(),
            'int', ['pointer','pointer','pointer','uint']);
        const UnlockRect = new NativeFunction(sysvt.add(14*4).readPointer(),
            'int', ['pointer']);
        const lr = Memory.alloc(8);
        hr = LockRect(pSys, lr, NULL, 0x10);
        if (hr !== 0) return { err:'LockRect', hr:'0x'+(hr>>>0).toString(16) };
        const pitch = lr.readS32(), pBits = lr.add(4).readPointer();
        const size = Math.abs(pitch) * h;
        const buf = pBits.readByteArray(size);
        UnlockRect(pSys);
        send({ ev:'d3d9_frame', w:w, h:h, pitch:pitch,
               fmt:'0x'+fmt.toString(16) }, buf);
        return { ok:true, w:w, h:h, pitch:pitch, fmt:'0x'+fmt.toString(16) };
    } catch (e) { return { err:'exception', msg:String(e) }; }
}

// Attach (once) a Present hook that, when a capture is pending, reads
// the backbuffer from the LIVE device passed to Present (args[0]) — a
// guaranteed-valid, mid-frame device. Idempotent: reuses the listener.
function ensureCaptureHook() {
    if (globalThis.__capListener) return { ok:true, reused:true };
    const vt = findDeviceVtable();
    if (!vt) return { ok:false, err:'device vtable not found' };
    const present = vt.add(17 * 4).readPointer();
    globalThis.__capListener = Interceptor.attach(present, {
        onEnter(args) {
            if (!globalThis.__capPending) return;
            globalThis.__capPending = false;
            // Frame is fully rendered, about to be presented; the device
            // is not being Reset() right now -> safe to copy the surface.
            try { captureOneDevice(args[0]); } catch (e) {
                send({ ev:'cap_err', msg:String(e) });
            }
        }
    });
    return { ok:true, present:'0x'+present.toString(16) };
}

rpc.exports = {
    // Robust path: capture on the next rendered frame (reset-safe).
    captureOnPresent() {
        const h = ensureCaptureHook();
        if (!h.ok) return h;
        globalThis.__capPending = true;
        return { ok:true, armed:true };
    },
    armPresentHook() {
        const vt = findDeviceVtable();
        if (!vt) return { ok:false, err:'device vtable not found' };
        const present = vt.add(17 * 4).readPointer();
        globalThis.__presentListener = Interceptor.attach(present, {
            onEnter(args) {
                if (globalThis.__DEV === null) {
                    globalThis.__DEV = args[0];   // 'this' = device
                    send({ ev:'device', dev:'0x'+args[0].toString(16) });
                }
            }
        });
        return { ok:true, vtable:'0x'+vt.toString(16),
                 present:'0x'+present.toString(16) };
    },
    haveDevice() { return globalThis.__DEV ? '0x'+globalThis.__DEV.toString(16) : null; },
    captureDev(devHex) { return captureOneDevice(ptr(devHex)); },
    capture() {
        if (globalThis.__presentListener) {
            globalThis.__presentListener.detach();
            globalThis.__presentListener = null;
        }
        if (!globalThis.__DEV) return { err:'no device captured yet' };
        return captureOneDevice(globalThis.__DEV);
    },
};
send({ ev:'ready' });
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=27042)
    ap.add_argument("--out", default="/tmp/nc2_frame.png")
    ap.add_argument("--device", help="capture directly from a known "
                    "device pointer (skip Present-hook discovery)")
    args = ap.parse_args()

    dm = frida.get_device_manager()
    dev = dm.add_remote_device(f"{args.host}:{args.port}")
    session = dev.attach("Gadget")
    script = session.create_script(JS)

    frame = {}

    def on_message(msg, data):
        if msg.get("type") == "send":
            p = msg["payload"]
            ev = p.get("ev")
            if ev == "device":
                print("captured live device:", p["dev"])
            elif ev == "d3d9_frame":
                frame["meta"] = p
                frame["data"] = data
        elif msg.get("type") == "error":
            print("JS error:", msg.get("description"))

    script.on("message", on_message)
    script.load()

    if args.device:
        res = script.exports_sync.capture_dev(args.device)
        print("captureDev:", res)
        time.sleep(0.4)
        if "data" in frame:
            m = frame["meta"]
            save_image(frame["data"], m["w"], m["h"], m["pitch"], args.out)
        else:
            print("no frame bytes received")
        session.detach()
        return

    arm = script.exports_sync.arm_present_hook()
    print("armPresentHook:", arm)
    if not arm.get("ok"):
        return

    # Wait for a Present to land the device pointer.
    devhex = None
    for _ in range(50):
        devhex = script.exports_sync.have_device()
        if devhex:
            break
        time.sleep(0.1)
    if not devhex:
        print("no Present call seen — is the client rendering / focused?")
        return

    res = script.exports_sync.capture()
    print("capture():", res)
    time.sleep(0.4)  # let the frame message arrive

    if "data" in frame:
        m = frame["meta"]
        save_image(frame["data"], m["w"], m["h"], m["pitch"], args.out)
    else:
        print("no frame bytes received")
    session.detach()


def save_image(buf, w, h, pitch, out):
    # D3D9 X8R8G8B8 / A8R8G8B8: in-memory bytes per pixel = B,G,R,X.
    rows = []
    ap = abs(pitch)
    for y in range(h):
        ry = (h - 1 - y) if pitch < 0 else y
        rows.append(buf[ry * ap: ry * ap + w * 4])
    rgb = bytearray(w * h * 3)
    o = 0
    for row in rows:
        for x in range(0, w * 4, 4):
            b, g, r = row[x], row[x + 1], row[x + 2]
            rgb[o] = r; rgb[o + 1] = g; rgb[o + 2] = b; o += 3
    try:
        from PIL import Image
        Image.frombytes("RGB", (w, h), bytes(rgb)).save(out)
        print(f"saved PNG {w}x{h} -> {out}")
    except ImportError:
        bmp = out.rsplit(".", 1)[0] + ".bmp"
        write_bmp(bmp, w, h, rgb)
        print(f"PIL missing; saved BMP {w}x{h} -> {bmp}")


def write_bmp(path, w, h, rgb):
    row_pad = (4 - (w * 3) % 4) % 4
    data = bytearray()
    for y in range(h - 1, -1, -1):
        for x in range(w):
            o = (y * w + x) * 3
            data += bytes((rgb[o + 2], rgb[o + 1], rgb[o]))
        data += b"\x00" * row_pad
    fsize = 54 + len(data)
    hdr = b"BM" + struct.pack("<IHHI", fsize, 0, 0, 54)
    hdr += struct.pack("<IiiHHIIiiII", 40, w, h, 1, 24, 0, len(data),
                       2835, 2835, 0, 0)
    with open(path, "wb") as f:
        f.write(hdr + data)


if __name__ == "__main__":
    main()
