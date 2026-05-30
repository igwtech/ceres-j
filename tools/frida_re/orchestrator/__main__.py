"""CLI entry point for the frida_re orchestrator.

    python3 -m orchestrator [--host …] [--port …] [--trace …]

Connects to the in-Wine frida-gadget, pushes the agent, then streams
events to stdout + JSONL while accepting interactive commands on
stdin (``read``, ``call``, ``send-udp``, …).
"""

from __future__ import annotations

import argparse
import logging
import shlex
import sys
import time
from pathlib import Path
from typing import Optional

from .controller import AgentController
from .decode import decode_event
from .recorder import JsonlRecorder
from .symbols import HOOK_TABLE, hook_table_for_agent


HERE = Path(__file__).resolve().parent
AGENT_DEFAULT = HERE.parent / "agent" / "_agent.js"


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="orchestrator",
        description="Drive the retail NC2 client via in-Wine Frida agent.",
    )
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=27042)
    p.add_argument("--agent", type=Path, default=AGENT_DEFAULT,
                   help="path to agent JS (default: ../agent/_agent.js)")
    p.add_argument("--trace", type=Path, default=None,
                   help="JSONL trace file (default: stdout only)")
    p.add_argument("--no-stdout", action="store_true",
                   help="suppress pretty stdout output")
    p.add_argument("--list-hooks", action="store_true",
                   help="print known Ghidra hooks and exit")
    p.add_argument("--duration", type=float, default=None,
                   help="run headless for N seconds, then exit "
                        "(default: interactive REPL on stdin)")
    p.add_argument("--commands", type=Path, default=None,
                   help="path to a plain-text command file. New lines "
                        "appended to it are read and dispatched as if "
                        "typed into the REPL. Useful with --duration "
                        "for non-blocking RPC from external scripts.")
    p.add_argument("-v", "--verbose", action="count", default=0)
    return p


def configure_logging(verbosity: int) -> None:
    level = logging.WARNING - 10 * min(verbosity, 2)
    logging.basicConfig(
        level=level,
        format="%(asctime)s %(name)-22s %(levelname)-7s %(message)s",
    )


def print_hook_table() -> None:
    print(f"{'name':<24} {'file_va':<10} {'offset':<10} status purpose")
    for spec in HOOK_TABLE.values():
        status = "ON " if spec.implemented else "STUB"
        print(f"{spec.name:<24} 0x{spec.file_va:08x} "
              f"0x{spec.offset:08x} {status}   {spec.purpose}")


