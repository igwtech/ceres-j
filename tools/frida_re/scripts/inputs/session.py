"""Frida-session attach helper for the input toolkit.

`Session` wraps a Frida script that loads a small JS payload exposing
`sendKey`, `mouseEvent`, `findWindow`, `focus`, and `capture`. Every
keyboard/mouse module here calls into this one session — no agent
proliferation, no Interceptor.attach (so the gadget can't choke).

Usage:

    with attach() as s:
        s.focus_nc2()
        s.key_down(0x57)  # W down
        ...

The session auto-detaches on context exit.
"""

from __future__ import annotations

import contextlib
import time
from typing import Iterator, Optional

try:
    import frida
except ImportError as e:  # pragma: no cover — graceful when frida missing
    frida = None  # type: ignore[assignment]


# JS payload — pure NativeFunction calls, NO Interceptor.attach.
# Everything is RPC-driven from Python.
INJECTOR_JS = r"""
const u32 = Process.findModuleByName('user32.dll');
const k32 = Process.findModuleByName('kernel32.dll');
const SendInput = new NativeFunction(
    u32.findExportByName('SendInput'), 'uint',
    ['uint','pointer','int']);
const FindWindowA = new NativeFunction(
    u32.findExportByName('FindWindowA'), 'pointer',
    ['pointer','pointer']);
const GetForegroundWindow = new NativeFunction(
    u32.findExportByName('GetForegroundWindow'), 'pointer', []);
const SetForegroundWindow = new NativeFunction(
    u32.findExportByName('SetForegroundWindow'), 'int', ['pointer']);
const BringWindowToTop = new NativeFunction(
    u32.findExportByName('BringWindowToTop'), 'int', ['pointer']);
const SetActiveWindow = new NativeFunction(
    u32.findExportByName('SetActiveWindow'), 'pointer', ['pointer']);
const SetFocus = new NativeFunction(
    u32.findExportByName('SetFocus'), 'pointer', ['pointer']);
const ShowWindow = new NativeFunction(
    u32.findExportByName('ShowWindow'), 'int', ['pointer','int']);
const GetClientRect = new NativeFunction(
    u32.findExportByName('GetClientRect'), 'int',
    ['pointer','pointer']);
const ClientToScreen = new NativeFunction(
    u32.findExportByName('ClientToScreen'), 'int',
    ['pointer','pointer']);
const GetSystemMetrics = new NativeFunction(
    u32.findExportByName('GetSystemMetrics'), 'int', ['int']);
const AllowSetForegroundWindow = new NativeFunction(
    u32.findExportByName('AllowSetForegroundWindow'), 'int', ['uint']);
const AttachThreadInput = new NativeFunction(
    u32.findExportByName('AttachThreadInput'), 'int',
    ['uint','uint','int']);
const GetWindowThreadProcessId = new NativeFunction(
    u32.findExportByName('GetWindowThreadProcessId'), 'uint',
    ['pointer','pointer']);
const GetCurrentThreadId = new NativeFunction(
    k32.findExportByName('GetCurrentThreadId'), 'uint', []);
const Sleep = new NativeFunction(
    k32.findExportByName('Sleep'), 'void', ['uint']);
// PostMessageW(hwnd, WM_CHAR=0x0102, charCode, 0) — text entry into
// edit controls / forms (login fields), which read WM_CHAR, not the
// VK key-down events SendInput posts.
const PostMessageW = new NativeFunction(
    u32.findExportByName('PostMessageW'), 'int',
    ['pointer','uint','pointer','pointer']);

const INPUT_KEYBOARD = 1;
const INPUT_MOUSE = 0;
const KEYEVENTF_KEYUP = 0x0002;
const KEYEVENTF_SCANCODE = 0x0008;
const MOUSEEVENTF_MOVE = 0x0001;
const MOUSEEVENTF_LEFTDOWN = 0x0002;
const MOUSEEVENTF_LEFTUP = 0x0004;
const MOUSEEVENTF_RIGHTDOWN = 0x0008;
const MOUSEEVENTF_RIGHTUP = 0x0010;
const MOUSEEVENTF_MIDDLEDOWN = 0x0020;
const MOUSEEVENTF_MIDDLEUP = 0x0040;
const MOUSEEVENTF_WHEEL = 0x0800;
const MOUSEEVENTF_ABSOLUTE = 0x8000;
const MOUSEEVENTF_VIRTUALDESK = 0x4000;
const SM_CXSCREEN = 0;
const SM_CYSCREEN = 1;

function _sendKeyboard(vk, up) {
    const inp = Memory.alloc(28);
    inp.writeU32(INPUT_KEYBOARD);
    inp.add(4).writeU16(vk);
    inp.add(8).writeU32(up ? KEYEVENTF_KEYUP : 0);
    return SendInput(1, inp, 28);
}

function _sendMouse(dx, dy, data, flags) {
    const inp = Memory.alloc(28);
    inp.writeU32(INPUT_MOUSE);
    inp.add(4).writeS32(dx | 0);
    inp.add(8).writeS32(dy | 0);
    inp.add(12).writeU32((data | 0) >>> 0);
    inp.add(16).writeU32(flags >>> 0);
    return SendInput(1, inp, 28);
}

function _attachFocus(hwnd) {
    const tid = GetWindowThreadProcessId(hwnd, NULL);
    const mine = GetCurrentThreadId();
    AttachThreadInput(mine, tid, 1);
    AllowSetForegroundWindow(-1);
    ShowWindow(hwnd, 9);
    BringWindowToTop(hwnd);
    SetForegroundWindow(hwnd);
    SetActiveWindow(hwnd);
    SetFocus(hwnd);
    return tid;
}

rpc.exports = {
    find_window(title) {
        const t = Memory.allocUtf8String(title);
        const hwnd = FindWindowA(NULL, t);
        if (hwnd.isNull()) return null;
        return '0x' + hwnd.toString(16);
    },
    foreground() {
        return '0x' + GetForegroundWindow().toString(16);
    },
    focus(hwndHex) {
        const hwnd = ptr(hwndHex);
        if (hwnd.isNull()) return { ok: false, err: 'null hwnd' };
        _attachFocus(hwnd);
        return { ok: true, fg: '0x' + GetForegroundWindow().toString(16) };
    },
    client_rect(hwndHex) {
        const hwnd = ptr(hwndHex);
        const rc = Memory.alloc(16);
        GetClientRect(hwnd, rc);
        const left   = rc.readS32();
        const top    = rc.add(4).readS32();
        const right  = rc.add(8).readS32();
        const bottom = rc.add(12).readS32();
        // Convert top-left to screen coords.
        const pt = Memory.alloc(8);
        pt.writeS32(0); pt.add(4).writeS32(0);
        ClientToScreen(hwnd, pt);
        const sx = pt.readS32();
        const sy = pt.add(4).readS32();
        return {
            width: right - left, height: bottom - top,
            screen_x: sx, screen_y: sy,
            cx_screen: GetSystemMetrics(SM_CXSCREEN),
            cy_screen: GetSystemMetrics(SM_CYSCREEN),
        };
    },
    key_down(vk) { return _sendKeyboard(vk & 0xff, false); },
    key_up(vk)   { return _sendKeyboard(vk & 0xff, true); },
    key_press(vk, holdMs) {
        _sendKeyboard(vk & 0xff, false);
        Sleep(holdMs | 0);
        _sendKeyboard(vk & 0xff, true);
        return { ok: true };
    },
    mouse_move_abs(x, y) {
        // Absolute coordinates in virtual-desk space (0..65535).
        return _sendMouse(x | 0, y | 0, 0,
                          MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE
                          | MOUSEEVENTF_VIRTUALDESK);
    },
    mouse_move_rel(dx, dy) {
        return _sendMouse(dx | 0, dy | 0, 0, MOUSEEVENTF_MOVE);
    },
    mouse_button(button, down) {
        const m = ({
            left:   down ? MOUSEEVENTF_LEFTDOWN   : MOUSEEVENTF_LEFTUP,
            right:  down ? MOUSEEVENTF_RIGHTDOWN  : MOUSEEVENTF_RIGHTUP,
            middle: down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP,
        })[button];
        if (m === undefined) return { ok: false, err: 'bad button' };
        return _sendMouse(0, 0, 0, m);
    },
    mouse_wheel(delta) {
        return _sendMouse(0, 0, delta | 0, MOUSEEVENTF_WHEEL);
    },
    sleep(ms) { Sleep(ms | 0); return { ok: true }; },
    // Type a string into the given window via WM_CHAR (one char per
    // PostMessageW). Use for login username/password fields and the
    // in-game chat input. perCharMs spaces the chars out.
    type_text(hwndHex, text, perCharMs) {
        const hwnd = ptr(hwndHex);
        for (let i = 0; i < text.length; i++) {
            PostMessageW(hwnd, 0x0102, ptr(text.charCodeAt(i)), NULL);
            if (perCharMs) Sleep(perCharMs | 0);
        }
        return { ok: true, n: text.length };
    },
};
// Frida's Python binding translates a snake_case call (e.g.
// find_window) into a camelCase export lookup (findWindow); some
// versions look it up lowercased (findwindow). Our exports are
// snake_case, so the lookup misses ("unable to find method
// 'findWindow'"). Add camelCase + lowercase aliases for every export
// so each method is reachable regardless of binding convention.
(function _addRpcAliases() {
    const ex = rpc.exports;
    const toCamel = (s) => s.replace(/_([a-z])/g, (_m, c) => c.toUpperCase());
    for (const k of Object.keys(ex)) {
        const cc = toCamel(k);
        if (cc !== k && ex[cc] === undefined) ex[cc] = ex[k];
        const lc = k.replace(/_/g, '').toLowerCase();
        if (lc !== k && ex[lc] === undefined) ex[lc] = ex[k];
    }
})();
send({ ev: 'ready' });
"""


