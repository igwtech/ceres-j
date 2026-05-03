#!/usr/bin/env python3
"""catalog_extract.py — sweep one or more pcaps and emit the protocol catalog.

For every observed packet type, aggregates evidence ACROSS all input
captures so the catalog answers "which captures back this packet?" not
just "did we see it once". Emits two artifacts:

  - docs/protocol/_data/packets.json — raw per-(direction,type) evidence
    (size stats, per-capture counts, samples, marker correlations)
  - docs/protocol/INDEX.md           — human-readable master table

This is intentionally narrow: it only catalogs *what we observed*. It
does not infer structure or annotate semantics. Per-packet structure
docs live under docs/protocol/packets/ and are written by hand.

Usage:
  catalog_extract.py --pcap-glob 'strace/*.pcap' --out-dir docs/protocol
  catalog_extract.py --pcap one.pcap --pcap two.pcap --out-dir docs/protocol
"""
from __future__ import annotations
import argparse, json, sys, glob, datetime
from pathlib import Path
from collections import defaultdict, Counter

sys.path.insert(0, str(Path(__file__).parent))
from importlib import import_module
decrypt_mod = import_module("decrypt-retail")
burst_mod = import_module("parse-burst")

try:
    from scapy.all import PcapReader, IP, UDP, TCP
except ImportError:
    sys.exit("scapy missing — pip install --user --break-system-packages scapy")


# ─── Marker file parsing ────────────────────────────────────────────────

def parse_markers(path: Path):
    if not path or not path.exists(): return []
    marks = []
    for line in path.read_text(errors="replace").splitlines():
        line = line.strip()
        if not line: continue
        parts = line.split(None, 1)
        if len(parts) < 2: continue
        ts_str, label = parts
        try:
            h, m, s = ts_str.split(":")
            secs = int(h) * 3600 + int(m) * 60 + float(s)
            marks.append((secs, label.strip()))
        except ValueError:
            continue
    return marks


def find_server_ip(pcap: Path):
    peers = Counter()
    with PcapReader(str(pcap)) as pr:
        for i, pkt in enumerate(pr):
            if i > 5000: break
            if IP not in pkt: continue
            for ip in (pkt[IP].src, pkt[IP].dst):
                if not (ip.startswith("127.") or ip.startswith("192.168.")
                        or ip.startswith("172.") or ip.startswith("10.")):
                    peers[ip] += 1
    return peers.most_common(1)[0][0] if peers else None


# ─── Per-pcap pass ──────────────────────────────────────────────────────

def sweep_pcap(pcap: Path, agg: dict, captures: list):
    """Walk one pcap, decrypt UDP, parse TCP framing, fold into agg."""
    server_ip = find_server_ip(pcap)
    if not server_ip:
        print(f"  ! {pcap.name}: no external peer; skipping", file=sys.stderr)
        return

    markers_path = pcap.with_suffix(".markers")
    markers_tod = parse_markers(markers_path)

    capture_start = None
    capture_start_tod = None
    last_ts = None
    pkt_total = 0
    markers_rel = []
    capture_id = pcap.name

    # local counts for this capture (so corpus map can show coverage)
    local_counts = Counter()

    # Per-capture multipart reassembly state. Multipart packets MUST be
    # cataloged as chains, not per-fragment — see
    # feedback_multipart_chain_aware memory. Key:
    # (direction, outer_opcode, reliable_subop, chain_key, disc).
    multipart_state: dict = {}

    with PcapReader(str(pcap)) as pr:
        for pkt in pr:
            if IP not in pkt: continue
            ts = float(pkt.time)
            if capture_start is None:
                capture_start = ts
                dt = datetime.datetime.fromtimestamp(ts)
                capture_start_tod = (dt.hour * 3600 + dt.minute * 60
                                     + dt.second + dt.microsecond / 1e6)
            rel_ts = ts - capture_start
            last_ts = rel_ts

            if pkt[IP].src == server_ip: direction = "S->C"
            elif pkt[IP].dst == server_ip: direction = "C->S"
            else: continue

            if UDP in pkt:
                payload = bytes(pkt[UDP].payload)
                if not payload: continue
                _fold_udp(direction, payload, rel_ts, capture_id, agg,
                          local_counts, multipart_state)
            elif TCP in pkt:
                payload = bytes(pkt[TCP].payload)
                if not payload: continue
                _fold_tcp(direction, payload, rel_ts, capture_id, agg, local_counts)
            pkt_total += 1

    # Any multipart chains that did NOT complete by capture end are
    # logged as "incomplete" so coverage gaps stay visible. They
    # don't enter the catalog as logical packets.
    incomplete = [(k, v) for k, v in multipart_state.items()
                  if v.get("size", 0) > 0]
    if incomplete:
        print(f"    ⚠ {len(incomplete)} multipart chain(s) "
              f"incomplete at end of {capture_id}", file=sys.stderr)

    # Re-baseline markers to relative seconds.
    if capture_start_tod is not None and markers_tod:
        for mtod, label in markers_tod:
            rel = mtod - capture_start_tod
            if rel < -3600: rel += 24 * 3600
            markers_rel.append((rel, label))

    # Correlate markers with each agg entry's ts list (only for this capture)
    if markers_rel:
        for key, entry in agg.items():
            for cap_id, ts_list in entry["ts_per_cap"].items():
                if cap_id != capture_id: continue
                for ts in ts_list:
                    for mts, label in markers_rel:
                        if abs(mts - ts) <= 2.0:
                            entry["markers"][label] += 1

    captures.append({
        "id": capture_id,
        "path": str(pcap),
        "server_ip": server_ip,
        "duration_s": round(last_ts or 0, 1),
        "packets": pkt_total,
        "markers": [{"t": round(t, 1), "label": l} for t, l in markers_rel],
        "type_counts": dict(local_counts),
    })


