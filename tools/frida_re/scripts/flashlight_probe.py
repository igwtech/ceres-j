#!/usr/bin/env python3
"""Live D3D9 light-state probe — captures EXACTLY what NC2's flashlight
implementation does to IDirect3DDevice9 during a session.

Ghidra static scan (FindD3D9LightSites.java vs the x64 binary) showed
NC2 calls D3D9 SetLight/LightEnable ZERO times — the engine does NOT
use fixed-function lighting. The flashlight contribution flows through
shader constants + an extra render-target (shadow map). So this probe
hooks the actual data path:

    23  CreateTexture            — flag DEPTHSTENCIL / RENDERTARGET
                                    RTs (shadow-map allocation)
    37  SetRenderTarget          — track every RT bind (shadow pass
                                    enters/exits)
    51  SetLight                 — kept as a ZERO-event baseline; if
                                    it ever fires, the assumption is
                                    wrong and we should retune
    107 SetPixelShader           — every pixel shader switch
    109 SetPixelShaderConstantF  — change-detected per start register
                                    (emits only when bytes actually
                                    flip), so 60 FPS of unchanged
                                    constants doesn't drown the channel

The flashlight ON/OFF user action then maps directly to "which start
register changed at the moment we pressed the key". That's the
register slot we'll override from inside the engine_injector to
re-shape the flashlight.

Light state is deduplicated per index; we emit only DISTINCT struct
contents (each index keeps a SHA-like signature of its last D3DLIGHT9
contents — if it's identical, we don't re-emit). That way 60 FPS of
unchanged lights doesn't drown the channel; a real per-frame
repositioning shows up as a stream of distinct events.

Usage:
    cd ceres-j/tools/frida_re/scripts
    ./flashlight_probe.py --host 127.0.0.1 --port 27042 \\
                          --duration 12 --out /tmp/flashlight.jsonl

Run while NC2 is in a dark zone (Spider Cave) with the flashlight ON,
strafe/aim for a few seconds, then read the jsonl — the per-frame
behaviour of the engine's light setup is now ground truth.
"""
from __future__ import annotations
import argparse
import json
import sys
import time

try:
    import frida
except ImportError:
    print("frida not installed", file=sys.stderr)
    sys.exit(3)

