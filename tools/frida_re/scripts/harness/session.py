"""Single Frida connection to the NC2 gadget that drives input,
captures D3D9 frames, and reads character state — by loading the three
proven agent scripts (input / d3d / state) into ONE session. The JS is
imported from the existing standalone tools so there's no duplication.

Coexists with the orchestrator (separate connection) — verified.
"""
from __future__ import annotations
import importlib.util
import os
import time

import frida

from . import keys as _keys

_HERE = os.path.dirname(os.path.abspath(__file__))
_SCRIPTS = os.path.dirname(_HERE)  # tools/frida_re/scripts


def _load_module(pyfile: str):
    spec = importlib.util.spec_from_file_location(
        os.path.basename(pyfile)[:-3], pyfile)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

# Pull the agent JS + helpers straight from the standalone tools.
_inp = _load_module(os.path.join(_SCRIPTS, "inputs", "session.py"))
_d3d = _load_module(os.path.join(_SCRIPTS, "d3d_capture.py"))
_st = _load_module(os.path.join(_SCRIPTS, "nc2_state.py"))
INJECTOR_JS = _inp.INJECTOR_JS
D3D_JS = _d3d.JS
STATE_JS = _st.JS
save_image = _d3d.save_image
STATE_FIELDS = _st.FIELDS