def _key_str(direction, transport, outer_or_opcode, sub, disc=None):
    if transport == "TCP":
        return f"TCP {direction} 0x{outer_or_opcode:04x}"
    if sub is not None:
        base = f"UDP {direction} 0x{outer_or_opcode:02x}/0x{sub:02x}"
        if disc is not None:
            base += f"/0x{disc:02x}"
        return base
    return f"UDP {direction} 0x{outer_or_opcode:02x}"


# Channels that ship as fragmented chains. The catalog folds these by
# (chain_key, discriminator) and emits one logical row per completed
# reassembly. Add new (outer, sub) pairs here as more multipart-like
# channels are identified — see feedback_multipart_chain_aware memory.
MULTIPART_CHANNELS = {
    (0x03, 0x07),  # canonical NC2 multipart channel — header is
                   # [chain_key 1B][0x00 1B][disc 1B][total_size LE4]
}


def _parse_multipart_header(inner: bytes):
    """Parse the 11-byte per-fragment header.

    Layout (verified against retail pcaps + Ceres-J's
    `PacketBuilderUDP130307.emitMultipart`):

        offset  size  field
        0       2     frag_idx       LE2
        2       4     total_frags    LE4
        6       1     discriminator
        7       3     data_size      LE3 (total reassembled body)
        10      1     chain_key
        11      var   fragment data

    Returns ``(frag_idx, total_frags, disc, total_size, chain_key,
    fragment_data)`` or ``None`` if inner is too short.
    """
    if len(inner) < 11: return None
    frag_idx = int.from_bytes(inner[0:2], "little")
    total_frags = int.from_bytes(inner[2:6], "little")
    disc = inner[6]
    total_size = int.from_bytes(inner[7:10], "little")
    chain_key = inner[10]
    return frag_idx, total_frags, disc, total_size, chain_key, inner[11:]


def _fold_multipart(direction, outer, rt, inner, ts, cap_id,
                     agg, local, state):
    """Accumulate a multipart fragment. When the chain completes,
    emit a single logical entry into ``agg``.

    Chain identity = (direction, chain_key, disc). Completion is
    decided by frag_idx coverage: when we've seen all indices
    ``0..total_frags-1`` for a chain, it's complete.

    The per-fragment row does NOT enter the catalog: the catalog
    counts logical reassembled packets. Per-fragment statistics
    (sizes of individual UDP datagrams) are preserved in the
    logical entry's metadata so MTU analysis is still possible.
    """
    parsed = _parse_multipart_header(inner)
    if parsed is None: return
    frag_idx, total_frags, disc, total_size, chain_key, frag = parsed

    # Sanity bound — protects against parsing nonsense as a chain
    # of millions of fragments. Multipart payloads are bounded by
    # CharInfo size (a few KB → a few dozen fragments at most).
    if total_frags == 0 or total_frags > 1024: return

    state_key = (direction, outer, rt, chain_key, disc)
    st = state.setdefault(state_key, {
        "total_frags": total_frags,
        "total_size": total_size,
        "received": set(),
        "ts0": None,
        "first_inner": b"",
        "frag_sizes": [],
    })
    if st["ts0"] is None:
        st["ts0"] = ts
        st["first_inner"] = inner[:32]
    st["received"].add(frag_idx)
    st["frag_sizes"].append(len(inner))

    if len(st["received"]) >= st["total_frags"]:
        key = _key_str(direction, "UDP", outer, rt, disc=disc)
        entry = _ensure_entry(agg, key)
        entry["count"] += 1
        n = st["total_size"]
        if entry["size_min"] is None or n < entry["size_min"]:
            entry["size_min"] = n
        if n > entry["size_max"]:
            entry["size_max"] = n
        entry["size_sum"] += n
        if entry["first_seen"] is None:
            entry["first_seen"] = cap_id
        if len(entry["samples"]) < 3:
            entry["samples"].append(st["first_inner"][:32].hex())
        entry["captures"][cap_id] += 1
        entry["ts_per_cap"][cap_id].append(st["ts0"])
        meta = entry.setdefault("multipart_meta", {
            "fragments_total": 0,
            "frag_sizes": [],
            "chain_keys": Counter(),
            "disc_seen": Counter(),
        })
        meta["fragments_total"] += len(st["frag_sizes"])
        meta["frag_sizes"].extend(st["frag_sizes"])
        meta["chain_keys"][chain_key] += 1
        meta["disc_seen"][disc] += 1
        local[key] += 1
        del state[state_key]


