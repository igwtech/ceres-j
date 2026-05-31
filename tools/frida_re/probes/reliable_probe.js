'use strict';
/* Self-calibrating probe v2: find the REAL reliable-window field (the one
 * that advances) + the re-request source (AddMsgToOOOList). PGM resolved
 * by "Packet loss" string xref; AddMsgToOOOList = the call right after that
 * xref inside PGM. Dumps the clientRecord so we can spot the advancing
 * window field (exp@+0xc read 0xca0 constant — likely wrong offset). */
const m = Process.findModuleByName('NeocronClient.exe');
const base = m.base, size = m.size;
function dump(p,n){ try{const b=new Uint8Array(p.readByteArray(n));let s=[];for(let i=0;i<b.length;i++)s.push((b[i]<16?'0':'')+b[i].toString(16));return s.join(' ');}catch(e){return '<'+e+'>';} }
function scan(pat,start,len){ try{return Memory.scanSync(start||base,len||size,pat);}catch(e){return [];} }
function ascii(s){let h=[];for(let i=0;i<s.length;i++)h.push(s.charCodeAt(i).toString(16).padStart(2,'0'));return h.join(' ');}
function leAddr(p){const v=parseInt(p.toString(),16);return [v&0xff,(v>>8)&0xff,(v>>16)&0xff,(v>>24)&0xff].map(b=>b.toString(16).padStart(2,'0')).join(' ');}
function rdPtr(p){try{return p.readPointer();}catch(e){return null;}}
function rdU16(p){try{return p.readU16();}catch(e){return null;}}
function hx(v){return v===null?'null':'0x'+(v>>>0).toString(16);}

function strAddr(s){const h=scan(ascii(s));return h.length?h[0].address:null;}
function pushXref(a){const x=scan('68 '+leAddr(a));return x.length?x[0].address:null;}
function prologueBefore(a){const p=scan('55 8b ec 6a ff 68',a.sub(0x6000),0x6000);return p.length?p[p.length-1].address:null;}

send({ev:'probe_ready',base:base.toString()});

// PGM via "Packet loss"
const plStr = strAddr('Packet loss');
const plXref = plStr ? pushXref(plStr) : null;
const pgmEntry = plXref ? prologueBefore(plXref) : null;
send({ev:'calib',pgm:pgmEntry?pgmEntry.toString():null});

// AddMsgToOOOList = a `e8 rel32` CALL in PGM body whose target has the SEH
// prologue. Scan the whole PGM body and report ALL such targets.
let oooEntry = null;
const callTargets = [];
if (pgmEntry) {
    for (let off = 0; off < 0x700; off++) {
        const a = pgmEntry.add(off);
        try {
            if (a.readU8() === 0xe8) {
                const rel = a.add(1).readS32();
                const tgt = a.add(5).add(rel);
                if (tgt.compare(base) > 0 && tgt.compare(base.add(size)) < 0) {
                    const pro = dump(tgt, 8);
                    if (pro.startsWith('55 8b ec 6a ff 68') &&
                        callTargets.indexOf(tgt.toString()) < 0) {
                        callTargets.push(tgt.toString());
                        if (!oooEntry) oooEntry = tgt; // first SEH-prologue callee
                    }
                }
            }
        } catch(e) {}
    }
}
send({ev:'calib',pgm_call_targets: callTargets, ooo: oooEntry?oooEntry.toString():null});

// re-request TIMER via "No AliveReps"
const naStr = strAddr('No AliveReps');
const naXref = naStr ? pushXref(naStr) : null;
const timerEntry = naXref ? prologueBefore(naXref) : null;
send({ev:'calib',timer:timerEntry?timerEntry.toString():null});

function clientRecord(ecx,cn){const arr=rdPtr(ecx.add(0x14));return arr?rdPtr(arr.add(cn*4)):null;}

// ── PGM: dump clientRecord to find the advancing window field ──
if (pgmEntry) { try {
    Interceptor.attach(pgmEntry, {
        onEnter(args){
            this.cn=args[0].toInt32()&0xff; this.seq=args[1].toInt32()&0x7ff; this.isResp=args[2].toInt32()&0xff;
            this.rec=clientRecord(this.context.ecx,this.cn);
            this.recBefore=this.rec?dump(this.rec,0x20):null;
        },
        onLeave(ret){
            send({ev:'pgm',seq:this.seq,isResp:this.isResp,ret:ret.toInt32()&0xff,
                  rec_before:this.recBefore,
                  rec_after:this.rec?dump(this.rec,0x20):null});
        }
    });
    send({ev:'hook_ok',name:'pgm'});
} catch(e){ send({ev:'hook_fail',name:'pgm',err:''+e}); } }

// ── AddMsgToOOOList: the seqs the client decides are MISSING -> re-requests
if (oooEntry) { try {
    Interceptor.attach(oooEntry, { onEnter(args){
        send({ev:'ooo_add',cn:args[0].toInt32()&0xff,seq:args[1].toInt32()&0xffff}); } });
    send({ev:'hook_ok',name:'ooo'});
} catch(e){ send({ev:'hook_fail',name:'ooo',err:''+e}); } }

// ── timer: each firing emits a re-request burst
if (timerEntry) { try {
    Interceptor.attach(timerEntry, { onEnter(args){ send({ev:'timer_fire'}); } });
    send({ev:'hook_ok',name:'timer'});
} catch(e){ send({ev:'hook_fail',name:'timer',err:''+e}); } }
