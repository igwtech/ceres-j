#!/usr/bin/env python3
"""Quote-aware summary of the Ghidra RE export (functions.csv).
Writes SUMMARY.md into ceres-j/docs/re_export/."""
import csv, collections, pathlib, re

base = pathlib.Path(__file__).resolve().parents[2] / "docs" / "re_export"
rows = list(csv.DictReader(open(base / "functions.csv", newline="")))

total = len(rows)
fun   = sum(1 for r in rows if r["name"].startswith("FUN_"))
thunk = sum(1 for r in rows if "thunk" in r["name"].lower())
named = total - fun
tagged = sum(1 for r in rows if r["src"])

# namespaced (method) functions, excluding toolchain noise
NOISE = re.compile(r"^(std|stdext|Concurrency|__|FID_|`anonymous|operator|fuzzer|`)", re.I)
ns_counts = collections.Counter()
for r in rows:
    ns = r["namespace"].strip()
    if ns and ns != "Global":
        ns_counts[ns] += 1

game_ns = [(n, c) for n, c in ns_counts.items() if not NOISE.search(n)]
game_ns.sort(key=lambda x: -x[1])

# functions tagged by source file
src_counts = collections.Counter()
for r in rows:
    if r["src"]:
        for f in re.findall(r"[\w/]+\.(?:cpp|cxx|c|h|hpp)", r["src"]):
            src_counts[f] += 1

out = []
out.append("# Neocron client — Ghidra RE export summary\n")
out.append(f"- Binary: `neocronclient.exe` (PE32 i386, ImageBase 0x00400000)\n")
out.append(f"- Functions total: **{total}**  | named: **{named}**  | unnamed `FUN_`: **{fun}**  | thunks: {thunk}\n")
out.append(f"- C++ classes (RTTI): see `classes.txt`  | vftables: see `vftables.txt`\n")
out.append(f"- Functions tagged with source file: **{tagged}**\n")
out.append(f"- Namespaced functions (methods under a class/namespace): **{sum(ns_counts.values())}**\n")
out.append("\n## Top game classes/namespaces by member count\n")
out.append("(toolchain/STL noise filtered out)\n\n| count | class/namespace |\n|---:|:---|\n")
for n, c in game_ns[:60]:
    out.append(f"| {c} | `{n}` |\n")
out.append("\n## Functions per source file (from __FILE__/assert strings)\n\n| count | source file |\n|---:|:---|\n")
for f, c in src_counts.most_common():
    out.append(f"| {c} | `{f}` |\n")

(base / "SUMMARY.md").write_text("".join(out))
print(f"wrote {base/'SUMMARY.md'}")
print(f"total={total} named={named} FUN_={fun} tagged={tagged} game_ns={len(game_ns)}")
print("top game classes:", game_ns[:12])