def _ensure_entry(agg, key):
    if key not in agg:
        agg[key] = {
            "key": key,
            "count": 0,
            "size_min": None, "size_max": 0, "size_sum": 0,
            "first_seen": None,
            "samples": [],
            "captures": Counter(),
            "ts_per_cap": defaultdict(list),
            "markers": Counter(),
        }
    return agg[key]


def _fold(entry, inner: bytes, ts, capture_id):
    entry["count"] += 1
    n = len(inner)
    if entry["size_min"] is None or n < entry["size_min"]:
        entry["size_min"] = n
    if n > entry["size_max"]:
        entry["size_max"] = n
    entry["size_sum"] += n
    if entry["first_seen"] is None:
        entry["first_seen"] = capture_id
    if len(entry["samples"]) < 3:
        entry["samples"].append(inner[:32].hex())
    entry["captures"][capture_id] += 1
    entry["ts_per_cap"][capture_id].append(ts)


def _fold_udp(direction, payload, ts, cap_id, agg, local,
              multipart_state):
    """Decrypt + parse one UDP datagram."""
    r = decrypt_mod.decrypt_wire_packet(payload)
    if not r:
        # Single-byte handshake / abort etc — fold raw first byte
        key = _key_str(direction, "UDP", payload[0], None)
        entry = _ensure_entry(agg, key)
        _fold(entry, payload, ts, cap_id)
        local[key] += 1
        return
    plain = r[0]
    parsed = burst_mod.parse_gamedata(plain)
    if not parsed:
        # Non-0x13 outer (or malformed) — fold by first byte of plaintext
        key = _key_str(direction, "UDP", plain[0] if plain else 0, None)
        entry = _ensure_entry(agg, key)
        _fold(entry, plain, ts, cap_id)
        local[key] += 1
        return
    for sub in parsed["subs"]:
        outer = sub["outer"]
        rt = sub.get("reliable_type")
        inner = sub.get("inner_data") if rt is not None else sub.get("data", b"")
        # Multipart channels are reassembled by chain. Fragments
        # don't enter the catalog as their own row — only the
        # completed logical packet does. See feedback memory.
        if (outer, rt) in MULTIPART_CHANNELS:
            _fold_multipart(direction, outer, rt, inner, ts, cap_id,
                             agg, local, multipart_state)
            continue
        key = _key_str(direction, "UDP", outer, rt)
        entry = _ensure_entry(agg, key)
        _fold(entry, inner, ts, cap_id)
        local[key] += 1


def _fold_tcp(direction, payload, ts, cap_id, agg, local):
    """Walk 0xfe-framed sub-packets in a TCP segment."""
    pos = 0
    while pos + 3 <= len(payload):
        if payload[pos] != 0xfe: break
        sub_len = payload[pos + 1] | (payload[pos + 2] << 8)
        pos += 3
        if pos + sub_len > len(payload): break
        sub = payload[pos:pos + sub_len]
        pos += sub_len
        if len(sub) < 2: continue
        opcode = (sub[0] << 8) | sub[1]
        key = _key_str(direction, "TCP", opcode, None)
        entry = _ensure_entry(agg, key)
        _fold(entry, sub, ts, cap_id)
        local[key] += 1


# ─── Output ─────────────────────────────────────────────────────────────

