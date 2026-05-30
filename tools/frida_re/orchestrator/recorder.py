"""Event recorder: JSONL trace file + pretty stdout.

The recorder owns the only writable handle to the trace file so
ordering is deterministic. It accepts already-decoded events
(``DecodedEvent``) and writes them as one JSON object per line —
machine-readable, append-friendly, easy to grep.

The recorder is *not* an asyncio thing: events come in synchronously
from the Frida session's ``on('message', …)`` callback (Frida
invokes it on its own thread, marshaled to Python via a queue
internally). We just need writes to be atomic against concurrent
calls; a ``threading.Lock`` handles that.
"""

from __future__ import annotations

import json
import sys
import threading
from dataclasses import asdict
from pathlib import Path
from typing import IO, Optional, TextIO

from .decode import DecodedEvent


class JsonlRecorder:
    """Writes one JSON line per event to a trace file, prints summary
    to stdout.

    Constructor parameters:
        path: trace file path. Parent directory must exist. Pass
              ``None`` to disable disk recording (stdout only).
        stdout: stream for pretty output. Pass ``None`` to suppress.
        flush: if True, flush after every write (slower but the trace
               survives a process crash mid-experiment).
    """

    def __init__(self,
                 path: Optional[Path] = None,
                 stdout: Optional[TextIO] = sys.stdout,
                 flush: bool = True) -> None:
        self._path = path
        self._stdout = stdout
        self._flush = flush
        self._lock = threading.Lock()
        self._fh: Optional[IO[str]] = None
        self._n_events = 0
        if path is not None:
            # Open in append mode so a single trace file can survive
            # an orchestrator restart mid-session (matches the
            # human-pilot workflow where you don't want to lose data).
            self._fh = path.open("a", encoding="utf-8")

    @property
    def n_events(self) -> int:
        """Number of events recorded since construction."""
        return self._n_events

    def record(self, event: DecodedEvent) -> None:
        """Persist one event."""
        if not isinstance(event, DecodedEvent):
            raise TypeError(
                f"event must be DecodedEvent, got {type(event).__name__}"
            )
        with self._lock:
            payload = self._serialise(event)
            line = json.dumps(payload, separators=(",", ":"))
            if self._fh is not None:
                self._fh.write(line)
                self._fh.write("\n")
                if self._flush:
                    self._fh.flush()
            if self._stdout is not None:
                self._stdout.write(event.summary)
                self._stdout.write("\n")
                if self._flush:
                    self._stdout.flush()
            self._n_events += 1

    @staticmethod
    def _serialise(event: DecodedEvent) -> dict:
        """Convert a DecodedEvent to a JSON-safe dict.

        ``bytes`` fields become hex strings (one per field name);
        ``None`` and empty-dict fields are elided to keep the trace
        compact.
        """
        raw = asdict(event)
        # Replace bytes fields with their hex string equivalents.
        if event.buf is not None:
            raw["buf_hex"] = event.buf.hex()
        if event.wire is not None:
            raw["wire_hex"] = event.wire.hex()
        if event.plain is not None:
            raw["plain_hex"] = event.plain.hex()
        # Drop the raw-bytes fields — already replaced.
        for k in ("buf", "wire", "plain"):
            raw.pop(k, None)
        # Drop None / empty-dict entries.
        return {k: v for k, v in raw.items() if v is not None
                and not (isinstance(v, dict) and not v)}

    def close(self) -> None:
        with self._lock:
            if self._fh is not None:
                self._fh.flush()
                self._fh.close()
                self._fh = None

    def __enter__(self) -> "JsonlRecorder":
        return self

    def __exit__(self, *exc) -> None:
        self.close()
