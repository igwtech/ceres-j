'use strict';
/* Calibrate Ghidra-VA -> runtime via string xrefs. GameNetMgr.cpp funcs
 * reference unique log strings; strings aren't relocated. Find string in
 * memory, find the `push <strAddr>` xref, walk back to the function
 * prologue → runtime entry of a KNOWN Ghidra function → delta. */
const m = Process.findModuleByName('NeocronClient.exe');
const base = m.base, size = m.size;
function dump(p, n) { try { const b = new Uint8Array(p.readByteArray(n)); let s=[];
    for (let i=0;i<b.length;i++) s.push((b[i]<16?'0':'')+b[i].toString(16)); return s.join(' ');
} catch(e){ return '<'+e+'>'; } }
function scan(pat, start, len) { try { return Memory.scanSync(start||base, len||size, pat); } catch(e){ return []; } }
function ascii(s){ let h=[]; for(let i=0;i<s.length;i++) h.push(s.charCodeAt(i).toString(16).padStart(2,'0')); return h.join(' '); }
function leAddr(p){ const v=p.toUInt32?p.toUInt32():parseInt(p.toString(),16);
    return [v&0xff,(v>>8)&0xff,(v>>16)&0xff,(v>>24)&0xff].map(b=>b.toString(16).padStart(2,'0')).join(' '); }

send({ ev:'c2', what:'base', v: base.toString() });

// Ghidra VAs of functions that reference each string (from disasm):
//   "Packet loss detected" -> ProcessGuaranteedMsg  G=0x4d0f60
//   "Bad MUID"             -> app handler            G=0x4cfab0
//   "No AliveReps"         -> timer FUN_0055d830     G=0x55d830
const STRINGS = [
  { s:'Packet loss', G:0x4d0f60, name:'ProcessGuaranteedMsg' },
  { s:'Bad MUID',    G:0x4cfab0, name:'apphandler' },
  { s:'No AliveReps',G:0x55d830, name:'timer_0x55d830' },
  { s:'GameNetMgr',  G:null,     name:'srcpath' },
  { s:'AddClient',   G:0x4cd1b0, name:'AddClient' },
];

for (const item of STRINGS) {
    const hits = scan(ascii(item.s));
    if (!hits.length) { send({ ev:'c2', what:'string "'+item.s+'"', v:'NOT FOUND' }); continue; }
    const sAddr = hits[0].address;
    send({ ev:'c2', what:'string "'+item.s+'" @', v: sAddr.toString()+' ('+hits.length+' hits)' });
    // find push <sAddr>: byte 0x68 followed by LE32(sAddr)
    const xrefs = scan('68 ' + leAddr(sAddr));
    send({ ev:'c2', what:'  push-xrefs to "'+item.s+'"', v: xrefs.map(x=>x.address.toString()).join(',') || 'none' });
    if (!xrefs.length || item.G === null) continue;
    // walk back from first xref to the nearest preceding SEH prologue
    const x = xrefs[0].address;
    const winStart = x.sub(0x6000);
    const pros = scan('55 8b ec 6a ff 68', winStart, 0x6000);
    if (!pros.length) { send({ ev:'c2', what:'  prologue before xref', v:'none in 0x6000 window' }); continue; }
    const entry = pros[pros.length - 1].address;   // nearest preceding
    const delta = parseInt(entry.toString(),16) - item.G;
    send({ ev:'c2', what:'  >>> entry of '+item.name, v: entry.toString()+'  prologue='+dump(entry,12) });
    send({ ev:'c2', what:'  >>> DELTA (runtime - ghidra)', v: '0x'+(delta>>>0).toString(16) });
    // verify: apply delta to all 4 funcs, dump prologues
    const FUNCS = { apphandler:0x4cfab0, pgm:0x4d0f60, ooo:0x4cde70, addclient:0x4cd1b0 };
    for (const k in FUNCS) {
        const rt = base.add((FUNCS[k] + delta) - parseInt(base.toString(),16) + parseInt(base.toString(),16));
        const a = ptr((FUNCS[k] + delta) >>> 0);
        send({ ev:'c2', what:'    '+k+' @ '+a.toString(), v: dump(a, 12) });
    }
    break; // one good calibration is enough
}