# Names we know from existing reverse-engineering work. Keeping this in
# code rather than docs because the catalog re-emits the index from raw
# evidence each run; hand-edits would be lost.
RELIABLE_NAMES = {
    0x01: "Resend", 0x07: "Multipart", 0x08: "ZoningEnd",
    0x0d: "TimeSync", 0x1b: "Group1B/PosUpdate", 0x1f: "GamePackets",
    0x22: "CharInfo", 0x23: "InfoResponse", 0x25: "PlayerInfo",
    0x26: "RemoveWorldItem", 0x27: "RequestWorldInfo",
    0x28: "WorldInfo", 0x2b: "CityCom", 0x2c: "StartPos",
    0x2d: "NPCData", 0x2e: "Weather", 0x2f: "UpdateModel",
    0x30: "ShortPlayerInfo", 0x31: "RequestShortPlayer",
    0x24: "?", 0x32: "?", 0x33: "?", 0x09: "?",
}
OUTER_NAMES = {
    0x03: "Reliable", 0x0b: "CPing", 0x0c: "TimeSync",
    0x20: "Movement", 0x2a: "RequestPos", 0x32: "?",
    0x1f: "?", 0x27: "?",
}
TCP_NAMES = {
    0x8000: "HandshakeB", 0x8001: "HandshakeA", 0x8003: "HandshakeC",
    0x8480: "Auth", 0x8482: "GetCharList",
    0x8381: "AuthAck", 0x8383: "?", 0x8385: "CharList",
    0x8301: "AuthB", 0x8305: "UDPServerData",
    0x830c: "Location", 0x830d: "GameinfoReady", 0x838f: "?",
    0x8737: "GetGamedata", 0x873a: "Gamedata", 0x873c: "GetUDPConnection",
    0xa001: "SessionReady-S", 0xa002: "?-S", 0xa003: "SessionReady-C",
}


MULTIPART_DISC_NAMES = {
    (0x03, 0x07, 0x01): "CharInfo",
    (0x03, 0x07, 0x02): "CharsysInfo",
    (0x03, 0x07, 0x03): "Multipart-disc3 (?)",
}


def lookup_name(key: str) -> str:
    parts = key.split()
    # "UDP S->C 0x03/0x07/0x01"  (multipart with disc)
    # "UDP S->C 0x03/0x1f"        (reliable sub)
    # "UDP S->C 0x20"             (raw outer)
    if parts[0] == "UDP":
        opc = parts[2]
        bits = opc.split("/")
        if len(bits) == 3:
            outer = int(bits[0], 16); sub = int(bits[1], 16)
            disc = int(bits[2], 16)
            named = MULTIPART_DISC_NAMES.get((outer, sub, disc))
            if named:
                return f"Multipart/{named}"
            return (f"{OUTER_NAMES.get(outer, '?')}/"
                    f"{RELIABLE_NAMES.get(sub, '?')}/disc=0x{disc:02x}")
        if len(bits) == 2:
            outer = int(bits[0], 16); sub = int(bits[1], 16)
            return f"{OUTER_NAMES.get(outer, '?')}/{RELIABLE_NAMES.get(sub, '?')}"
        outer = int(opc, 16)
        return OUTER_NAMES.get(outer, "?")
    if parts[0] == "TCP":
        opc = int(parts[2], 16)
        return TCP_NAMES.get(opc, "?")
    return "?"


def status_for(entry, captures):
    """Heuristic verification status. The catalog only reports what is
    *evidenced*; deeper structure verification is the per-packet doc's
    job. Status here is just "how broadly observed":

      verified  — appears in ≥2 captures, ≥10 samples
      partial   — appears in ≥1 capture
    """
    if len(entry["captures"]) >= 2 and entry["count"] >= 10:
        return "verified"
    return "partial"


def write_index(agg: dict, captures: list, out_dir: Path):
    rows = sorted(agg.values(),
                  key=lambda e: (e["key"].split()[0],   # transport
                                 e["key"].split()[1],   # direction
                                 -e["count"]))

    lines = []
    lines.append("# Protocol Catalog — Master Index\n\n")
    lines.append(f"_Generated by `tools/catalog_extract.py` on "
                 f"{datetime.date.today().isoformat()}._\n\n")
    lines.append(f"Captures swept: **{len(captures)}**. "
                 f"Unique packet types observed: **{len(rows)}**. "
                 f"Total packets: "
                 f"**{sum(e['count'] for e in rows):,}**.\n\n")
    lines.append("Status legend:\n")
    lines.append("- **verified**: observed in ≥2 captures with ≥10 samples\n")
    lines.append("- **partial**:  observed in ≥1 capture\n")
    lines.append("- **unknown**:  named but not yet observed in the corpus "
                 "(absent from this table)\n\n")
    lines.append("Per-packet structure docs live under "
                 "[`packets/`](packets/). Capture corpus map: "
                 "[`captures/INDEX.md`](captures/INDEX.md).\n\n")

    lines.append("## Catalog\n\n")
    lines.append("| Status | Transport | Dir | Type | Name (best-known) | "
                 "Count | Captures | Size min/avg/max | Top markers | "
                 "Sample |\n")
    lines.append("|---|---|---|---|---|---:|---:|---|---|---|\n")
    for e in rows:
        parts = e["key"].split()
        transport = parts[0]; direction = parts[1]; tp = parts[2]
        name = lookup_name(e["key"])
        avg = e["size_sum"] // max(1, e["count"])
        sz = f"{e['size_min']}/{avg}/{e['size_max']}"
        top_marks = ", ".join(f"{l}({n})"
                              for l, n in e["markers"].most_common(3)) or "—"
        sample = e["samples"][0] if e["samples"] else ""
        if len(sample) > 48: sample = sample[:48] + "..."
        st = status_for(e, captures)
        ncaps = len(e["captures"])
        lines.append(f"| {st} | {transport} | {direction} | `{tp}` | "
                     f"{name} | {e['count']} | {ncaps}/{len(captures)} | "
                     f"{sz} | {top_marks} | `{sample}` |\n")

    out = out_dir / "INDEX.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(lines))
    return out