class Session:
    """Wraps a single Frida script loaded into the running gadget."""

    def __init__(self, host: str = "127.0.0.1", port: int = 27042,
                 nc2_title: str = "Neocron Evolution") -> None:
        if frida is None:
            raise RuntimeError(
                "frida not installed — pip install frida")
        self.host = host
        self.port = port
        self.nc2_title = nc2_title
        self._dev = None
        self._sess = None
        self._script = None
        self._ready = False
        self._hwnd: Optional[str] = None

    def attach(self) -> "Session":
        mgr = frida.get_device_manager()
        self._dev = mgr.add_remote_device(f"{self.host}:{self.port}")
        target = next(p.pid for p in self._dev.enumerate_processes()
                      if p.name == "Gadget")
        self._sess = self._dev.attach(target)
        self._script = self._sess.create_script(INJECTOR_JS)
        self._script.on('message',
                        lambda m, d: self._on_message(m, d))
        self._script.load()
        # Wait for the 'ready' send.
        deadline = time.monotonic() + 5.0
        while not self._ready and time.monotonic() < deadline:
            time.sleep(0.02)
        if not self._ready:
            raise RuntimeError("injector JS didn't signal ready")
        return self

    def detach(self) -> None:
        try:
            if self._script is not None:
                self._script.unload()
        except Exception:
            pass
        try:
            if self._sess is not None:
                self._sess.detach()
        except Exception:
            pass
        self._script = None
        self._sess = None

    def _on_message(self, msg: dict, _data: Optional[bytes]) -> None:
        if msg.get('type') == 'send' \
                and isinstance(msg.get('payload'), dict) \
                and msg['payload'].get('ev') == 'ready':
            self._ready = True

    # ── high-level wrappers ─────────────────────────────────────────

    def find_nc2(self) -> Optional[str]:
        hwnd = self._script.exports_sync.find_window(self.nc2_title)
        if hwnd is not None:
            self._hwnd = hwnd
        return hwnd

    def focus_nc2(self) -> dict:
        if self._hwnd is None:
            self.find_nc2()
        if self._hwnd is None:
            return {"ok": False, "err": "NC2 window not found"}
        return self._script.exports_sync.focus(self._hwnd)

    def client_rect(self) -> Optional[dict]:
        if self._hwnd is None:
            self.find_nc2()
        if self._hwnd is None:
            return None
        return self._script.exports_sync.client_rect(self._hwnd)

    def key_press(self, vk: int, hold_ms: int = 60) -> dict:
        return self._script.exports_sync.key_press(vk, hold_ms)

    def key_down(self, vk: int) -> int:
        return self._script.exports_sync.key_down(vk)

    def key_up(self, vk: int) -> int:
        return self._script.exports_sync.key_up(vk)

    def mouse_button(self, button: str, down: bool) -> dict:
        return self._script.exports_sync.mouse_button(button, down)

    def mouse_move_rel(self, dx: int, dy: int) -> int:
        return self._script.exports_sync.mouse_move_rel(dx, dy)

    def mouse_move_abs(self, x: int, y: int) -> int:
        return self._script.exports_sync.mouse_move_abs(x, y)

    def mouse_wheel(self, delta: int) -> int:
        return self._script.exports_sync.mouse_wheel(delta)

    def sleep(self, ms: int) -> None:
        self._script.exports_sync.sleep(ms)


@contextlib.contextmanager
def attach(host: str = "127.0.0.1", port: int = 27042,
           nc2_title: str = "Neocron Evolution") -> Iterator[Session]:
    """Context manager: attach + yield Session, then detach."""
    s = Session(host=host, port=port, nc2_title=nc2_title)
    s.attach()
    try:
        yield s
    finally:
        s.detach()
