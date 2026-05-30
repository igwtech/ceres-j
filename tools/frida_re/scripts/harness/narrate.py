"""LLM-facing narration: echo a clearly-tagged, human/LLM-readable
comment about what the pass is doing and what to expect, AND append it
to marks.log so a driving LLM can follow along and correlate with the
trace timeline.
"""
from __future__ import annotations
import time


class Narrator:
    def __init__(self, marks_path: str):
        self._f = open(marks_path, "a", encoding="utf-8")

    def _stamp(self) -> str:
        return time.strftime("%H:%M:%S")

    def say(self, msg: str) -> None:
        """An LLM-facing comment (intent + expectation)."""
        print(f">>> LLM | {msg}", flush=True)
        self._f.write(f"[{self._stamp()}] NARRATE {msg}\n")
        self._f.flush()

    def mark(self, msg: str) -> None:
        """A terse timeline marker (step boundary), also echoed."""
        print(f"--- {msg}", flush=True)
        self._f.write(f"[{self._stamp()}] MARK {msg}\n")
        self._f.flush()

    def state(self, snap: dict, label: str = "") -> None:
        """Echo the headline character-state values for the LLM."""
        def g(k):
            v = snap.get(k)
            return "?" if v is None else v
        line = (f"STATE{f' ({label})' if label else ''} | "
                f"charsys={g('base')} HP={g('hp')}/{g('hp_max')} "
                f"PSI={g('psi')} STA={g('sta')} MAP={g('map_id')} "
                f"TIME={g('game_time')}")
        self.say(line)

    def close(self):
        try:
            self._f.close()
        except Exception:
            pass