def write_data(agg: dict, captures: list, out_dir: Path):
    data_dir = out_dir / "_data"
    data_dir.mkdir(parents=True, exist_ok=True)
    # Strip ts_per_cap from JSON output (huge, redundant with markers)
    serializable = {}
    for k, e in agg.items():
        serializable[k] = {
            "count": e["count"],
            "size_min": e["size_min"],
            "size_max": e["size_max"],
            "size_avg": e["size_sum"] // max(1, e["count"]),
            "first_seen_capture": e["first_seen"],
            "captures": dict(e["captures"]),
            "markers": dict(e["markers"]),
            "samples": e["samples"],
        }
    (data_dir / "packets.json").write_text(json.dumps({
        "generated": datetime.datetime.now().isoformat(),
        "captures": captures,
        "packets": serializable,
    }, indent=2))
    return data_dir / "packets.json"


def _packet_filename(key: str) -> str:
    """Map a catalog key to a stable filename slug.

    `UDP S->C 0x03/0x1f`        -> `udp_s2c_03_1f.md`
    `UDP S->C 0x03/0x07/0x01`   -> `udp_s2c_03_07_01.md`  (multipart)
    `TCP C->S 0x8480`           -> `tcp_c2s_8480.md`
    """
    parts = key.split()
    transport = parts[0].lower()
    direction = "s2c" if parts[1] == "S->C" else "c2s"
    opc = parts[2].replace("0x", "").replace("/", "_")
    return f"{transport}_{direction}_{opc}.md"


def write_packet_stubs(agg: dict, captures: list, out_dir: Path,
                       force: bool = False):
    """Emit a stub doc for every observed packet type.

    Stubs are only written if the file does not already exist (so
    hand-curated structure analysis is preserved across re-runs).
    Pass ``force=True`` to overwrite, but you almost never want this —
    use the auto-generated `<!-- catalog-evidence ... -->` markers
    in the existing files for safe regeneration instead.
    """
    pkt_dir = out_dir / "packets"
    pkt_dir.mkdir(parents=True, exist_ok=True)
    written = 0; skipped = 0

    for key, e in agg.items():
        fn = pkt_dir / _packet_filename(key)
        if fn.exists() and not force:
            skipped += 1; continue
        parts = key.split()
        transport = parts[0]; direction = parts[1]; tp = parts[2]
        name = lookup_name(key)
        avg = e["size_sum"] // max(1, e["count"])
        ncaps = len(e["captures"])
        st = status_for(e, captures)

        body = []
        body.append(f"# `{key}` — {name}\n\n")
        body.append(f"**Transport:** {transport}  \n")
        body.append(f"**Direction:** {direction}  \n")
        body.append(f"**Identifier:** `{tp}`  \n")
        body.append(f"**Status:** {st}  \n\n")

        body.append("## Evidence\n\n")
        body.append("<!-- catalog-evidence (auto-generated; do not edit "
                    "manually — regenerate via `tools/catalog_extract.py "
                    "--update-evidence`) -->\n\n")
        body.append(f"- Total observations: **{e['count']}**\n")
        body.append(f"- Captures with this packet: **{ncaps}/{len(captures)}**\n")
        body.append(f"- Size (bytes): min **{e['size_min']}**, "
                    f"avg **{avg}**, max **{e['size_max']}**\n")
        if "multipart_meta" in e:
            mp = e["multipart_meta"]
            n_logical = max(1, e["count"])
            body.append(f"- Multipart: {mp['fragments_total']} fragments "
                        f"reassembled into {n_logical} logical "
                        f"packet(s); avg "
                        f"{mp['fragments_total'] // n_logical} "
                        f"fragments per packet\n")
            if mp["frag_sizes"]:
                fs = mp["frag_sizes"]
                body.append(f"  - Fragment size (bytes): "
                            f"min {min(fs)}, max {max(fs)}\n")
            if mp["chain_keys"]:
                ck = ", ".join(f"0x{k:02x}×{n}"
                               for k, n in mp["chain_keys"].most_common(5))
                body.append(f"  - Chain keys observed: {ck}\n")
        if e["markers"]:
            body.append("- Top markers (within ±2s):\n")
            for label, n in e["markers"].most_common(5):
                body.append(f"  - {label} × {n}\n")
        if e["captures"]:
            body.append("- Per-capture counts:\n")
            for cap, n in e["captures"].most_common(20):
                short = cap.replace("nc2_strace_", "").replace(".pcap", "")
                body.append(f"  - `{short}` × {n}\n")
        if e["samples"]:
            body.append("\nSamples (first 32 bytes inner data):\n\n")
            for i, s in enumerate(e["samples"]):
                body.append(f"```\n#{i+1}: {s}\n```\n")
        body.append("\n<!-- /catalog-evidence -->\n\n")

        body.append("## Structure\n\n")
        body.append("_TODO: byte-level layout. Use evidence above + "
                    "matching pcaps to derive. Cite specific captures "
                    "and offsets._\n\n")

        body.append("## Variants\n\n")
        body.append("_TODO: enumerate observed variants (e.g. different "
                    "sub-tags, optional trailers)._\n\n")

        body.append("## Observed contexts\n\n")
        body.append("_TODO: when does this packet fire? Which "
                    "scenarios trigger it? See top markers above for "
                    "hints._\n\n")

        body.append("## Open questions\n\n")
        body.append("_TODO: list what we don't yet understand._\n\n")

        body.append("## Server-side handler\n\n")
        body.append("_TODO: pointer to the Ceres-J implementation, "
                    "or 'not yet implemented' if missing._\n\n")

        fn.write_text("".join(body))
        written += 1

    return written, skipped


