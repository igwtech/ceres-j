#!/usr/bin/env python3
"""
compare-packet-sizes.py — compare UDP packet size distributions between
retail and ceres captures. Even without decryption we can see what sizes
the client sends/receives and whether key packet sizes (13-byte sync
response, 14-byte handshake, etc.) appear in both captures.
"""

from __future__ import annotations

import re
import sys
from collections import Counter
from pathlib import Path

_RE = re.compile(
    r'(recvmsg|sendmsg)\(\d+<UDP[^>]*>.*?iov_len=(\d+).*?= (\d+)',
    re.MULTILINE,
)


def sizes(path: Path) -> tuple[Counter, Counter]:
    recv = Counter()
    send = Counter()
    if not path.exists():
        return recv, send
    with path.open("r", errors="replace") as f:
        for m in _RE.finditer(f.read()):
            call = m.group(1)
            n = int(m.group(3))  # actual bytes transferred
            if n <= 0:
                continue
            if call == "recvmsg":
                recv[n] += 1
            else:
                send[n] += 1
    return recv, send


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print("usage: compare-packet-sizes.py retail.log ceres.log [retail2.log ...]")
        return 1
    logs = [Path(p) for p in argv[1:]]
    results = [(p, sizes(p)) for p in logs]
    for path, (recv, send) in results:
        total_r = sum(recv.values())
        total_s = sum(send.values())
        print(f"\n=== {path.name} ===")
        print(f"  recvmsg (S→C): {total_r} packets, top 15 sizes:")
        for sz, c in sorted(recv.items(), key=lambda kv: -kv[1])[:15]:
            print(f"    {sz:5d}B × {c}")
        print(f"  sendmsg (C→S): {total_s} packets, top 15 sizes:")
        for sz, c in sorted(send.items(), key=lambda kv: -kv[1])[:15]:
            print(f"    {sz:5d}B × {c}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
