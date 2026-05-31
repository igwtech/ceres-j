'use strict';
/* Calibration: resolve how Ghidra file-VA maps to runtime, and what the
 * real runtime prologue looks like. The orchestrator hooks the cipher at
 * base+0x160090 (FIRES) so that's a known-good function. */
const m = Process.findModuleByName('NeocronClient.exe');
const base = m.base, size = m.size;
function dump(p, n) { try { const b = new Uint8Array(p.readByteArray(n)); let s=[];
    for (let i=0;i<b.length;i++) s.push((b[i]<16?'0':'')+b[i].toString(16)); return s.join(' ');
} catch(e){ return '<'+e+'>'; } }
function cnt(pat){ try { return Memory.scanSync(base, size, pat).length; } catch(e){ return 'ERR:'+e; } }

send({ ev: 'calib', what: 'base', v: base.toString(), size: size });
send({ ev: 'calib', what: 'MZ@base', v: dump(base, 4) });
// cipher: orchestrator offset 0x160090 (file VA 0x560090) — known good
send({ ev: 'calib', what: 'base+0x160090 (cipher)', v: dump(base.add(0x160090), 16) });
// my (wrong) handler guess
send({ ev: 'calib', what: 'base+0xcfab0 (handler guess)', v: dump(base.add(0xcfab0), 16) });
// how many functions use the MSVC SEH prologue at runtime?
send({ ev: 'calib', what: 'count 55 8b ec 6a ff 68', v: cnt('55 8b ec 6a ff 68') });
send({ ev: 'calib', what: 'count 55 8b ec', v: cnt('55 8b ec') });
send({ ev: 'calib', what: 'count 8b ff 55 8b ec', v: cnt('8b ff 55 8b ec') });
// the cipher's first bytes as a pattern — find ALL its occurrences to see
// if base+0x160090 prologue is unique/findable
const cipherPro = dump(base.add(0x160090), 8);
send({ ev: 'calib', what: 'cipher prologue 8B', v: cipherPro });
send({ ev: 'calib', what: 'count cipher prologue', v: cnt(cipherPro) });
// Try: is the handler maybe at a DIFFERENT delta? scan for the SEH push
// low bytes the disasm gave (0d 37 = app handler SEH), any context.
send({ ev: 'calib', what: 'count 68 0d 37', v: cnt('68 0d 37') });
send({ ev: 'calib', what: 'count 6a ff 68 0d 37', v: cnt('6a ff 68 0d 37') });