def update_packet_evidence(out_dir: Path, agg: dict, captures: list):
    """Re-write only the `<!-- catalog-evidence ... -->` block in
    existing packet files. Hand-edited sections (Structure, Variants,
    etc.) are preserved verbatim.

    This is the safe way to refresh stats after new captures land.
    """
    pkt_dir = out_dir / "packets"
    if not pkt_dir.exists(): return 0
    import re
    BLOCK_RE = re.compile(
        r"<!-- catalog-evidence.*?-->.*?<!-- /catalog-evidence -->",
        re.DOTALL,
    )
    updated = 0
    for key, e in agg.items():
        fn = pkt_dir / _packet_filename(key)
        if not fn.exists(): continue
        existing = fn.read_text()
        if not BLOCK_RE.search(existing): continue
        # Build new evidence block
        avg = e["size_sum"] // max(1, e["count"])
        ncaps = len(e["captures"])
        block = ["<!-- catalog-evidence (auto-generated; do not edit "
                 "manually — regenerate via `tools/catalog_extract.py "
                 "--update-evidence`) -->\n\n"]
        block.append(f"- Total observations: **{e['count']}**\n")
        block.append(f"- Captures with this packet: **{ncaps}/{len(captures)}**\n")
        block.append(f"- Size (bytes): min **{e['size_min']}**, "
                     f"avg **{avg}**, max **{e['size_max']}**\n")
        if "multipart_meta" in e:
            mp = e["multipart_meta"]
            n_logical = max(1, e["count"])
            block.append(f"- Multipart: {mp['fragments_total']} fragments "
                         f"reassembled into {n_logical} logical "
                         f"packet(s); avg "
                         f"{mp['fragments_total'] // n_logical} "
                         f"fragments per packet\n")
            if mp["frag_sizes"]:
                fs = mp["frag_sizes"]
                block.append(f"  - Fragment size (bytes): "
                             f"min {min(fs)}, max {max(fs)}\n")
            if mp["chain_keys"]:
                ck = ", ".join(f"0x{k:02x}×{n}"
                               for k, n in mp["chain_keys"].most_common(5))
                block.append(f"  - Chain keys observed: {ck}\n")
        if e["markers"]:
            block.append("- Top markers (within ±2s):\n")
            for label, n in e["markers"].most_common(5):
                block.append(f"  - {label} × {n}\n")
        if e["captures"]:
            block.append("- Per-capture counts:\n")
            for cap, n in e["captures"].most_common(20):
                short = cap.replace("nc2_strace_", "").replace(".pcap", "")
                block.append(f"  - `{short}` × {n}\n")
        if e["samples"]:
            block.append("\nSamples (first 32 bytes inner data):\n\n")
            for i, s in enumerate(e["samples"]):
                block.append(f"```\n#{i+1}: {s}\n```\n")
        block.append("\n<!-- /catalog-evidence -->")
        new = BLOCK_RE.sub("".join(block), existing)
        if new != existing:
            fn.write_text(new)
            updated += 1
    return updated