class Session:
    def __init__(self, host: str = "127.0.0.1", port: int = 27042,
                 nc2_title: str = "Neocron Evolution"):
        dm = frida.get_device_manager()
        self._dev = dm.add_remote_device(f"{host}:{port}")
        self._sess = self._dev.attach("Gadget")
        self.title = nc2_title
        self._frame = None
        self._hwnd = None
        self.input = self._load(INJECTOR_JS)
        self.d3d = self._load(D3D_JS, self._on_d3d)
        self.state = self._load(STATE_JS)
        self._state_armed = False
        self.last_error = None

    def alive(self) -> bool:
        """True while the Frida session/scripts are usable."""
        try:
            self.state.exports_sync.base()
            return True
        except Exception as e:
            self.last_error = f"alive: {e}"
            return False

    def _load(self, js, on_message=None):
        sc = self._sess.create_script(js)
        if on_message:
            sc.on("message", on_message)
        sc.load()
        return sc

    def _on_d3d(self, msg, data):
        if msg.get("type") == "send" and \
                msg["payload"].get("ev") == "d3d9_frame":
            self._frame = (msg["payload"], data)

    # ── Input ────────────────────────────────────────────────────────
    def focus(self):
        hwnd = self.input.exports_sync.find_window(self.title)
        if hwnd:
            self.input.exports_sync.focus(hwnd)
            self._hwnd = hwnd
        return hwnd

    def key(self, vk: int, hold_ms: int = 60):
        """Tap (down+up) a single VK; hold_ms holds it that long."""
        self.input.exports_sync.key_press(vk, hold_ms)

    def keys(self, seq, gap: float = 0.35):
        """Press a sequence of VKs with a gap between each."""
        for vk in seq:
            self.key(vk)
            time.sleep(gap)

    def hold(self, vk: int, ms: int):
        """Hold a key for `ms` (e.g. walk forward: hold(keys.W, 8000))."""
        self.input.exports_sync.key_press(vk, ms)

    def _ensure_hwnd(self):
        if self._hwnd is None:
            self.focus()
        return self._hwnd

    def type_text(self, text: str, per_char_ms: int = 20) -> bool:
        """Type a string via WM_CHAR — for login fields and chat input
        (edit controls read WM_CHAR, not VK key-downs)."""
        hwnd = self._ensure_hwnd()
        if not hwnd:
            return False
        self.input.exports_sync.type_text(hwnd, text, per_char_ms)
        return True

    def login(self, username: str, password: str, field_gap: float = 0.4):
        """Fill the login form: username, Tab, password, Enter."""
        self.type_text(username); time.sleep(field_gap)
        self.key(_keys.TAB);      time.sleep(field_gap)
        self.type_text(password); time.sleep(field_gap)
        self.key(_keys.ENTER)

    def chat(self, message: str, open_key: int = _keys.ENTER,
             send: bool = True):
        """Open chat (open_key), type the message, optionally send (Enter)."""
        self.key(open_key); time.sleep(0.3)
        self.type_text(message); time.sleep(0.2)
        if send:
            self.key(_keys.ENTER)

    # ── Mouse / look (A/D strafe, so turning is mouselook) ───────────
    def mouse_move(self, dx: int, dy: int = 0):
        """One relative mouse motion (SendInput MOUSEEVENTF_MOVE)."""
        self.input.exports_sync.mouse_move_rel(int(dx), int(dy))

    def mouse_button(self, button: str = "right", down: bool = True):
        self.input.exports_sync.mouse_button(button, down)

    def look(self, dx: int, dy: int = 0, *, steps: int = 12,
             step_ms: int = 12, hold_rmb: bool = False):
        """Quake-style mouselook turn. dx>0 turns right, dx<0 left;
        dy>0 looks down. The total delta is sent as `steps` small
        relative motions for smoothness (games clamp big single deltas).
        hold_rmb wraps the motion in a right-button hold for NC2's
        free-look mode (set if plain motion doesn't turn the camera)."""
        if hold_rmb:
            self.mouse_button("right", True)
            time.sleep(0.05)
        try:
            ax = ay = 0.0
            for _ in range(max(1, steps)):
                ax += dx / steps
                ay += dy / steps
                ix, iy = int(ax), int(ay)
                ax -= ix; ay -= iy
                if ix or iy:
                    self.mouse_move(ix, iy)
                time.sleep(step_ms / 1000.0)
        finally:
            if hold_rmb:
                self.mouse_button("right", False)

    # ── Visual ───────────────────────────────────────────────────────
    def screenshot(self, path: str, device: str) -> bool:
        """Capture the D3D9 backbuffer from `device` (heap ptr) to PNG.
        Never raises — returns False if the capture (or the Frida
        session) fails, so one bad frame can't abort a whole pass."""
        self._frame = None
        try:
            self.d3d.exports_sync.capture_dev(device)
        except Exception as e:
            self.last_error = f"screenshot: {e}"
            return False
        end = time.time() + 1.5
        while self._frame is None and time.time() < end:
            time.sleep(0.05)
        if self._frame:
            meta, data = self._frame
            save_image(data, meta["w"], meta["h"], meta["pitch"], path)
            return True
        return False

    # ── Character state ──────────────────────────────────────────────
    def arm_state(self, secs: float = 6.0) -> bool:
        """Resolve the CHARSYS base (needs the player in-world so the
        HUD tick fires). Idempotent; returns True once resolved. Never
        raises."""
        try:
            if self.state.exports_sync.base():
                self._state_armed = True
                return True
            self.state.exports_sync.arm(secs)
            end = time.time() + secs
            while time.time() < end:
                if self.state.exports_sync.base():
                    self._state_armed = True
                    return True
                time.sleep(0.2)
        except Exception as e:
            self.last_error = f"arm_state: {e}"
        return False

    def read_state(self) -> dict:
        """Snapshot of decoded fields (None for not-yet-pinned offsets).
        Never raises — returns {'err': ...} on a session fault."""
        try:
            out = {"base": self.state.exports_sync.base()}
            if out["base"] is None:
                out["err"] = "CHARSYS base not resolved — call arm_state() in-world"
                return out
            for name, (off, kind) in STATE_FIELDS.items():
                out[name] = None if off is None else \
                    self.state.exports_sync.read_at(off, kind)
        except Exception as e:
            return {"base": None, "err": f"read_state: {e}"}
        b = [out.get("hp_b0"), out.get("hp_b1"), out.get("hp_b2")]
        out["hp"] = round(sum(b), 1) if all(x is not None for x in b) else None
        out["hp_max"] = out.get("hp_anchor")
        return out

    def dump_state(self, before: int = 64, after: int = 192):
        return self.state.exports_sync.dump(before, after)

    def close(self):
        try:
            self._sess.detach()
        except Exception:
            pass
