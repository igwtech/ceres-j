'use strict';
/* weapon_gate_probe.js — runtime test of the server-authoritative weapon gate.
 *
 * Static (Ghidra) found, in the PlayerAction dispatcher FUN_0064ec90:
 *   case 0x10 (weapon-change): if (incoming_weapon_id != self.+0x334)
 *                                  -> "Weapon change denied" + rollback
 * and FUN_0064e820 clears self.+0x334 = 0 (holster). +0x334 is a per-entity
 * 16-bit in-hand weapon id.
 *
 * This probe watches, at runtime against whichever server is connected:
 *   - every PlayerAction dispatch: msg type (puVar1[0]) + subtype (puVar1[3])
 *   - for type 0x15 / subtype 0x10: incoming weapon id vs current +0x334
 *     (i.e. would this draw be ACCEPTED or DENIED?)
 *   - the holster/clear path firing
 *
 * Calling convention (from decompile): __thiscall-ish — `this` = ECX (in_ECX),
 * explicit stack arg param_1 = args[0].
 */
const MODULE = 'NeocronClient.exe';
const IMAGE_BASE = ptr('0x400000');
const m = Process.findModuleByName(MODULE) || Process.findModuleByName('neocronclient.exe');
const base = m.base;
function at(va) {
    const s = (va.toString().startsWith('0x')) ? va : ('0x' + va);
    return base.add(ptr(s).sub(IMAGE_BASE));
}
send({ ev: 'wg_ready', base: base.toString() });

function u8(p)  { try { return p.readU8();  } catch (e) { return -1; } }
function u16(p) { try { return p.readU16(); } catch (e) { return -1; } }
function rp(p)  { try { return p.readPointer(); } catch (e) { return ptr(0); } }

// current authoritative in-hand weapon id for `this` (ECX):
//   iVar9 = *(*(ECX-0x150)+4);  val = *(u16*)(ECX + iVar9 + 0x334)
function curWeapon(ecx) {
    try {
        const a = rp(ecx.sub(0x150));
        if (a.isNull()) return { ok: false };
        const iVar9 = rp(a.add(4));            // offset base
        const addr = ecx.add(iVar9).add(0x334);
        return { ok: true, off: iVar9.toInt32(), val: u16(addr), addr: addr.toString() };
    } catch (e) { return { ok: false, err: '' + e }; }
}

// PlayerAction dispatcher
try {
    Interceptor.attach(at('0064ec90'), {
        onEnter(args) {
            const ecx = this.context.ecx;
            const param1 = args[0];                 // uint* param_1
            const body = rp(param1.add(4));         // puVar1 = param_1[1]
            if (body.isNull()) return;
            const t = u8(body);                     // *puVar1  (msg type)
            const sub = u8(body.add(3));            // puVar1[3] (subtype)
            // Only surface weapon-relevant traffic to keep the log readable.
            if (t === 0x15 && sub === 0x10) {
                const incoming = u16(body.add(4));  // *(short*)(puVar1+4)
                const cur = curWeapon(ecx);
                send({ ev: 'weapon_change', incoming: incoming,
                       cur: cur.val, off: cur.off, addr: cur.addr,
                       verdict: (incoming === cur.val) ? 'ACCEPT' : 'DENY' });
            } else if (t === 0x15) {
                send({ ev: 'ctrl15', sub: sub });
            } else {
                send({ ev: 'dispatch', type: t });
            }
        }
    });
    send({ ev: 'hook_ok', name: 'FUN_0064ec90' });
} catch (e) { send({ ev: 'hook_fail', name: 'FUN_0064ec90', err: '' + e }); }

// Holster / clear-in-hand (sets +0x334 = 0)
try {
    Interceptor.attach(at('0064e820'), {
        onEnter() {
            const ecx = this.context.ecx;
            const cur = curWeapon(ecx);
            send({ ev: 'holster_clear', cur_before: cur.val });
        }
    });
    send({ ev: 'hook_ok', name: 'FUN_0064e820' });
} catch (e) { send({ ev: 'hook_fail', name: 'FUN_0064e820', err: '' + e }); }