def write_packets_index(agg: dict, captures: list, out_dir: Path):
    """Navigation page linking every per-packet stub. Grouped by
    transport+direction so it mirrors the codebase's mental model."""
    pkt_dir = out_dir / "packets"
    pkt_dir.mkdir(parents=True, exist_ok=True)

    groups = {"TCP C->S": [], "TCP S->C": [], "UDP C->S": [], "UDP S->C": []}
    for key, e in agg.items():
        parts = key.split()
        gkey = f"{parts[0]} {parts[1]}"
        groups.setdefault(gkey, []).append((key, e))
    for v in groups.values():
        v.sort(key=lambda r: -r[1]["count"])

    lines = []
    lines.append("# Per-packet structure docs\n\n")
    lines.append(f"_Auto-generated by `tools/catalog_extract.py` on "
                 f"{datetime.date.today().isoformat()}._\n\n")
    lines.append("Each packet type observed in retail captures gets its "
                 "own structure doc. Stub fields ('Structure', "
                 "'Variants', 'Observed contexts', 'Open questions', "
                 "'Server-side handler') are hand-curated; the "
                 "evidence block at the top of each file is "
                 "auto-refreshed via "
                 "`tools/catalog_extract.py --update-evidence`.\n\n")
    for gkey in ("TCP C->S", "TCP S->C", "UDP C->S", "UDP S->C"):
        rows = groups.get(gkey, [])
        if not rows: continue
        lines.append(f"## {gkey}\n\n")
        lines.append("| Type | Name | Status | Count | "
                     "Captures | Doc |\n")
        lines.append("|---|---|---|---:|---:|---|\n")
        for key, e in rows:
            tp = key.split()[2]
            name = lookup_name(key)
            st = status_for(e, captures)
            ncaps = len(e["captures"])
            fn = _packet_filename(key)
            lines.append(f"| `{tp}` | {name} | {st} | "
                         f"{e['count']} | {ncaps}/{len(captures)} | "
                         f"[{fn}](packets/{fn}) |\n")
        lines.append("\n")

    out = out_dir / "packets" / "INDEX.md"
    out.write_text("".join(lines))
    return out


def write_capture_index(captures: list, agg: dict, out_dir: Path):
    cap_dir = out_dir / "captures"
    cap_dir.mkdir(parents=True, exist_ok=True)
    lines = []
    lines.append("# Capture Corpus\n\n")
    lines.append(f"_Generated by `tools/catalog_extract.py` on "
                 f"{datetime.date.today().isoformat()}._\n\n")
    lines.append(f"{len(captures)} retail captures swept. "
                 "Each row describes a pcap file under "
                 "`ceres-j/strace/`. The 'unique types' column counts "
                 "how many distinct packet types appear in that "
                 "capture — useful for spotting gaps in coverage.\n\n")

    # Sort by date in filename if present, else alphabetic
    captures = sorted(captures, key=lambda c: c["id"])

    lines.append("| Capture | Server | Duration | Packets | Unique types | "
                 "Markers | Notes |\n")
    lines.append("|---|---|---:|---:|---:|---|---|\n")
    for c in captures:
        name = c["id"].replace("nc2_strace_", "").replace(".pcap", "")
        marker_str = (f"{len(c['markers'])} markers"
                      if c["markers"] else "—")
        lines.append(f"| `{c['id']}` | `{c['server_ip']}` | "
                     f"{c['duration_s']}s | {c['packets']} | "
                     f"{len(c['type_counts'])} | {marker_str} | "
                     f"_{_scenario_hint(name)}_ |\n")

    # Per-capture detail blocks
    lines.append("\n## Per-capture detail\n\n")
    for c in captures:
        name = c["id"].replace("nc2_strace_", "").replace(".pcap", "")
        lines.append(f"### `{c['id']}`\n\n")
        lines.append(f"- Server: `{c['server_ip']}`\n")
        lines.append(f"- Duration: {c['duration_s']}s\n")
        lines.append(f"- Packets: {c['packets']}\n")
        lines.append(f"- Hint: {_scenario_hint(name)}\n")
        if c["markers"]:
            lines.append(f"- Markers ({len(c['markers'])}): "
                         + ", ".join(f"{m['t']}s={m['label']}"
                                     for m in c['markers'][:8]))
            if len(c["markers"]) > 8: lines.append(", …")
            lines.append("\n")
        # Top 10 most frequent types in this capture
        top = sorted(c["type_counts"].items(),
                     key=lambda x: -x[1])[:10]
        if top:
            lines.append(f"- Top types: "
                         + ", ".join(f"`{t}`×{n}" for t, n in top)
                         + "\n")
        lines.append("\n")

    out = cap_dir / "INDEX.md"
    out.write_text("".join(lines))
    return out


