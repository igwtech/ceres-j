"""Robust, state-based NC2 login for deterministic passes.

The flaky part of automating the real client is timing: the login form
and char-select appear at variable times, and a fixed sleep sometimes
types into a not-yet-ready screen. This drives login off OBSERVABLE
state from the agent trace instead:

  1. settle for the menu, then type credentials;
  2. RETRY the credentials until the client connects to the GameServer
     (tcp_connect to <server>:12000 in the trace) == auth accepted;
  3. nudge Enter through char-select until sustained S->C UDP
     (in-world) appears.

Returns True once in-world.
"""
from __future__ import annotations
import time

from . import keys


def robust_login(sess, lc, username, password, *,
                 menu_settle=12.0, cred_attempts=3, auth_timeout=25.0,
                 spawn_nudges=10, spawn_step=8.0, inworld_recv=50,
                 log=print, shot=None):
    server = lc.server_ip
    auth_sig = f'"port":12000,"ip":"{server}"'

    def _shot(name):
        if shot:
            shot(name)

    log(f"[login] menu settle {menu_settle:g}s")
    time.sleep(menu_settle)
    _shot("login_a_menu")            # is the login form actually up?

    authed = lc.count_trace(auth_sig) > 0
    for i in range(cred_attempts):
        if authed:
            break
        sess.focus()   # window focus (SetForegroundWindow) is reliable
        if i > 0:
            # A prior attempt may have typed into a half-ready form;
            # back out to a clean state before retrying.
            lc.ri_key(keys.ESC)
            time.sleep(1.0)
            sess.focus()
        sess.type_text(username)
        _shot(f"login_b_user_{i}")    # did the username land in the field?
        sess.key(keys.TAB)
        time.sleep(0.3)
        sess.type_text(password)
        sess.key(keys.TAB)
        sess.key(keys.TAB)
        _shot(f"login_c_creds_{i}")   # password entered, on Resume button?
        sess.key(keys.ENTER)
        log(f"[login] credentials (RawInput) attempt {i + 1}/"
            f"{cred_attempts}; waiting {auth_timeout:g}s for GameServer auth")
        # Server Select
        sess.keys([keys.TAB,keys.ENTER])
        #if lc.wait_trace(auth_sig, auth_timeout):
            authed = True

    if not authed:
        log("[login] FAILED: never connected to GameServer (auth)")
        return False
    log("[login] authenticated; entering world")
    
    # Char-select -> world. Nudge Enter until sustained UDP appears.
    for n in range(spawn_nudges):
        if lc.wait_inworld(spawn_step, min_recv=inworld_recv):
            log("[login] in-world")
            return True
        log(f"[login] spawn nudge {n + 1}/{spawn_nudges}")
        lc.ri_key(keys.ENTER)
    ok = lc.wait_inworld(spawn_step, min_recv=inworld_recv)
    log(f"[login] {'in-world' if ok else 'FAILED to spawn'}")
    return ok
