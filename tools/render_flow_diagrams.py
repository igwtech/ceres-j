#!/usr/bin/env python3
"""render_flow_diagrams.py — extract every ```mermaid block from
docs/protocol/flows/*.md and render each to a PNG under
docs/protocol/flows/diagrams/.

Output filenames: ``<flow_stem>.<idx>.png`` (e.g. ``login.1.png``).
Multiple diagrams per file are numbered in source order.

Requires mmdc (mermaid-cli). Default path is the local install at
/tmp/mermaid-tool/node_modules/.bin/mmdc; override with --mmdc.
"""
from __future__ import annotations
import argparse, re, subprocess, sys, tempfile
from pathlib import Path

MERMAID_RE = re.compile(r"```mermaid\n(.*?)\n```", re.DOTALL)

# Mermaid's sequenceDiagram parser disallows ()[]{} in message text after
# the `:` delimiter on `actor->>actor:` lines. We can't fix the parser,
# so sanitize the blocks before handing them to mmdc. The source .md
# stays readable; only the on-the-fly tempfile gets sanitized.
ARROW_LINE_RE = re.compile(
    r"^(\s*[A-Za-z0-9_]+\s*(?:-->>|->>|-->|->|--x|--\\)\s*[A-Za-z0-9_]+\s*:)\s*(.*)$"
)
NOTE_LINE_RE = re.compile(
    r"^(\s*Note\s+(?:over|right of|left of)\s+[^:]+:)\s*(.*)$"
)


def _sanitize_message(text: str) -> str:
    """Remove characters mermaid's sequence parser chokes on while
    keeping the message human-readable.

    Forbidden characters in message text after `:`:
      - `(` `)` `[` `]` `{` `}` — parsed as participant aliasing
      - `;`                     — statement separator
    """
    # Replace parens around a clause with em-dash separator.
    text = re.sub(r"\s*\(\s*", " — ", text)
    text = re.sub(r"\s*\)\s*$", "", text)
    text = text.replace(")", " ")
    # Brackets get the same treatment.
    text = re.sub(r"\s*\[\s*", " — ", text)
    text = text.replace("]", " ")
    # Curly braces (rare) — strip.
    text = text.replace("{", " ").replace("}", " ")
    # Semicolons are mermaid statement separators — replace with comma.
    text = text.replace(";", ",")
    # Collapse runs of whitespace.
    text = re.sub(r"\s+", " ", text).strip()
    return text


def sanitize_block(block: str) -> str:
    out_lines = []
    for line in block.splitlines():
        m = ARROW_LINE_RE.match(line) or NOTE_LINE_RE.match(line)
        if m:
            head, msg = m.group(1), m.group(2)
            out_lines.append(f"{head} {_sanitize_message(msg)}")
        else:
            out_lines.append(line)
    return "\n".join(out_lines)


def extract_blocks(md_path: Path):
    text = md_path.read_text()
    return MERMAID_RE.findall(text)


def render_block(mmdc: str, block: str, out_png: Path,
                  bg: str = "white", scale: int = 2) -> bool:
    sanitized = sanitize_block(block)
    with tempfile.NamedTemporaryFile(
        mode="w", suffix=".mmd", delete=False
    ) as tf:
        tf.write(sanitized)
        tmp_path = Path(tf.name)
    try:
        cmd = [mmdc, "-i", str(tmp_path), "-o", str(out_png),
               "-b", bg, "-s", str(scale)]
        result = subprocess.run(cmd, capture_output=True, text=True,
                                timeout=120)
        if result.returncode != 0:
            print(f"  ! {out_png.name}: {result.stderr.strip()[:200]}",
                  file=sys.stderr)
            return False
        return out_png.exists()
    except subprocess.TimeoutExpired:
        print(f"  ! {out_png.name}: timeout", file=sys.stderr)
        return False
    finally:
        tmp_path.unlink(missing_ok=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--flows-dir", type=Path,
                    default=Path("docs/protocol/flows"))
    ap.add_argument("--out-dir", type=Path,
                    default=Path("docs/protocol/flows/diagrams"))
    ap.add_argument("--mmdc", default="/tmp/mermaid-tool/node_modules/.bin/mmdc")
    ap.add_argument("--scale", type=int, default=2)
    ap.add_argument("--bg", default="white")
    args = ap.parse_args()

    if not Path(args.mmdc).exists():
        sys.exit(f"mmdc not found at {args.mmdc}")
    if not args.flows_dir.exists():
        sys.exit(f"flows dir not found: {args.flows_dir}")

    args.out_dir.mkdir(parents=True, exist_ok=True)

    mds = sorted(args.flows_dir.glob("*.md"))
    total = 0
    written = 0
    for md in mds:
        if md.name == "INDEX.md": continue
        blocks = extract_blocks(md)
        if not blocks: continue
        stem = md.stem
        for i, block in enumerate(blocks, start=1):
            out_png = args.out_dir / f"{stem}.{i}.png"
            print(f"  · {md.name} block {i} → {out_png.name}")
            total += 1
            if render_block(args.mmdc, block, out_png,
                             bg=args.bg, scale=args.scale):
                written += 1

    print(f"\n  rendered {written}/{total} diagrams")
    print(f"  output dir: {args.out_dir}")


if __name__ == "__main__":
    main()
