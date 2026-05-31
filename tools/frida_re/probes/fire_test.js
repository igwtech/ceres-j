'use strict';
/* Firing diagnostic for a SECONDARY Frida script on the harness session.
 * Q: does Interceptor.onEnter fire at all from here? Hook a guaranteed-hot
 * Windows export (GetTickCount) using the API the proven _agent.js uses
 * (module-object.findExportByName), plus the candidate app-handler. */
const MODULE = 'NeocronClient.exe';
const IMAGE_BASE = ptr('0x400000');
const base = Process.findModuleByName(MODULE).base;
function at(va) { return base.add(ptr(va).sub(IMAGE_BASE)); }
send({ ev: 'fire_ready', base: base.toString() });

function modExport(mod, sym) {
    try {
        const m = Process.findModuleByName(mod) || Process.getModuleByName(mod);
        if (!m) return null;
        // newer frida: module object has findExportByName / getExportByName
        if (typeof m.findExportByName === 'function') return m.findExportByName(sym);
        if (typeof m.getExportByName === 'function') return m.getExportByName(sym);
    } catch (e) { send({ ev: 'modexport_err', mod: mod, sym: sym, err: '' + e }); }
    return null;
}

// 1) GetTickCount — pure firing sanity (called constantly by the game loop)
['kernel32.dll'].forEach(function (mod) {
    ['GetTickCount', 'GetSystemTimeAsFileTime', 'QueryPerformanceCounter'].forEach(function (sym) {
        const a = modExport(mod, sym);
        if (!a) { send({ ev: 'export_null', mod: mod, sym: sym }); return; }
        try {
            Interceptor.attach(a, { onEnter() {
                const k = '__' + sym;
                if (!globalThis[k]) { globalThis[k] = 1; send({ ev: 'fired', who: sym }); }
            }});
            send({ ev: 'hook_ok', name: sym, addr: a.toString() });
        } catch (e) { send({ ev: 'hook_fail', name: sym, err: '' + e }); }
    });
});

// 2) recvfrom (ws2_32) — the actual UDP recv, definitely hot
const rf = modExport('ws2_32.dll', 'recvfrom') || modExport('wsock32.dll', 'recvfrom');
if (rf) {
    try { Interceptor.attach(rf, { onEnter() {
        if (!globalThis.__rf) { globalThis.__rf = 1; send({ ev: 'fired', who: 'recvfrom' }); }
    }}); send({ ev: 'hook_ok', name: 'recvfrom', addr: rf.toString() }); }
    catch (e) { send({ ev: 'hook_fail', name: 'recvfrom', err: '' + e }); }
} else { send({ ev: 'export_null', mod: 'ws2_32', sym: 'recvfrom' }); }