def _scenario_hint(name: str) -> str:
    """Best-guess scenario from the capture filename. Hand-curated
    refinements should go in captures/INDEX.md notes column over
    time; this is just a starting label."""
    n = name.upper()
    if "PLAZA_TO_PEPPER" in n: return "cross-district zone walk"
    if "ZONING_AND_ITEMS_LONG" in n: return "long session: zoning, NPCs, vendor, inventory"
    if "CASH_VENDOR" in n: return "vendor buy / cash carrier"
    if "DRSTONE" in n: return "Dr Stone tutorial / Genesis dungeon"
    if "FALL_DAMAGE" in n: return "fall damage"
    if "DEATH" in n: return "death"
    if "FULL_PCAP_TRACE" in n: return "full session reference"
    if "ACC1_CHAR1" in n or "ACC1_CHAR2" in n or "ACC2_CHAR1" in n or "ACC2_CHAR2" in n:
        return "multi-account login set"
    if "AUGUSTO" in n or "HANNIBAL" in n or "NORMAN" in n or "ODA" in n:
        return "character-specific session"
    return "?"


def _is_ceres_capture(name: str) -> bool:
    """The catalog only indexes retail traffic. Ceres-J test captures
    must be excluded so server-side bugs don't get cooked into the
    'this is what retail does' baseline. Match anything with CERESJ
    anywhere in the filename — even RETAIL_CERESJ_* (capture script
    is named retail but server is Ceres-J)."""
    return "CERESJ" in name.upper()


# ─── Main ───────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pcap", action="append", default=[], type=Path,
                    help="One pcap (repeatable)")
    ap.add_argument("--pcap-glob", default=None,
                    help="Glob for pcaps")
    ap.add_argument("--out-dir", type=Path,
                    default=Path("docs/protocol"))
    ap.add_argument("--limit", type=int, default=0,
                    help="Stop each pcap after N packets (0=full)")
    ap.add_argument("--stubs", action="store_true",
                    help="Also write per-packet stub files for any "
                         "type that doesn't already have one. Existing "
                         "files are NOT touched (use --force to "
                         "overwrite, or --update-evidence to refresh "
                         "just the evidence block).")
    ap.add_argument("--update-evidence", action="store_true",
                    help="Refresh the auto-generated evidence block in "
                         "existing packet files. Hand-edited sections "
                         "are preserved.")
    ap.add_argument("--force", action="store_true",
                    help="Used with --stubs: overwrite existing files. "
                         "Destructive; almost never what you want.")
    args = ap.parse_args()

    pcaps = list(args.pcap)
    if args.pcap_glob:
        pcaps += [Path(p) for p in glob.glob(args.pcap_glob)]
    if not pcaps:
        sys.exit("no pcaps; pass --pcap or --pcap-glob")

    # Filter to retail-only by default. Ceres-J test captures contaminate
    # the catalog because the server-side bugs would be indistinguishable
    # from real retail behavior in the resulting INDEX.
    skipped = []
    retail = []
    for p in pcaps:
        if _is_ceres_capture(p.name):
            skipped.append(p.name)
        else:
            retail.append(p)
    if skipped:
        print(f"  ⊘ skipping {len(skipped)} ceres test capture(s):")
        for s in skipped: print(f"      {s}")
    pcaps = retail

    agg: dict = {}
    captures: list = []
    for p in pcaps:
        if not p.exists():
            print(f"  ! {p}: missing; skipping", file=sys.stderr); continue
        print(f"  · {p.name}")
        try:
            sweep_pcap(p, agg, captures)
        except Exception as e:
            print(f"  ! {p.name}: {type(e).__name__}: {e}", file=sys.stderr)

    args.out_dir.mkdir(parents=True, exist_ok=True)
    idx = write_index(agg, captures, args.out_dir)
    data = write_data(agg, captures, args.out_dir)
    cap_idx = write_capture_index(captures, agg, args.out_dir)
    pkt_idx = write_packets_index(agg, captures, args.out_dir)
    print(f"\nwrote {idx}")
    print(f"wrote {data}")
    print(f"wrote {cap_idx}")
    print(f"wrote {pkt_idx}")

    if args.stubs:
        w, s = write_packet_stubs(agg, captures, args.out_dir,
                                   force=args.force)
        print(f"  packet stubs: {w} written, {s} preserved")
    if args.update_evidence:
        u = update_packet_evidence(args.out_dir, agg, captures)
        print(f"  packet evidence blocks refreshed: {u}")

    print(f"\n  {len(agg)} unique packet types")
    print(f"  {sum(e['count'] for e in agg.values()):,} total packets")
    print(f"  {len(captures)} captures swept")


if __name__ == "__main__":
    main()