def interactive_loop(ctrl: AgentController) -> None:
    """Minimal REPL — one command per line, blank line to exit.

    Commands:
        help                            show this list
        hooks                           print known hooks
        read <addr> <n>                 read n bytes from VA
        write <addr> <hex>              write hex bytes to VA
        call <addr> <int_arg> [...]     call function at VA
        send-udp <hex>                  encrypt + send a UDP payload
        quit                            disconnect and exit
    """
    print("orchestrator> ready. type 'help' for commands, blank to exit.")
    while True:
        try:
            line = input("orchestrator> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return
        if not line:
            return
        try:
            _dispatch_command(ctrl, line)
        except SystemExit:
            return
        except Exception as exc:  # noqa: BLE001 — REPL surface
            print(f"! {type(exc).__name__}: {exc}")


def _dispatch_command(ctrl: AgentController, line: str) -> None:
    argv = shlex.split(line)
    if not argv:
        return
    cmd, *args = argv
    if cmd in {"help", "?"}:
        print(interactive_loop.__doc__ or "")
    elif cmd == "hooks":
        print_hook_table()
    elif cmd == "read":
        addr = int(args[0], 0)
        n = int(args[1], 0)
        data = ctrl.call("readMem", addr, n)
        print(bytes(data).hex(" "))
    elif cmd == "write":
        addr = int(args[0], 0)
        data = bytes.fromhex(args[1].replace(" ", ""))
        ctrl.call("writeMem", addr, list(data))
        print(f"wrote {len(data)} bytes @ 0x{addr:x}")
    elif cmd == "call":
        addr = int(args[0], 0)
        fn_args = [int(a, 0) for a in args[1:]]
        result = ctrl.call("callFunction", addr, fn_args)
        print(f"-> {result!r}")
    elif cmd == "send-udp":
        data = bytes.fromhex(args[0].replace(" ", ""))
        result = ctrl.call("sendUdp", list(data))
        print(f"-> {result!r}")
    elif cmd == "vk":
        # vk <hex> <on|off|press [ms]>
        vk = int(args[0], 16) if args[0].startswith("0x") \
            else int(args[0], 16)
        if len(args) >= 2 and args[1] == "press":
            ms = int(args[2]) if len(args) >= 3 else 1000
            print(f"vk 0x{vk:02x} press {ms}ms")
            ctrl.call("vkPress", vk, True)
            import time as _t; _t.sleep(ms / 1000.0)
            ctrl.call("vkPress", vk, False)
        elif len(args) >= 2 and args[1] in {"on", "1"}:
            print(ctrl.call("vkPress", vk, True))
        elif len(args) >= 2 and args[1] in {"off", "0"}:
            print(ctrl.call("vkPress", vk, False))
        else:
            print("usage: vk <hex_vk> <on|off|press [ms]>")
    elif cmd == "dik":
        # dik <hex_scancode> <on|off|press <ms>>
        # examples: dik 11 on / dik 11 off / dik 11 press 2000
        dik = int(args[0], 16) if args[0].startswith("0x") \
            else int(args[0], 16)
        if len(args) >= 2 and args[1] == "press":
            ms = int(args[2]) if len(args) >= 3 else 1000
            print(f"dik 0x{dik:02x} press {ms}ms")
            ctrl.call("dikPress", dik, True)
            import time as _t; _t.sleep(ms / 1000.0)
            ctrl.call("dikPress", dik, False)
        elif len(args) >= 2 and args[1] in {"on", "1"}:
            print(ctrl.call("dikPress", dik, True))
        elif len(args) >= 2 and args[1] in {"off", "0"}:
            print(ctrl.call("dikPress", dik, False))
        else:
            print("usage: dik <hex> <on|off|press [ms]>")
    elif cmd == "capture":
        # capture [out_path] — call captureD3D9 RPC, save PNG.
        # Default out path encodes timestamp so successive captures
        # don't clobber.
        import time as _t
        out_path = args[0] if args else (
            f"/tmp/frida_capture_{int(_t.time())}.png")
        result = ctrl.call("captureD3D9")
        print(f"-> capture result: {result!r}")
        # If 'd3d9_frame' event was emitted, recorder's last event has
        # it. (RPC return doesn't include pixel bytes.) For now just
        # log the result; PNG conversion is done in the listener.
        print(f"   (PNG saved to {out_path} by event sink if frame "
              f"event was emitted)")
    elif cmd == "ri":
        # ri <hex_vk> <ms>   — RawInput keystroke press for N ms
        vk = int(args[0], 16) if args[0].startswith("0x") \
            else int(args[0], 16)
        ms = int(args[1]) if len(args) >= 2 else 200
        print(f"ri 0x{vk:02x} press {ms}ms")
        print(ctrl.call("riPress", vk, ms))
    elif cmd == "rpc":
        # rpc <method> [json_arg ...] — generic RPC dispatch
        import json as _json
        method = args[0]
        rpc_args = [_json.loads(a) for a in args[1:]]
        result = ctrl.call(method, *rpc_args)
        print(f"-> {result!r}")
    elif cmd in {"quit", "exit"}:
        raise SystemExit
    else:
        print(f"! unknown command: {cmd!r} (try 'help')")


def main(argv: Optional[list[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    configure_logging(args.verbose)

    if args.list_hooks:
        print_hook_table()
        return 0

    if not args.agent.is_file():
        print(f"error: agent script not found: {args.agent}", file=sys.stderr)
        return 2

    recorder = JsonlRecorder(
        path=args.trace,
        stdout=None if args.no_stdout else sys.stdout,
    )

    def sink(payload: dict, _data: Optional[bytes]) -> None:
        try:
            decoded = decode_event(payload)
        except Exception as exc:  # noqa: BLE001 — keep stream alive
            logging.getLogger("orchestrator").warning(
                "decode failed: %s payload=%r", exc, payload)
            return
        # Stamp wall time if the agent didn't.
        if decoded.ts_ns is None:
            decoded.ts_ns = time.time_ns()
        recorder.record(decoded)
        # Auto-save d3d9_frame events as PNG.
        if payload.get("ev") == "d3d9_frame" and _data is not None:
            try:
                out = _save_d3d9_frame_png(payload, _data)
                print(f"d3d9_frame → {out}")
            except Exception as exc:  # noqa: BLE001
                logging.getLogger("orchestrator").warning(
                    "PNG save failed: %s", exc)

    ctrl = AgentController(
        host=args.host,
        port=args.port,
        agent_path=args.agent,
        hook_table=hook_table_for_agent(),
    )
    ctrl.on_event(sink)

    try:
        ctrl.connect()
        print(f"connected. {recorder.n_events} events recorded so far. "
              f"trace={'-' if args.trace is None else args.trace}")
        if args.duration is not None:
            _headless_run(recorder, args.duration, ctrl=ctrl,
                          commands_path=args.commands)
        else:
            interactive_loop(ctrl)
    finally:
        ctrl.disconnect()
        recorder.close()
        print(f"recorded {recorder.n_events} events.")

    return 0


def _headless_run(recorder: "JsonlRecorder", duration: float,
                  ctrl: Optional["AgentController"] = None,
                  commands_path: Optional[Path] = None) -> None:
    """Sleep for ``duration`` seconds, periodically logging event
    count so the operator can see progress even though we're not
    in the REPL. Returns when duration elapses OR on SIGINT.

    If ``commands_path`` is provided, the file is polled every
    ``poll_interval`` seconds and any new lines appended to it are
    dispatched through ``_dispatch_command``. The file is created
    empty if it doesn't exist, and the read offset advances so
    each line is dispatched exactly once.
    """
    if duration <= 0:
        return
    deadline = time.monotonic() + duration
    last_n = recorder.n_events
    last_print = time.monotonic()
    print(f"headless: running for {duration:.1f}s "
          f"(Ctrl-C to exit early)")

    cmd_pos = 0
    if commands_path is not None:
        # Touch the file so writers can `>>` into it from t=0.
        commands_path.touch(exist_ok=True)
        # Start at end so existing content from a prior session is
        # ignored. (User can pass an empty file if they want fresh.)
        cmd_pos = commands_path.stat().st_size
        print(f"headless: polling commands from {commands_path} "
              f"(starting at offset {cmd_pos})")

    try:
        while True:
            now = time.monotonic()
            if now >= deadline:
                break
            time.sleep(min(0.2, deadline - now))

            # Poll commands file for new lines.
            if commands_path is not None and ctrl is not None:
                cmd_pos = _drain_commands(ctrl, commands_path, cmd_pos)

            # Print a status line every 5s if events flowed.
            if now - last_print >= 5.0:
                delta = recorder.n_events - last_n
                if delta > 0:
                    print(f"headless: +{delta} events "
                          f"(total {recorder.n_events})")
                last_n = recorder.n_events
                last_print = now
    except KeyboardInterrupt:
        print("\nheadless: interrupted")


def _save_d3d9_frame_png(payload: dict, data: bytes) -> str:
    """Convert a d3d9_frame event (DXVK backbuffer pixels) into a
    PNG on disk. Format is X8R8G8B8 / A8R8G8B8 (4 BGRA bytes per
    pixel, |pitch| stride per row). Returns the output path.
    """
    import struct
    import time as _t
    import zlib

    w = int(payload["w"])
    h = int(payload["h"])
    pitch = int(payload["pitch"])
    abs_pitch = abs(pitch)
    if abs_pitch * h > len(data):
        raise ValueError(
            f"frame data too small: pitch={pitch} h={h} need "
            f"{abs_pitch * h} got {len(data)}")

    # BGRA → RGBA, row-by-row (handle negative pitch as bottom-up).
    rgba = bytearray(w * h * 4)
    for y in range(h):
        if pitch < 0:
            src_row = (h - 1 - y) * abs_pitch
        else:
            src_row = y * pitch
        dst_row = y * w * 4
        row = data[src_row:src_row + w * 4]
        for x in range(w):
            rgba[dst_row + x * 4 + 0] = row[x * 4 + 2]  # R
            rgba[dst_row + x * 4 + 1] = row[x * 4 + 1]  # G
            rgba[dst_row + x * 4 + 2] = row[x * 4 + 0]  # B
            rgba[dst_row + x * 4 + 3] = 0xff             # A

    out = f"/tmp/frida_capture_{int(_t.time())}.png"

    def chunk(typ: bytes, body: bytes) -> bytes:
        crc = zlib.crc32(typ + body) & 0xffffffff
        return (struct.pack(">I", len(body)) + typ + body
                + struct.pack(">I", crc))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR",
                 struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    raw_lines = bytearray()
    for y in range(h):
        raw_lines.append(0)  # filter byte
        raw_lines.extend(rgba[y * w * 4:(y + 1) * w * 4])
    png += chunk(b"IDAT", zlib.compress(bytes(raw_lines)))
    png += chunk(b"IEND", b"")
    Path(out).write_bytes(png)
    return out


def _drain_commands(ctrl: "AgentController", path: Path,
                    pos: int) -> int:
    """Read new bytes from ``path`` starting at ``pos`` and dispatch
    each whole line. Returns the new file offset. Partial trailing
    lines (no `\\n`) are NOT consumed — we retry next poll.
    """
    try:
        size = path.stat().st_size
    except FileNotFoundError:
        return pos
    if size < pos:
        # File was truncated/replaced — start from 0 to be safe.
        pos = 0
    if size == pos:
        return pos
    try:
        with path.open("r", encoding="utf-8") as f:
            f.seek(pos)
            chunk = f.read()
    except OSError:
        return pos
    # Only consume up to the last newline.
    last_nl = chunk.rfind("\n")
    if last_nl < 0:
        return pos  # no full line yet
    consumed = chunk[:last_nl + 1]
    new_pos = pos + len(consumed.encode("utf-8"))
    for raw in consumed.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        print(f"cmd< {line}")
        try:
            _dispatch_command(ctrl, line)
        except SystemExit:
            # `quit` in the file would terminate headless early.
            return new_pos
        except Exception as exc:  # noqa: BLE001
            print(f"! {type(exc).__name__}: {exc}")
    return new_pos


if __name__ == "__main__":
    sys.exit(main())