JS = r"""
const PS = Process.pointerSize;
function vmethod(vt, slot, ret, args) {
    return new NativeFunction(vt.add(slot * PS).readPointer(), ret, args);
}

function findDeviceVtable() {
    const mods = Process.enumerateModules().filter(
        m => m.name.toLowerCase() === 'd3d9.dll');
    for (const d3d of mods) {
        const mBase = d3d.base, mEnd = d3d.base.add(d3d.size);
        const inCode = (p) => p.compare(mBase) >= 0 && p.compare(mEnd) < 0;
        for (const r of d3d.enumerateRanges('r--')) {
            const end = r.base.add(r.size).sub(PS * 120);
            let p = r.base;
            while (p.compare(end) < 0) {
                let n = 0;
                try {
                    for (let i = 0; i < 120; i++) {
                        if (inCode(p.add(i * PS).readPointer())) n++; else break;
                    }
                } catch (e) { break; }
                if (n >= 100) {
                    send({ ev: 'vt_found', mod: d3d.path,
                           vt: '0x' + p.toString(16), n: n });
                    return p;
                }
                p = p.add(PS);
            }
        }
    }
    return null;
}

// Quick scan: walk the d3d9.dll image for a live IDirect3DDevice9
// pointer whose first qword is the vtable we just located. Caches the
// device pointer for any subsequent calls — but the probe itself
// doesn't need it; the vtable is what we hook.
function readVec3(p)  { return [p.readFloat(),
                                p.add(4).readFloat(),
                                p.add(8).readFloat()]; }
function readColor(p) { return [p.readFloat(),
                                p.add(4).readFloat(),
                                p.add(8).readFloat(),
                                p.add(12).readFloat()]; }

// D3DLIGHT9 = 104 B both arches: Type u32, 3 D3DCOLORVALUE
// (4 floats each), Position 3f, Direction 3f, Range, Falloff,
// Attenuation0..2, Theta, Phi.
function readLight(p) {
    try {
        const type = p.readU32();
        const out = {
            type: type,                                            // 1=point 2=spot 3=dir
            diffuse:  readColor(p.add(4)),
            specular: readColor(p.add(20)),
            ambient:  readColor(p.add(36)),
            position: readVec3(p.add(52)),
            direction:readVec3(p.add(64)),
            range:        p.add(76).readFloat(),
            falloff:      p.add(80).readFloat(),
            atten0:       p.add(84).readFloat(),
            atten1:       p.add(88).readFloat(),
            atten2:       p.add(92).readFloat(),
            theta:        p.add(96).readFloat(),      // inner cone (rad)
            phi:         p.add(100).readFloat(),      // outer cone (rad)
        };
        return out;
    } catch (e) { return { err: String(e) }; }
}

// Cheap content signature — string-join floats truncated to 3
// decimals. Identical lights between frames hash identically.
function sigLight(L) {
    if (L.err) return 'err';
    const r = (a) => a.map(x => x.toFixed(3)).join(',');
    return [
        L.type, r(L.diffuse), r(L.position), r(L.direction),
        L.range.toFixed(3), L.falloff.toFixed(3),
        L.atten0.toFixed(4), L.atten1.toFixed(4), L.atten2.toFixed(4),
        L.theta.toFixed(4), L.phi.toFixed(4),
    ].join('|');
}

globalThis.__VT = null;
globalThis.__lastSig = {};      // SetLight: index -> sig
globalThis.__lastEnable = {};   // LightEnable: index -> bool
globalThis.__lastPSC = {};      // SetPixelShaderConstantF: start -> sha
globalThis.__lastPS = null;     // SetPixelShader: last shader ptr
globalThis.__count = { SetLight: 0, LightEnable: 0,
                       SetRT: 0, CreateTexture: 0,
                       SetPixelShader: 0,
                       SetPixelShaderConstantF: 0 };

const D3DUSAGE_RENDERTARGET = 0x00000001;
const D3DUSAGE_DEPTHSTENCIL = 0x00000002;

// 32-bit FNV-1a over `n` bytes at `p`. Cheap content fingerprint
// so we can dedupe "constants haven't changed since last call".
function fnv32(p, n) {
    let h = 0x811c9dc5;
    for (let i = 0; i < n; i++) {
        h = (h ^ p.add(i).readU8()) >>> 0;
        h = Math.imul(h, 0x01000193) >>> 0;
    }
    return h;
}

function armHooks() {
    const vt = findDeviceVtable();
    if (vt === null) {
        send({ ev: 'vt_not_found' });
        return false;
    }
    globalThis.__VT = vt;

    // SetLight(DWORD Index, const D3DLIGHT9*)
    const SetLight_slot = vt.add(51 * PS).readPointer();
    Interceptor.attach(SetLight_slot, {
        onEnter(args) {
            const idx = args[1].toInt32();
            const pL  = args[2];
            const L   = readLight(pL);
            const sig = sigLight(L);
            globalThis.__count.SetLight++;
            if (globalThis.__lastSig[idx] === sig) return;  // dedup
            globalThis.__lastSig[idx] = sig;
            send({ ev: 'SetLight', t: Date.now(), idx: idx, light: L });
        },
    });

    // LightEnable(DWORD Index, BOOL bEnable)
    const LightEnable_slot = vt.add(53 * PS).readPointer();
    Interceptor.attach(LightEnable_slot, {
        onEnter(args) {
            const idx = args[1].toInt32();
            const en  = args[2].toInt32() !== 0;
            globalThis.__count.LightEnable++;
            if (globalThis.__lastEnable[idx] === en) return;
            globalThis.__lastEnable[idx] = en;
            send({ ev: 'LightEnable', t: Date.now(), idx: idx, enable: en });
        },
    });

    // SetRenderTarget(DWORD RTIndex, IDirect3DSurface9* pRT)
    const SetRT_slot = vt.add(37 * PS).readPointer();
    Interceptor.attach(SetRT_slot, {
        onEnter(args) {
            const idx = args[1].toInt32();
            const pRT = args[2];
            globalThis.__count.SetRT++;
            send({ ev: 'SetRenderTarget', t: Date.now(),
                   idx: idx, rt: '0x' + pRT.toString(16) });
        },
    });

    // CreateTexture(W,H,Levels,Usage,Format,Pool, ppTex, pSharedHandle)
    // — only emit when Usage flags DEPTHSTENCIL or RENDERTARGET, which
    // is the signature of a shadow map / dynamic RT allocation.
    const CreateTexture_slot = vt.add(23 * PS).readPointer();
    Interceptor.attach(CreateTexture_slot, {
        onEnter(args) {
            const usage  = args[4].toInt32();
            globalThis.__count.CreateTexture++;
            if ((usage & (D3DUSAGE_RENDERTARGET |
                          D3DUSAGE_DEPTHSTENCIL)) === 0) return;
            send({ ev: 'CreateTexture_RT', t: Date.now(),
                   w: args[1].toInt32(), h: args[2].toInt32(),
                   levels: args[3].toInt32(),
                   usage: '0x' + (usage >>> 0).toString(16),
                   fmt:   '0x' + (args[5].toInt32() >>> 0).toString(16),
                   pool:  args[6].toInt32() });
        },
    });

    // SetPixelShader(IDirect3DPixelShader9* pShader)
    // — flag every shader switch (very low frequency in NC2)
    const SetPS_slot = vt.add(107 * PS).readPointer();
    Interceptor.attach(SetPS_slot, {
        onEnter(args) {
            const pS = args[1];
            globalThis.__count.SetPixelShader++;
            const k = '0x' + pS.toString(16);
            if (globalThis.__lastPS === k) return;
            globalThis.__lastPS = k;
            send({ ev: 'SetPixelShader', t: Date.now(), ps: k });
        },
    });

    // SetPixelShaderConstantF(UINT StartRegister,
    //                         const float* pConstantData,
    //                         UINT Vector4fCount)
    // — emit only when bytes at (start..start+count) actually changed
    // since the last call for the same StartRegister.
    const SetPSC_slot = vt.add(109 * PS).readPointer();
    Interceptor.attach(SetPSC_slot, {
        onEnter(args) {
            const start = args[1].toInt32();
            const pD    = args[2];
            const cnt   = args[3].toInt32();
            globalThis.__count.SetPixelShaderConstantF++;
            if (cnt <= 0 || cnt > 256 || pD.isNull()) return;
            const nBytes = cnt * 16;
            let sig;
            try { sig = fnv32(pD, nBytes); }
            catch (e) { return; }
            const key = start + ':' + cnt;
            if (globalThis.__lastPSC[key] === sig) return;
            globalThis.__lastPSC[key] = sig;
            // Emit the first vec4 verbatim so we can eyeball it
            // (positions / colors / cone params live here).
            const v0 = [pD.readFloat(),
                        pD.add(4).readFloat(),
                        pD.add(8).readFloat(),
                        pD.add(12).readFloat()];
            send({ ev: 'PSC_change', t: Date.now(),
                   start: start, count: cnt,
                   v0: v0,
                   sig: '0x' + sig.toString(16) });
        },
    });

    send({ ev: 'hooks_armed', vt: '0x' + vt.toString(16) });
    return true;
}

rpc.exports = {
    arm: function () { return armHooks(); },
    counters: function () { return globalThis.__count; },
};
"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=27042)
    ap.add_argument("--duration", type=float, default=12.0,
                    help="seconds to capture before stopping")
    ap.add_argument("--out", default="/tmp/flashlight.jsonl",
                    help="write events as JSONL")
    args = ap.parse_args()

    print(f"[probe] connecting to gadget at {args.host}:{args.port}")
    mgr = frida.get_device_manager().add_remote_device(
        f"{args.host}:{args.port}")
    session = mgr.attach("Gadget")
    script = session.create_script(JS)
    fh = open(args.out, "w")

    def on_message(msg, data):
        if msg.get("type") == "send":
            p = msg.get("payload") or {}
            fh.write(json.dumps(p) + "\n")
            fh.flush()
            ev = p.get("ev", "?")
            if ev in ("vt_found", "vt_not_found", "hooks_armed",
                      "CreateTexture_RT", "SetRenderTarget"):
                print(f"  {ev}: {json.dumps(p, separators=(',', ':'))}")
            elif ev == "SetLight":
                L = p["light"]
                tname = {1: "POINT", 2: "SPOT", 3: "DIR"}.get(L["type"], f"?{L['type']}")
                print(f"  L{p['idx']} {tname}  "
                      f"pos=({L['position'][0]:+.2f},{L['position'][1]:+.2f},{L['position'][2]:+.2f}) "
                      f"dir=({L['direction'][0]:+.2f},{L['direction'][1]:+.2f},{L['direction'][2]:+.2f}) "
                      f"rng={L['range']:.1f}  "
                      f"att=({L['atten0']:.4f},{L['atten1']:.4f},{L['atten2']:.4f}) "
                      f"theta={L['theta']:.3f} phi={L['phi']:.3f}")
            elif ev == "LightEnable":
                print(f"  L{p['idx']} ENABLE={p['enable']}")
            elif ev == "SetPixelShader":
                print(f"  PixelShader -> {p['ps']}")
            elif ev == "PSC_change":
                v = p['v0']
                print(f"  PSC c{p['start']}..c{p['start']+p['count']-1} "
                      f"v0=({v[0]:+.3f},{v[1]:+.3f},{v[2]:+.3f},{v[3]:+.3f}) "
                      f"sig={p['sig']}")
        elif msg.get("type") == "error":
            print("[probe] script error:", msg)

    script.on("message", on_message)
    script.load()

    armed = script.exports_sync.arm()
    if not armed:
        print("[probe] device vtable not located — is d3d9.dll loaded?")
        return 2

    print(f"[probe] capturing for {args.duration:.1f}s — go to a dark "
          f"zone with the flashlight ON, strafe & aim")
    time.sleep(args.duration)

    counters = script.exports_sync.counters()
    print(f"[probe] done. total calls:  {counters}")
    print(f"[probe] log: {args.out}")
    session.detach()
    fh.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
