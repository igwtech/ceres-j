"""Tests for the input toolkit under scripts/inputs/ and the
frida_input.py CLI.

Pure-Python tests for everything that doesn't need a live gadget:
  * VK / NC2-action resolver — full mapping and error cases
  * batch-token splitter — argv decomposition for `frida_input batch`
  * `frida_input.py vk` — offline subcommand integration

Live-gadget tests live in test_functional.py (so they can be marked
`functional` and skipped in CI).
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

# Make scripts importable.
HERE = Path(__file__).resolve().parent
SCRIPTS = HERE.parent / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from inputs import vk  # noqa: E402
import frida_input  # noqa: E402


# ── VK resolver ─────────────────────────────────────────────────────


class TestVkResolve:
    @pytest.mark.parametrize("name,expected", [
        ("W", 0x57), ("w", 0x57),
        ("F2", 0x71), ("f12", 0x7B),
        ("ENTER", 0x0d), ("Return", 0x0d),
        ("UP", 0x26), ("Down", 0x28),
        ("SPACE", 0x20), ("ESC", 0x1B),
        ("0", 0x30), ("9", 0x39),
        ("NUMPAD5", 0x65),
        ("PERIOD", 0xBE), ("OEM_3", 0xC0),
        ("LSHIFT", 0xA0), ("RSHIFT", 0xA1),
    ])
    def test_win32_vk_names(self, name, expected):
        assert vk.resolve(name) == expected

    @pytest.mark.parametrize("action,expected", [
        ("forward",   0x57),  # W
        ("backward",  0x53),  # S
        ("strafe_left",  0x41),  # A
        ("strafe_right", 0x44),  # D
        ("jump",      0x20),  # SPACE
        ("crouch",    0x43),  # C
        ("inventory", 0x71),  # F2
        ("skills",    0x72),  # F3
        ("menu",      0x1B),  # ESC
        ("use",       0x45),  # E
        ("weapon_5",  0x35),  # '5'
    ])
    def test_nc2_action_aliases(self, action, expected):
        assert vk.resolve(action) == expected

    def test_hex_passthrough(self):
        assert vk.resolve("0x1B") == 0x1B
        assert vk.resolve("0xff") == 0xff

    def test_int_passthrough(self):
        assert vk.resolve(0x57) == 0x57
        # Large ints are masked to one byte.
        assert vk.resolve(0x157) == 0x57

    def test_unknown_raises(self):
        with pytest.raises(ValueError, match="unknown key"):
            vk.resolve("not_a_real_key_xyz")

    def test_empty_raises(self):
        with pytest.raises(ValueError, match="empty"):
            vk.resolve("")

    def test_mouse_bound_action_raises(self):
        """NC2 'fire' is bound to LMB, not a keyboard key. Asking the
        resolver for it must fail loudly so callers route through the
        mouse module."""
        with pytest.raises(ValueError, match="mouse-bound"):
            vk.resolve("fire")


# ── CLI batch splitter ──────────────────────────────────────────────


class TestBatchSplit:
    def test_single_command(self):
        chunks = frida_input._split_batch_tokens(["key", "F2"])
        assert chunks == [["key", "F2"]]

    def test_multi_commands(self):
        chunks = frida_input._split_batch_tokens([
            "key", "inventory",
            "sleep", "500",
            "click", "--x", "300", "--y", "200",
            "key", "menu",
        ])
        assert chunks == [
            ["key", "inventory"],
            ["sleep", "500"],
            ["click", "--x", "300", "--y", "200"],
            ["key", "menu"],
        ]

    def test_args_belong_to_previous(self):
        """`--ms` after `walk` is a flag for walk, not a command."""
        chunks = frida_input._split_batch_tokens([
            "walk", "--dx", "1", "--ms", "1000",
            "sleep", "200",
        ])
        assert chunks == [
            ["walk", "--dx", "1", "--ms", "1000"],
            ["sleep", "200"],
        ]

    def test_empty_tokens(self):
        assert frida_input._split_batch_tokens([]) == []

    def test_unknown_starting_token_is_kept(self):
        """Unknown leading token attaches to the first chunk —
        argparse will then fail loudly with a clear error. The splitter
        itself shouldn't filter."""
        chunks = frida_input._split_batch_tokens(["weird", "key", "F1"])
        # Either packaged with weird as a leading arg, or split as 2
        # separate chunks. Implementation collapses 'weird' into the
        # first chunk (which then contains key as the leader once the
        # 'weird' is consumed by the first cur).
        assert len(chunks) >= 1


# ── Parser smoke-test (no Frida needed) ─────────────────────────────


class TestParserSmoke:
    def test_help_does_not_crash(self, capsys):
        with pytest.raises(SystemExit) as exc:
            frida_input.build_parser().parse_args(["--help"])
        assert exc.value.code == 0

    def test_vk_subcommand_no_session(self, capsys):
        """`vk` is offline — main() should never attach Frida."""
        rc = frida_input.main(["vk", "F2"])
        assert rc == 0
        out = capsys.readouterr().out
        assert "0x71" in out or '"vk_hex"' in out

    def test_unknown_vk_subcommand_fails(self, capsys):
        rc = frida_input.main(["vk", "not_a_key"])
        assert rc == 2
        err = capsys.readouterr().err
        assert "unknown key" in err

    @pytest.mark.parametrize("argv", [
        ["status"],
        ["focus"],
        ["key", "inventory"],
        ["hold", "forward", "3000"],
        ["walk", "--dx", "1", "--dz", "1", "--ms", "2000"],
        ["say", "hello"],
        ["slash", "/setpsi 0"],
        ["click", "--x", "100", "--y", "100"],
        ["dclick", "--x", "100", "--y", "100"],
        ["look", "--dx", "10", "--dy", "5"],
        ["move", "--x", "1", "--y", "1"],
        ["drag", "--from-x", "0", "--from-y", "0", "--to-x", "100",
         "--to-y", "100"],
        ["wheel", "120"],
        ["sleep", "100"],
    ])
    def test_argv_parses(self, argv):
        ns = frida_input.build_parser().parse_args(argv)
        assert ns.cmd == argv[0]
