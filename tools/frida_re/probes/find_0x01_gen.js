'use strict';
/* Find WHERE the client generates the 0x13-wrapped [01][seq] retransmit-
 * request. Hook ws2_32 sendto, decrypt the outgoing buffer (LFSR+CFB),
 * and when a sub-packet wrapper==0x01 is present, capture the backtrace.
 * Map return addresses back via the string-xref-derived delta so we can
 * name the generator in Ghidra. */
const m = Process.findModuleByName('NeocronClient.exe');
const base = m.base, size = m.size;

// ── LFSR+CFB decrypt (mirror of WireEncrypt) ──
function lfsr(state, input){
    let s = state[0] & 0xFFFF, out = 0;
    for (let bit=0; bit<8; bit++){
        const hi=(s>>8)&0xFF, lo=s&0xFF, db=(input>>bit)&1;
        const fb=((hi>>6)^(hi>>5)^(hi>>3)^lo^db)&1;
        s=((s<<1)|fb)&0xFFFF; out|=(fb<<(7-bit));
    }
    state[0]=s; return out&0xFF;
}
function decrypt(wire){
    if (wire.length<4) return null;
    const seed=wire[0]|(wire[1]<<8), st=[seed];
    lfsr(st,wire[1]); lfsr(st,wire[2]);
    let prev=wire[3]; const out=[];
    for (let i=4;i<wire.length;i++){ out.push(lfsr(st,prev)^wire[i]); prev=wire[i]; }
    return out;
}

// derive delta via "Packet loss" -> ProcessGuaranteedMsg (Ghidra 0x4d0f60)
function scan(pat,start,len){try{return Memory.scanSync(start||base,len||size,pat);}catch(e){return [];}}
function ascii(s){let h=[];for(let i=0;i<s.length;i++)h.push(s.charCodeAt(i).toString(16).padStart(2,'0'));return h.join(' ');}
function leAddr(p){const v=parseInt(p.toString(),16);return [v&0xff,(v>>8)&0xff,(v>>16)&0xff,(v>>24)&0xff].map(b=>b.toString(16).padStart(2,'0')).join(' ');}
let delta = 0;
{
    const sh=scan(ascii('Packet loss'));
    if (sh.length){ const x=scan('68 '+leAddr(sh[0].address));
        if (x.length){ const pr=scan('55 8b ec 6a ff 68', x[0].address.sub(0x6000),0x6000);
            if (pr.length){ delta = parseInt(pr[pr.length-1].address.toString(),16) - 0x4d0f60; } } }
}
send({ev:'ready', base:base.toString(), delta:'0x'+(delta>>>0).toString(16)});

const sendto = Process.getModuleByName('ws2_32.dll').findExportByName('sendto');
const seen = {};
const stat = { sends:0, p13:0, has01:0, transport01:0 };
Interceptor.attach(sendto, {
    onEnter(args){
        const len = args[2].toInt32();
        if (len < 4 || len > 1500) return;
        let wire;
        try { wire = Array.from(new Uint8Array(args[1].readByteArray(len))); } catch(e){ return; }
        const p = decrypt(wire);
        if (!p) return;
        stat.sends++;
        if (p[0] === 0x01) { stat.transport01++; }   // transport ConnectRequest
        if (p[0] !== 0x13) return;
        stat.p13++;
        // walk sub-packets, look for wrapper 0x01
        let i=5, has01=false, seqs=[];
        while (i+3 <= p.length){
            const sl = p[i]|(p[i+1]<<8); i+=2;
            if (sl<=0 || i+sl>p.length) break;
            const w = p[i];
            if (w===0x01){ has01=true; seqs.push(p[i+1]|(p[i+2]<<8)); }
            i+=sl;
        }
        if (!has01) return;
        stat.has01++;
        // capture backtrace mapped to Ghidra VAs
        const bt = Thread.backtrace(this.context, Backtracer.ACCURATE)
            .map(a => {
                const off = parseInt(a.toString(),16) - parseInt(base.toString(),16);
                if (off<0 || off>size) return a.toString()+'(ext)';
                const gh = (parseInt(a.toString(),16) - delta) >>> 0;
                return '0x'+gh.toString(16);   // Ghidra VA
            });
        const key = bt.slice(0,4).join(',');
        if (seen[key]) { seen[key]++; return; }   // dedupe identical stacks
        seen[key]=1;
        send({ev:'gen_0x01', seqs:seqs, ghidra_backtrace: bt.slice(0,10)});
    }
});
send({ev:'hooked_sendto', addr: sendto.toString()});
setInterval(function(){ send({ev:'stat', stat: stat}); }, 2000);
