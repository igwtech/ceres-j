"""Unit tests for orchestrator.recorder."""

import io
import json
import threading
from pathlib import Path

import pytest

from orchestrator.decode import DecodedEvent, decode_event
from orchestrator.recorder import JsonlRecorder


pytestmark = pytest.mark.unit


def _make_event() -> DecodedEvent:
    return decode_event({
        "ev": "cipher_enter",
        "hook": "udp_cipher_a",
        "ts": 1716_900_000_000_000_000,
        "tid": 7,
        "seed_lo": 0x12,
        "seed_hi": 0x34,
        "buf_hex": "fe 0a 00 03 1f",
        "buf_len": 5,
    })


class TestStdoutOnly:
    def test_records_to_stdout_no_file(self):
        out = io.StringIO()
        rec = JsonlRecorder(path=None, stdout=out, flush=False)
        rec.record(_make_event())
        assert "cipher_enter" in out.getvalue()
        assert "0x3412" in out.getvalue()
        assert rec.n_events == 1
        rec.close()

    def test_no_stdout_no_file_still_counts(self):
        rec = JsonlRecorder(path=None, stdout=None)
        rec.record(_make_event())
        rec.record(_make_event())
        assert rec.n_events == 2
        rec.close()


class TestJsonlFile:
    def test_writes_one_line_per_event(self, tmp_path: Path):
        f = tmp_path / "trace.jsonl"
        rec = JsonlRecorder(path=f, stdout=None)
        rec.record(_make_event())
        rec.record(_make_event())
        rec.close()

        lines = f.read_text().splitlines()
        assert len(lines) == 2
        for line in lines:
            obj = json.loads(line)
            assert obj["ev"] == "cipher_enter"
            assert obj["hook"] == "udp_cipher_a"
            assert obj["tid"] == 7
            assert obj["buf_hex"] == "fe0a00031f"
            assert obj["seed"] == 0x3412

    def test_none_fields_elided(self, tmp_path: Path):
        f = tmp_path / "trace.jsonl"
        rec = JsonlRecorder(path=f, stdout=None)
        # Event with no seed and no buf.
        evt = decode_event({"ev": "ready", "module": "x"})
        rec.record(evt)
        rec.close()
        obj = json.loads(f.read_text())
        assert "seed" not in obj
        assert "buf_hex" not in obj
        assert "buf" not in obj  # raw bytes never leak
        assert obj["ev"] == "ready"

    def test_append_mode(self, tmp_path: Path):
        f = tmp_path / "trace.jsonl"
        rec1 = JsonlRecorder(path=f, stdout=None)
        rec1.record(_make_event())
        rec1.close()
        rec2 = JsonlRecorder(path=f, stdout=None)
        rec2.record(_make_event())
        rec2.close()
        assert len(f.read_text().splitlines()) == 2

    def test_rejects_non_decoded_event(self, tmp_path: Path):
        rec = JsonlRecorder(path=tmp_path / "t.jsonl", stdout=None)
        with pytest.raises(TypeError):
            rec.record({"ev": "x"})  # type: ignore[arg-type]
        rec.close()

    def test_context_manager_closes(self, tmp_path: Path):
        f = tmp_path / "trace.jsonl"
        with JsonlRecorder(path=f, stdout=None) as rec:
            rec.record(_make_event())
        # After __exit__ the file should be readable in full.
        assert json.loads(f.read_text())["ev"] == "cipher_enter"

    def test_flush_after_every_write(self, tmp_path: Path):
        """With flush=True the JSONL is visible mid-stream — important
        because trace files outlive process crashes."""
        f = tmp_path / "trace.jsonl"
        rec = JsonlRecorder(path=f, stdout=None, flush=True)
        rec.record(_make_event())
        # Without closing, content should already be on disk.
        assert f.read_text().count("\n") == 1
        rec.close()


class TestUdpEventSerialisation:
    def test_wire_and_plain_serialised_as_hex(self, tmp_path: Path):
        """udp_recv/udp_send events must serialise both the raw wire
        bytes and the decrypted plaintext as hex strings, never as
        raw bytes (un-JSON-able) and never leaking the `wire`/`plain`
        attribute names that hold bytes objects."""
        import json
        # Build a valid wire from known plaintext.
        from orchestrator.decrypt import lfsr_byte

        def encrypt(pt: bytes, seed: int) -> bytes:
            state = seed
            k1, state = lfsr_byte(state, (seed >> 8) & 0xff)
            enc_lo = k1 ^ (len(pt) & 0xff)
            k2, state = lfsr_byte(state, enc_lo)
            enc_hi = k2 ^ ((len(pt) >> 8) & 0xff)
            body = bytearray()
            prev = enc_hi
            for p in pt:
                k, state = lfsr_byte(state, prev)
                c = k ^ p
                body.append(c)
                prev = c
            return bytes([seed & 0xff, (seed >> 8) & 0xff,
                          enc_lo, enc_hi]) + bytes(body)

        wire = encrypt(b"\x13\x5f\x0c", 0x4902)
        evt = decode_event({
            "ev": "udp_recv", "sock": 912, "len": len(wire),
            "hex": wire.hex(),
        })
        f = tmp_path / "trace.jsonl"
        with JsonlRecorder(path=f, stdout=None) as rec:
            rec.record(evt)
        obj = json.loads(f.read_text())
        assert obj["ev"] == "udp_recv"
        assert obj["direction"] == "s2c"
        assert obj["wire_hex"] == wire.hex()
        assert obj["plain_hex"] == "135f0c"
        assert obj["opcode"] == 0x13
        assert obj["decrypt_ok"] is True
        # Raw bytes attribute names must NOT appear.
        assert "wire" not in obj
        assert "plain" not in obj
        assert "buf" not in obj


class TestConcurrency:
    def test_concurrent_writes_dont_interleave(self, tmp_path: Path):
        """Recorder uses a Lock — N threads each writing K events must
        produce exactly N*K well-formed JSONL lines."""
        f = tmp_path / "trace.jsonl"
        rec = JsonlRecorder(path=f, stdout=None, flush=False)
        N, K = 8, 25

        def worker():
            for _ in range(K):
                rec.record(_make_event())

        threads = [threading.Thread(target=worker) for _ in range(N)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        rec.close()

        lines = f.read_text().splitlines()
        assert len(lines) == N * K
        for line in lines:
            obj = json.loads(line)        # must parse cleanly
            assert obj["ev"] == "cipher_enter"
        assert rec.n_events == N * K
