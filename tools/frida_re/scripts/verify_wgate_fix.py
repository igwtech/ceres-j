#!/usr/bin/env python3
"""verify_wgate_fix.py — live confirmation that the zone-security fix
unblocks quickbelt weapon draw on Ceres-J.

Launches the real client vs Ceres, ENTER-logs-in as Krafteo (msn3wolf
slot 0, who spawns in PEPPER PARK SEC-1 = a PvP/outskirt zone), enters
world, then DRIVES the quickbelt: presses '1' to draw the Lazar pistol,
'1' again to holster. Captures D3D9 frames before/after each, and tees
the decrypted wire to trace.jsonl so we can assert the
0x03/0x23/0x20 zoneInfo carries worldflags=0 (not the old hardcoded
0x10 SECURE).

Pass criteria (printed at the end):
  * trace zoneInfo 0x23/0x20 body == `20 00 00 00 00 00` (pepper=0)
  * draw frame differs from baseline (weapon now rendered in hand)
  * NO copbot "Secure Sector" refusal (eyeball the PNGs)

  ./verify_wgate_fix.py            # vs Ceres (172.18.0.3)
"""
from __future__ import annotations
import json, os, sys, time

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)
from harness import keys as K            # noqa: E402
from harness.narrate import Narrator     # noqa: E402
from harness.session import Session      # noqa: E402
from harness.lifecycle import Lifecycle  # noqa: E402

SERVER_IP = "172.18.0.3"
FRIDA_RE_DIR = os.path.dirname(_HERE)
RUNS_ROOT = os.path.join(FRIDA_RE_DIR, "runs")
NC2_DIR = os.environ.get("NC2_DIR", "/home/javier/Neocron2")
GADGET_PORT = 27042
USER, PASSWORD, SLOT = "msn3wolf", "sprlnk2kk", 0


def main() -> int:
    run_id = os.environ.get("RUN_ID") or \
        f"wgate_fix_{time.strftime('%Y%m%d_%H%M%S')}"
    run_dir = os.path.join(RUNS_ROOT, run_id)
    os.makedirs(run_dir, exist_ok=True)
    nar = Narrator(os.path.join(run_dir, "marks.log"))

    lc = Lifecycle(SERVER_IP, run_dir, scripts_dir=_HERE,
                   frida_re_dir=FRIDA_RE_DIR, py=sys.executable,
                   nc2_dir=NC2_DIR, port=GADGET_PORT)
    sess = None
    device = None

    def shot(name, why=""):
        if not device:
            nar.say(f"(skip {name}: no device)"); return
        ok = sess.screenshot(os.path.join(run_dir, name), device)
        nar.say(f"screenshot {name} {'OK' if ok else 'FAILED'}"
                f"{(' — ' + why) if why else ''}")

    try:
        nar.mark(f"verify_wgate_fix {run_id} {USER} on {SERVER_IP}")
        lc.clean_slate(); lc.set_server(); lc.launch_client()
        if not lc.wait_gadget(120):
            nar.say("FATAL: gadget never came up"); return 1
        lc.start_orchestrator()
        # Best-effort: the present-hook screenshot path finds the device
        # itself, so a missed CreateDevice candidate (likely under
        # on_load=resume) is non-fatal — only the direct-capture fallback
        # needs this pointer.
        device = lc.wait_device(40) or ""
        nar.say(f"live D3D9 device: {device or '(none — using present-hook capture)'}")
        sess = Session(port=GADGET_PORT)
        time.sleep(10)

        nar.say(f"ENTER-login {USER} (long path, slot {SLOT})")
        sess.focus()
        sess.type_text(USER); sess.key(K.TAB); sess.type_text(PASSWORD)
        sess.keys([K.TAB, K.TAB]); sess.key(K.ENTER)
        time.sleep(3)
        sess.keys([K.TAB, K.ENTER])            # confirm server
        time.sleep(4)
        # set_server() reset ini LastChar=0, so the char-select cursor
        # starts at slot 0 (Krafteo @ pepper_p1, the one with a weapon in
        # QB). The cursor WRAPS, so don't navigate LEFT — just step RIGHT
        # to the target slot. SLOT=0 → no movement, ENTER selects Krafteo.
        for _ in range(SLOT):
            sess.key(K.RIGHT)
            time.sleep(0.2)
        shot("00_charselect.png", "char-select — Krafteo (slot 0) highlighted?")
        sess.key(K.ENTER)                      # enter world
        if not lc.wait_inworld(70):
            nar.say("FATAL: never reached in-world"); return 1
        time.sleep(4)

        nar.mark("IN_WORLD pepper_p1")
        # Level/pitch the camera down so the first-person weapon (which
        # renders lower-centre) is in frame; do it BEFORE the baseline so
        # holstered vs drawn compare under the same view. Step forward to
        # clear the spawn nook into open street.
        sess.look(0, 180)            # pitch down toward the horizon/ground
        time.sleep(0.4)
        sess.hold(K.W, 1200)         # walk a step into open space
        time.sleep(0.6)
        shot("01_inworld.png", "baseline — pepper_p1, weapon holstered")
        time.sleep(1)

        # DRAW: quickbelt slot 1 = key '1' (Lazar pistol, def 0x122)
        nar.say("DRAW: press '1' (QB slot 1 = Lazar pistol)")
        sess.key(K.digit(1))
        time.sleep(2.5)
        shot("02_draw.png", "after QB '1' — pistol should be in hand")
        time.sleep(1)

        # HOLSTER: same key toggles back
        nar.say("HOLSTER: press '1' again")
        sess.key(K.digit(1))
        time.sleep(2.5)
        shot("03_holster.png", "after QB '1' again — holstered")

        # second draw for good measure (in case the first race-lost)
        nar.say("DRAW again: '1'")
        sess.key(K.digit(1))
        time.sleep(2.5)
        shot("04_draw2.png", "second draw")

        nar.mark("DONE")
        time.sleep(2)
        return 0
    except Exception as e:
        nar.say(f"PASS ERROR: {type(e).__name__}: {e}")
        return 1
    finally:
        if sess:
            try: sess.close()
            except Exception: pass
        lc.teardown(); nar.close()
        # Post-hoc: assert the zoneInfo worldflags on the live wire.
        _assert_zoneinfo(run_dir)
        print(f"\nArtifacts: {run_dir}")


