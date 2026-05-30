"""frida_re orchestrator package.

Drives the retail NC2 client via a Frida agent (frida-gadget.dll)
running INSIDE the Wine prefix. See ../README.md for architecture.

Public API:
    symbols.HOOK_TABLE          — Ghidra address constants
    decode.decode_event(evt)    — pure event → structured decode
    recorder.JsonlRecorder      — JSONL writer + pretty stdout
    controller.AgentController  — Frida attach + RPC client
"""

from .symbols import HOOK_TABLE, IMAGE_BASE, file_va_to_offset
from .decode import decode_event, DecodedEvent
from .decrypt import decrypt_wire_packet, lfsr_byte
from .recorder import JsonlRecorder

__all__ = [
    "HOOK_TABLE",
    "IMAGE_BASE",
    "file_va_to_offset",
    "decode_event",
    "DecodedEvent",
    "decrypt_wire_packet",
    "lfsr_byte",
    "JsonlRecorder",
]
