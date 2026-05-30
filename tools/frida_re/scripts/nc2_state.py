#!/usr/bin/env python3
"""nc2_state.py — read live character state out of neocronclient.exe.

Resolves the CHARSYS / pool struct base by hooking the HUD pool-tick
functions (which run every frame in-world) and probing candidate
pointers at the documented pool offsets, then reads fields off it.

Commands:
  read   -> JSON of decoded state (hp/psi/sta/pool/map/time/...)
  dump   -> hex window around the resolved base, for pinning new
            field offsets against DB ground truth.

Offsets (NeocronClient.exe image base 0x400000; runtime = base+RVA):
  HUD HP-tick  FUN_007e87d0 -> +0x3e87d0   (gives the CHARSYS ptr)
  HUD HP-sum   FUN_0080c660 -> +0x40c660
CHARSYS sec2 (from current_pool_damage_heal_path):
  +0x3f4/+0x3f8/+0x3fc  HP buckets (f32, sum = current HP)
  +0x42c                HP anchor / max (f32)
PSI/STA/map/time offsets: NOT yet pinned — use `dump` + DB ground
truth (Krafteo: HP 9819, PSI 30/100, STA 100/100, map 1) to fill in
FIELDS below, then they light up automatically in `read`.
"""
from __future__ import annotations
import argparse
import json
import sys
import time

try:
    import frida
except ImportError:
    print(json.dumps({"err": "frida not installed"}))
    sys.exit(3)

# Field map relative to the CHARSYS base. type: 'f32'|'u32'|'u16'.
# Fill `off` (int) as offsets get pinned; None = not yet known.
FIELDS = {
    "hp_b0":     (0x3f4, "f32"),
    "hp_b1":     (0x3f8, "f32"),
    "hp_b2":     (0x3fc, "f32"),
    "hp_anchor": (0x42c, "f32"),
    # --- TODO: pin these via `dump` + DB ground truth ---
    "psi":       (None,  "f32"),
    "psi_max":   (None,  "f32"),
    "sta":       (None,  "f32"),
    "sta_max":   (None,  "f32"),
    "map_id":    (None,  "u32"),
    "game_time": (None,  "u32"),
}

JS = r"""
const MODULE='NeocronClient.exe';
function modBase(){let m=Process.findModuleByName(MODULE);if(m)return m.base;
 for(const x of Process.enumerateModules())if(x.name.toLowerCase()===MODULE.toLowerCase())return x.base;return null;}
globalThis.__BASE=null;          // resolved CHARSYS base
function probe(p){ // does p look like the CHARSYS (sane HP buckets)?
 try{const f0=p.add(0x3f4).readFloat(),f1=p.add(0x3f8).readFloat(),f2=p.add(0x3fc).readFloat(),a=p.add(0x42c).readFloat();
  const s=f0+f1+f2; const ok=isFinite(s)&&s>1&&s<1e6&&isFinite(a)&&a>0&&a<1e6;
  return ok?{ptr:'0x'+p.toString(16),sum:s,anchor:a}:null;}catch(e){return null;}}
rpc.exports={
 arm(secs){
  const b=modBase(); if(!b)return {ok:false,err:'module not found'};
  const targets=[b.add(0x3e87d0), b.add(0x40c660)];
  const ls=[];
  for(const a of targets){ try{ ls.push(Interceptor.attach(a,{onEnter(args){
    if(globalThis.__BASE)return;
    const ctx=this.context;
    const cands=[ctx.ecx,ctx.eax,ctx.edx];
    try{for(let i=1;i<=4;i++)cands.push(ctx.esp.add(i*4).readPointer());}catch(e){}
    for(const c of cands){const r=probe(c); if(r){globalThis.__BASE=c;
      send({ev:'base',base:r.ptr,sum:r.sum,anchor:r.anchor});break;}}
  }})); }catch(e){send({ev:'armerr',err:String(e)});} }
  return {ok:true, base:'0x'+b.toString(16), hooks:targets.map(x=>'0x'+x.toString(16))};
 },
 base(){return globalThis.__BASE?'0x'+globalThis.__BASE.toString(16):null;},
 readAt(off,kind){ if(!globalThis.__BASE)return null; const p=globalThis.__BASE.add(off);
  try{ if(kind==='f32')return p.readFloat(); if(kind==='u16')return p.readU16(); return p.readU32();}catch(e){return null;} },
 dump(before,after){ if(!globalThis.__BASE)return null; const p=globalThis.__BASE.sub(before); const n=before+after; const u=[],f=[];
  for(let i=0;i<n;i+=4){try{u.push(p.add(i).readU32());f.push(p.add(i).readFloat());}catch(e){u.push(null);f.push(null);}}
  return {start:'0x'+p.toString(16),before:before,u32:u,f32:f}; },
};
send({ev:'ready'});
"""


def connect(host, port):
    dm = frida.get_device_manager()
    dev = dm.add_remote_device(f"{host}:{port}")
    sess = dev.attach("Gadget")
    sc = sess.create_script(JS)
    sc.load()
    return sess, sc


def resolve_base(sc, secs):
    sc.exports_sync.arm(secs)
    end = time.time() + secs
    while time.time() < end:
        b = sc.exports_sync.base()
        if b:
            return b
        time.sleep(0.2)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["read", "dump"])
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=27042)
    ap.add_argument("--wait", type=float, default=6.0,
                    help="seconds to wait for the HUD tick to resolve the base")
    ap.add_argument("--before", type=int, default=64)
    ap.add_argument("--after", type=int, default=192)
    args = ap.parse_args()

    sess, sc = connect(args.host, args.port)
    base = resolve_base(sc, args.wait)
    out = {"base": base}
    if base is None:
        out["err"] = "could not resolve CHARSYS base (is the player in-world?)"
        print(json.dumps(out))
        sess.detach()
        return

    if args.cmd == "read":
        for name, (off, kind) in FIELDS.items():
            out[name] = None if off is None else sc.exports_sync.read_at(off, kind)
        # Convenience: current HP = sum of the three buckets.
        b = [out.get("hp_b0"), out.get("hp_b1"), out.get("hp_b2")]
        out["hp"] = round(sum(b), 1) if all(x is not None for x in b) else None
        out["hp_max"] = out.get("hp_anchor")
        print(json.dumps(out))
    elif args.cmd == "dump":
        d = sc.exports_sync.dump(args.before, args.after)
        print(f"CHARSYS base {base}  (window starts {d['start']}, "
              f"base at +0x{args.before:x})")
        for i, (u, f) in enumerate(zip(d["u32"], d["f32"])):
            o = i * 4 - args.before
            fr = f"{f:11.3f}" if f is not None else "       None"
            ur = f"{u:11d}" if u is not None else "      None"
            tag = "  <== CHARSYS base" if o == 0 else ""
            print(f"  +{o:+#06x}  u32={ur}  f32={fr}{tag}")
    sess.detach()


if __name__ == "__main__":
    main()