def _assert_zoneinfo(run_dir):
    """Scan trace.jsonl for S->C 0x03/0x23 sub 0x20 zoneInfo and print
    its worldflags. pepper_p1 must be 0x00000000 (not 0x10 secure)."""
    path = os.path.join(run_dir, "trace.jsonl")
    if not os.path.exists(path):
        print("zoneInfo check: no trace.jsonl"); return
    hits = []
    for line in open(path):
        try:
            r = json.loads(line)
        except Exception:
            continue
        if r.get("ev") != "udp_recv":
            continue
        ph = r.get("plain_hex")
        if not ph:
            continue
        b = bytes.fromhex(ph)
        if not b or b[0] != 0x13:
            continue
        i, n = 5, len(b)
        while i + 2 <= n:
            ln = b[i] | (b[i + 1] << 8); i += 2
            if ln <= 0 or i + ln > n:
                break
            sub = b[i:i + ln]; i += ln
            # reliable 0x03, opcode 0x23, sub 0x20 → zoneInfo
            if len(sub) >= 10 and sub[0] == 0x03 and sub[3] == 0x23 \
                    and sub[4] == 0x20:
                flags = int.from_bytes(sub[6:10], "little")
                hits.append(flags)
    print("\n=== zoneInfo (0x03/0x23/0x20) worldflags on live wire ===")
    if not hits:
        print("  (none captured — check trace.jsonl manually)")
    else:
        from collections import Counter
        for fl, c in Counter(hits).items():
            verdict = "SECURE (BUG)" if (fl & 0x10) else "ok (not secure)"
            print(f"  worldflags=0x{fl:x}  x{c}  -> {verdict}")
        secure = any(f & 0x10 for f in hits)
        print(f"  RESULT: {'FAIL — still secure' if secure else 'PASS — pepper not secure'}")


if __name__ == "__main__":
    sys.exit(main())
