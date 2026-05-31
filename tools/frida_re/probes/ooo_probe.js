'use strict';
/* Watch the OOO-list seed/drain. Resolve ProcessGuaranteedMsg via the
 * "Packet loss" string-xref, then find AddMsgToOOOList as a CALL target
 * inside PGM (handle backward/negative rel32). Hook both:
 *  - PGM: log seq, isResp, the window field before/after, and ret.
 *  - AddMsgToOOOList: log every seq SEEDED into the OOO list (= will be
 *    re-requested every ~255ms until drained). */
const m = Process.findModuleByName('NeocronClient.exe');
const base = m.base, size = m.size;
function dump(p,n){try{const b=new Uint8Array(p.readByteArray(n));let s=[];for(let i=0;i<b.length;i++)s.push((b[i]<16?'0':'')+b[i].toString(16));return s.join(' ');}catch(e){return '<'+e+'>';}}
function scan(pat,start,len){try{return Memory.scanSync(start||base,len||size,pat);}catch(e){return [];}}
function ascii(s){let h=[];for(let i=0;i<s.length;i++)h.push(s.charCodeAt(i).toString(16).padStart(2,'0'));return h.join(' ');}
function leAddr(p){const v=parseInt(p.toString(),16);return [v&0xff,(v>>8)&0xff,(v>>16)&0xff,(v>>24)&0xff].map(b=>b.toString(16).padStart(2,'0')).join(' ');}
function rdU16(p){try{return p.readU16();}catch(e){return null;}}
function rdPtr(p){try{return p.readPointer();}catch(e){return null;}}
function hx(v){return v===null?'null':'0x'+(v>>>0).toString(16);}
function npToNum(p){return parseInt(p.toString(),16);}

send({ev:'probe_ready',base:base.toString()});

// PGM
let pgm=null;
{ const sh=scan(ascii('Packet loss'));
  if (sh.length){ const x=scan('68 '+leAddr(sh[0].address));
    if (x.length){ const pr=scan('55 8b ec 6a ff 68', x[0].address.sub(0x6000),0x6000);
      if (pr.length) pgm=pr[pr.length-1].address; } } }
send({ev:'calib',pgm:pgm?pgm.toString():null});

function clientRecord(ecx,cn){const arr=rdPtr(ecx.add(0x14));return arr?rdPtr(arr.add(cn*4)):null;}

// Hook PGM. Dump rec[0..0x10] (find the advancing field), deref rec+0xc
// (likely a pointer to the window struct), and watch the OOO count +0x4014.
let first=true;
if (pgm){ try {
  Interceptor.attach(pgm,{
    onEnter(args){ this.ecx=this.context.ecx; this.cn=args[0].toInt32()&0xff;
      this.seq=args[1].toInt32()&0x7ff; this.isResp=args[2].toInt32()&0xff;
      const r=clientRecord(this.ecx,this.cn); this.r=r;
      this.head=r?dump(r,0x10):null;
      const wcp=r?rdPtr(r.add(0xc)):null;          // *(rec+0xc) — window struct ptr?
      this.wcDeref=wcp?dump(wcp,0x10):null;
      this.oooN=r?rdU16(r.add(0x4014)):null; },
    onLeave(ret){
      const ev={ev:'pgm',seq:this.seq,isResp:this.isResp,ret:ret.toInt32()&0xff,
        head_before:this.head, head_after:this.r?dump(this.r,0x10):null,
        ooo:hx(this.oooN), ooo_after:hx(this.r?rdU16(this.r.add(0x4014)):null)};
      if (first){ ev.wc_deref=this.wcDeref; first=false; }
      send(ev); }
  });
  send({ev:'hook_ok',name:'pgm'});
}catch(e){ send({ev:'hook_fail',name:'pgm',err:''+e}); } }
