"""Unit tests for orchestrator/__main__.py helper functions:

* ``_save_d3d9_frame_png`` — convert raw DXVK BGRA backbuffer bytes
  into a valid PNG on disk.
* ``_drain_commands`` — poll a command file, dispatch new whole lines
  into the orchestrator REPL exactly once.

These don't touch Frida — they're pure-Python helpers driven by the
``--commands FILE`` and ``captureD3D9`` plumbing added with #262.
"""

from __future__ import annotations

import struct
import zlib
from pathlib import Path

import pytest

from orchestrator.__main__ import _drain_commands, _save_d3d9_frame_png


# ---------------------------------------------------------------------
# _save_d3d9_frame_png
# ---------------------------------------------------------------------


def _solid_bgra_frame(w: int, h: int, b: int, g: int, r: int,
                      a: int = 0xff) -> bytes:
    """Synthesize a top-down BGRA framebuffer (X8R8G8B8) of a single
    solid colour. Pitch == w*4 (no padding). Matches what DXVK's
    GetRenderTargetData copy yields for the standard NC2 backbuffer
    format.
    """
    px = bytes([b & 0xff, g & 0xff, r & 0xff, a & 0xff])
    return px * (w * h)


class TestSaveD3D9FramePng:
    def test_writes_png_with_correct_dimensions(self, tmp_path: Path,
                                                monkeypatch):
        # Redirect the helper's hardcoded /tmp output to tmp_path by
        # monkey-patching time so each test gets a deterministic name,
        # then move the file into tmp_path so we don't pollute /tmp.
        w, h = 16, 8
        data = _solid_bgra_frame(w, h, b=0x80, g=0x40, r=0xff)
        payload = {"ev": "d3d9_frame", "w": w, "h": h,
                   "pitch": w * 4, "fmt": "0x16"}
        out = Path(_save_d3d9_frame_png(payload, data))
        try:
            assert out.exists()
            png = out.read_bytes()
            assert png[:8] == b"\x89PNG\r\n\x1a\n"
            # IHDR follows the signature: 4B length + 'IHDR' + 13B.
            ihdr_len = struct.unpack(">I", png[8:12])[0]
            assert ihdr_len == 13
            assert png[12:16] == b"IHDR"
            ihdr = png[16:16 + 13]
            iw, ih, depth, ctype = struct.unpack(">IIBB", ihdr[:10])
            assert (iw, ih) == (w, h)
            assert depth == 8
            assert ctype == 6  # RGBA
        finally:
            out.unlink(missing_ok=True)

    def test_bgra_to_rgba_channel_swap(self, tmp_path: Path):
        """A pure-red BGRA pixel must round-trip to RGBA red — i.e.
        the helper swaps B↔R so the user actually sees red, not blue.
        """
        # BGRA red = b=0 g=0 r=ff a=ff
        data = _solid_bgra_frame(2, 2, b=0x00, g=0x00, r=0xff)
        payload = {"ev": "d3d9_frame", "w": 2, "h": 2,
                   "pitch": 8, "fmt": "0x16"}
        out = Path(_save_d3d9_frame_png(payload, data))
        try:
            png_bytes = out.read_bytes()
            # Find IDAT chunk and decompress.
            idat = _extract_chunk(png_bytes, b"IDAT")
            raw = zlib.decompress(idat)
            # Each row = 1 filter byte + w*4 pixel bytes.
            row_stride = 1 + 2 * 4
            assert len(raw) == row_stride * 2
            # First pixel of first row, after filter byte 0.
            r, g, b, a = raw[1], raw[2], raw[3], raw[4]
            assert (r, g, b, a) == (0xff, 0x00, 0x00, 0xff)
        finally:
            out.unlink(missing_ok=True)

    def test_negative_pitch_handled(self):
        """DXVK sometimes returns negative pitch for bottom-up
        surfaces. The helper must invert row order so output is
        upright."""
        w, h = 4, 4
        # Top half red, bottom half blue (top-down ordering).
        top    = bytes([0x00, 0x00, 0xff, 0xff]) * (w * (h // 2))
        bottom = bytes([0xff, 0x00, 0x00, 0xff]) * (w * (h // 2))
        data = top + bottom
        # Tell the helper the buffer is bottom-up (pitch < 0).
        payload = {"ev": "d3d9_frame", "w": w, "h": h,
                   "pitch": -(w * 4), "fmt": "0x16"}
        out = Path(_save_d3d9_frame_png(payload, data))
        try:
            png_bytes = out.read_bytes()
            idat = _extract_chunk(png_bytes, b"IDAT")
            raw = zlib.decompress(idat)
            row_stride = 1 + w * 4
            # With pitch<0 the "top half" of source bytes becomes
            # the BOTTOM half of the upright image. Row 0 of the
            # PNG should be the original bottom row (BGRA blue).
            r0 = raw[1:row_stride]
            r0_first_px = r0[:4]
            assert r0_first_px == bytes([0x00, 0x00, 0xff, 0xff])
        finally:
            out.unlink(missing_ok=True)

    def test_raises_on_undersized_data(self):
        payload = {"ev": "d3d9_frame", "w": 32, "h": 32,
                   "pitch": 32 * 4, "fmt": "0x16"}
        with pytest.raises(ValueError, match="too small"):
            _save_d3d9_frame_png(payload, b"\x00" * 10)


def _extract_chunk(png: bytes, want: bytes) -> bytes:
    """Read a PNG chunk by name. Returns the chunk's body bytes."""
    pos = 8
    while pos < len(png):
        length = struct.unpack(">I", png[pos:pos + 4])[0]
        typ = png[pos + 4:pos + 8]
        body = png[pos + 8:pos + 8 + length]
        if typ == want:
            return body
        pos += 8 + length + 4  # length + type + body + crc
    raise KeyError(f"chunk {want!r} not found")


# ---------------------------------------------------------------------
# _drain_commands
# ---------------------------------------------------------------------


class _RecordingCtrl:
    """Stand-in for AgentController — records each (method, args)."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, tuple]] = []

    def call(self, method: str, *args):
        self.calls.append((method, args))
        return {"recorded": True}


class TestDrainCommands:
    def test_returns_pos_if_file_missing(self, tmp_path: Path):
        ctrl = _RecordingCtrl()
        missing = tmp_path / "nope.txt"
        assert _drain_commands(ctrl, missing, 0) == 0
        assert ctrl.calls == []

    def test_dispatches_complete_lines_only(self, tmp_path: Path):
        # 'capture' is a known orchestrator command.
        ctrl = _RecordingCtrl()
        f = tmp_path / "cmds.txt"
        f.write_text("capture\nvk 0x28 press 50\n# this is a comment\n")
        pos = _drain_commands(ctrl, f, 0)
        # capture → captureD3D9 call. vk press → vkPress True + False.
        methods = [c[0] for c in ctrl.calls]
        assert "captureD3D9" in methods
        assert methods.count("vkPress") == 2
        assert pos == f.stat().st_size

    def test_partial_trailing_line_is_not_consumed(self, tmp_path: Path):
        ctrl = _RecordingCtrl()
        f = tmp_path / "cmds.txt"
        f.write_text("capture\nvk 0x28 on")  # no trailing newline
        pos = _drain_commands(ctrl, f, 0)
        # 'capture' is consumed; the partial 'vk 0x28 on' line is not.
        assert any(c[0] == "captureD3D9" for c in ctrl.calls)
        assert not any(c[0] == "vkPress" for c in ctrl.calls)
        # Position should be at the end of 'capture\n' (i.e. after
        # the newline), not at end-of-file.
        assert pos == len("capture\n")
        # Now append a newline; the second call should pick it up.
        with f.open("a") as h:
            h.write("\n")
        pos2 = _drain_commands(ctrl, f, pos)
        assert any(c[0] == "vkPress" for c in ctrl.calls)
        assert pos2 == f.stat().st_size

    def test_resets_on_truncation(self, tmp_path: Path):
        ctrl = _RecordingCtrl()
        f = tmp_path / "cmds.txt"
        f.write_text("capture\n")
        pos = _drain_commands(ctrl, f, 0)
        # Truncate.
        f.write_text("")  # size 0
        # File is smaller than pos → helper resets to 0 and finds no
        # newline → returns 0.
        assert _drain_commands(ctrl, f, pos) == 0

    def test_blank_and_comment_lines_skipped(self, tmp_path: Path):
        ctrl = _RecordingCtrl()
        f = tmp_path / "cmds.txt"
        f.write_text("\n# a comment\n   \n")
        pos = _drain_commands(ctrl, f, 0)
        assert ctrl.calls == []
        assert pos == f.stat().st_size
