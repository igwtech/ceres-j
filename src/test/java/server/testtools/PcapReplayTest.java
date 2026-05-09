package server.testtools;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * First-divergence regression for the pcap replay harness.
 *
 * <p>This test loads ONE retail UDP capture, replays the C→S
 * sub-packets against a fresh {@link ReplayHarness}, and emits
 * a unified diff between the resulting S→C plaintext bytes and
 * what retail actually responded with. Designed to FAIL on the
 * first run — the failure message becomes the next-bug-to-fix
 * backlog item.
 *
 * <h3>How to run</h3>
 * <pre>
 *   # CI runs other tests; this one needs an env var so it
 *   # only fires when a developer asks for it.
 *   NC2_PCAP_REPLAY=1 mvn test -Dtest=PcapReplayTest
 * </pre>
 *
 * <p>By default the test {@code Assume}-skips so the green
 * suite stays green. To override the pcap, set
 * {@code NC2_PCAP_REPLAY_PATH=/abs/path/to.pcap}.
 *
 * <h3>What it asserts</h3>
 *
 * <ol>
 *   <li>The pcap loads cleanly (peer auto-detect succeeded,
 *       at least one C→S sub-packet decoded).</li>
 *   <li>For every C→S sub-packet, replaying it through
 *       {@code GamePacketReaderUDP.decodesub13} either drives an
 *       event or is recognised as a fall-through.</li>
 *   <li>The cumulative S→C plaintext bytes the harness emits
 *       are a prefix of the retail S→C sub-packet stream up to
 *       the first divergence — and the divergence point is
 *       reported with byte-level context.</li>
 * </ol>
 *
 * <p>Today this is expected to FAIL — every divergence we find
 * is one less protocol bug we're guessing at.
 */
public class PcapReplayTest {

    /** Default pcap path: pick the smallest retail capture in
     *  the {@code strace/} corpus. Override with
     *  {@code NC2_PCAP_REPLAY_PATH}. */
    private static final String DEFAULT_PCAP =
            System.getProperty("user.dir")
                    + "/strace/nc2_strace_RETAIL_DRSTONE3"
                    + "_20260501_181349.pcap";

    /**
     * Resolve the configured pcap path (env override takes
     * precedence). Returns null if the file isn't present —
     * lets the {@link #pcapReplayDriverProducesActionableDiff}
     * test {@code Assume}-skip cleanly on developer machines
     * without the corpus.
     */
    private static File resolvePcap() {
        String override = System.getenv("NC2_PCAP_REPLAY_PATH");
        File f = new File(override != null ? override : DEFAULT_PCAP);
        return f.isFile() ? f : null;
    }

    @Test
    public void pcapReplayDriverProducesActionableDiff()
            throws Exception {
        // Gate on env var so CI stays green. Developers run with
        // NC2_PCAP_REPLAY=1 to surface the first divergence.
        boolean enabled = "1".equals(
                System.getenv("NC2_PCAP_REPLAY"));
        assumeTrue("NC2_PCAP_REPLAY env var not set; "
                + "skipping pcap-replay regression. "
                + "Run with NC2_PCAP_REPLAY=1 to see the first "
                + "retail-vs-Ceres-J divergence.",
                enabled);

        File pcap = resolvePcap();
        assumeTrue("retail pcap not found at " + DEFAULT_PCAP
                + " — set NC2_PCAP_REPLAY_PATH to override.",
                pcap != null);

        PcapReplay.Loaded loaded = PcapReplay.load(pcap);
        assertNotNull("pcap parse returned null", loaded);
        assertTrue("no records decoded from " + pcap.getName(),
                !loaded.records.isEmpty());

        // Build per-direction sub-packet timelines. We compare
        // C→S inputs (replayed) to S→C outputs (expected from
        // retail's bytes).
        List<PcapReplay.Record> c2s = new ArrayList<>();
        List<PcapReplay.Record> s2c = new ArrayList<>();
        for (PcapReplay.Record r : loaded.records) {
            if (r.proto == PcapReplay.Proto.UDP_SUB
                    || r.proto == PcapReplay.Proto.UDP_RAW) {
                if (r.direction == PcapReplay.Direction.C2S) {
                    c2s.add(r);
                } else {
                    s2c.add(r);
                }
            }
        }
        assertTrue("expected at least 1 C→S sub-packet in pcap",
                !c2s.isEmpty());

        ReplayHarness h = new ReplayHarness();

        // Replay every C→S sub-packet in order. Capture a
        // running total of S→C plaintext bytes emitted and
        // compare against the retail S→C timeline as we go.
        StringBuilder report = new StringBuilder();
        report.append("Pcap: ").append(pcap.getName()).append('\n');
        report.append("Server IP: ").append(loaded.serverIp)
                .append('\n');
        report.append("Records: ").append(loaded.records.size())
                .append('\n');
        report.append("C→S sub-packets: ").append(c2s.size())
                .append('\n');
        report.append("S→C sub-packets: ").append(s2c.size())
                .append('\n');
        report.append("Counts: ").append(loaded.counts).append('\n');
        report.append('\n');

        int s2cIdx = 0;
        int firstDivergenceStep = -1;
        String firstDivergenceMsg = null;
        int recognisedCount = 0;
        int unrecognisedCount = 0;

        // Collect EVERY divergence (not just the first) so each
        // test run produces a complete backlog snapshot. The
        // failure message lists all of them grouped by sub-tag,
        // most-frequent-first — same diagnostic pattern that made
        // the agent reports useful.
        java.util.List<String> divergences =
                new java.util.ArrayList<>();
        java.util.Map<String, Integer> divergenceCountBySubTag =
                new java.util.LinkedHashMap<>();
        // Up to 3 sample diff messages per sub-tag, so the failure
        // report shows actual bytes for every cluster — not just the
        // single first divergence. Lets one harness run drive the
        // whole backlog.
        java.util.Map<String, java.util.List<String>>
                samplesBySubTag =
                        new java.util.LinkedHashMap<>();
        final int SAMPLES_PER_SUBTAG = 3;

        for (int i = 0; i < c2s.size(); i++) {
            PcapReplay.Record r = c2s.get(i);
            ReplayHarness.DriveResult res;
            try {
                // UDP_RAW = outer-frame raw datagram (0x01
                // handshake, 0x08 abort, raw 0x03 reliable). Drive
                // it through decode() so handshake replies fire
                // during replay — keeps S→C alignment with retail
                // when the capture starts at handshake.
                // UDP_SUB = inner sub-packet within a 0x13 outer
                // frame. Drive via decodesub13().
                res = (r.proto == PcapReplay.Proto.UDP_RAW)
                        ? h.driveRaw(r.bytes)
                        : h.drive(r.bytes);
            } catch (RuntimeException ex) {
                report.append(String.format(
                        "[step %d] decoder threw on C→S sub %s: %s\n",
                        i, r.previewHex(16), ex.getMessage()));
                if (firstDivergenceStep < 0) {
                    firstDivergenceStep = i;
                    firstDivergenceMsg = "decoder threw: "
                            + ex.getClass().getSimpleName()
                            + " — " + ex.getMessage();
                }
                continue;
            }
            if (res.wasRecognised() && !res.wasUnknown()) {
                recognisedCount++;
            } else {
                unrecognisedCount++;
            }

            // Compare each emitted S→C raw plaintext against the
            // next retail S→C bytes in order. We use the first
            // 1+sub-tag bytes as the "shape" key so a length
            // mismatch is reported as a body diff, not as a
            // frame-shape mismatch.
            for (byte[] emitted : res.udpRawBytesThisStep) {
                if (s2cIdx >= s2c.size()) {
                    if (firstDivergenceStep < 0) {
                        firstDivergenceStep = i;
                        firstDivergenceMsg = String.format(
                                "Ceres-J emitted EXTRA S→C "
                                + "(%dB starting %s) but retail "
                                + "had no more responses",
                                emitted.length,
                                hexPreview(emitted, 16));
                    }
                    break;
                }
                // Skip retail's "spare" UDPAlive entries —
                // packets retail emitted as periodic keepalive
                // (~3.2s spacing per HANNIBAL pcap analysis,
                // 2026-05-09) that Ceres-J doesn't yet emit. Don't
                // count them against the byte diff; advance past
                // them in the queue. Heuristic: a 7B 0x04
                // followed by an emitted-bytes shape that doesn't
                // match (i.e. retail has a UDPAlive here but
                // Ceres-J doesn't) — skip the retail entry and
                // re-pair against the next retail S→C.
                while (s2cIdx < s2c.size()
                        && (isSpareUDPAlive(
                                s2c.get(s2cIdx).bytes, emitted)
                            || isUnreplicableNpcBroadcast(
                                s2c.get(s2cIdx).bytes, emitted)
                            || isUnreplicableInitBurst(
                                s2c.get(s2cIdx).bytes, emitted)
                            || isUnreplicableEntityState(
                                s2c.get(s2cIdx).bytes, emitted))) {
                    s2cIdx++;
                }
                if (s2cIdx >= s2c.size()) break;
                PcapReplay.Record exp = s2c.get(s2cIdx++);
                String diff = diff(exp.bytes, emitted);
                if (diff != null) {
                    String divMsg = String.format(
                            "step %d (C→S %s):%n"
                            + "  expected S→C [%dB]: %s%n"
                            + "  actual   S→C [%dB]: %s%n"
                            + "  %s",
                            i, r.previewHex(8),
                            exp.bytes.length,
                            hexPreview(exp.bytes, 32),
                            emitted.length,
                            hexPreview(emitted, 32),
                            diff);
                    if (firstDivergenceStep < 0) {
                        firstDivergenceStep = i;
                        firstDivergenceMsg = divMsg;
                    }
                    divergences.add(divMsg);
                    String subTagKey = subTagKeyOf(exp.bytes);
                    divergenceCountBySubTag.merge(
                            subTagKey, 1, Integer::sum);
                    java.util.List<String> bucket =
                            samplesBySubTag.computeIfAbsent(
                                    subTagKey,
                                    k -> new java.util.ArrayList<>());
                    if (bucket.size() < SAMPLES_PER_SUBTAG) {
                        bucket.add(divMsg);
                    }
                }
            }
        }

        report.append("Decoder recognised: ").append(recognisedCount)
                .append('\n');
        report.append("Decoder fallthrough: ")
                .append(unrecognisedCount).append('\n');
        report.append("S→C emitted by Ceres-J: ")
                .append(h.emittedRawBytes().size()).append('\n');
        report.append("S→C in retail capture: ")
                .append(s2c.size()).append('\n');

        if (firstDivergenceStep >= 0) {
            report.append('\n');
            report.append("Total divergences: ")
                    .append(divergences.size())
                    .append('\n');
            report.append("By sub-tag (most-frequent first):\n");
            divergenceCountBySubTag.entrySet().stream()
                    .sorted((a, b) -> b.getValue()
                            - a.getValue())
                    .forEach(e -> report.append("  ")
                            .append(e.getKey())
                            .append(" × ")
                            .append(e.getValue())
                            .append('\n'));
            report.append('\n');
            // Print up to N sample diffs per sub-tag, ordered by
            // count descending. Surfaces every cluster's actual
            // retail-vs-Ceres-J bytes, so a single harness run is a
            // complete actionable backlog (not just the first bug).
            report.append("SAMPLES PER SUB-TAG (up to ")
                    .append(SAMPLES_PER_SUBTAG)
                    .append(" per cluster):\n");
            divergenceCountBySubTag.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> {
                        report.append("\n--- ")
                                .append(e.getKey())
                                .append(" (")
                                .append(e.getValue())
                                .append(" total) ---\n");
                        java.util.List<String> bucket =
                                samplesBySubTag.get(e.getKey());
                        if (bucket != null) {
                            for (String s : bucket) {
                                report.append(s).append('\n');
                            }
                        }
                    });
            report.append('\n');
            report.append("FIRST DIVERGENCE at step ")
                    .append(firstDivergenceStep)
                    .append(":\n");
            report.append(firstDivergenceMsg).append('\n');
            // Surface as test failure with a clean, copy-pastable
            // diff. This is the actionable backlog item.
            org.junit.Assert.fail(report.toString());
        }
        // No divergence — the harness round-trips this capture.
        // Either we got everything right or the capture is too
        // short to expose a bug. Either way, report.
        System.out.println(report);
    }

    /** First-byte-mismatch diff. Returns null when bytes match,
     *  else a short human-readable description of the divergence
     *  (length, first differing offset).
     *
     *  <p>Inherently session-derived bytes are exempted from the
     *  comparison — see {@link #isSessionDerivedByte}. UDPAlive's
     *  {@code -sessionkey LE2 + port LE2} (bytes 3..6) are random
     *  per server boot and per ephemeral-port allocation; treating
     *  them as divergence would mask every real bug after the
     *  handshake. */
    static String diff(byte[] expected, byte[] actual) {
        if (expected == null || actual == null) {
            return "null buffer (expected="
                    + (expected == null ? "null" : expected.length)
                    + " actual="
                    + (actual == null ? "null" : actual.length)
                    + ")";
        }
        // PcapReplay's UDP_SUB records strip the outer 0x13 frame
        // (returns just the inner sub-packet body). Ceres-J's
        // CapturingUDPConnection captures the FULL emitted
        // datagram including any 0x13 wrapper. When retail's
        // expected bytes start with the inner sub-packet (e.g.
        // 0x03/0x2c) and Ceres-J emitted a 0x13-wrapped variant,
        // strip the 7-byte 0x13 header from the actual bytes
        // before comparison.
        // Outer 0x13 layout: [0x13][counter LE2][counter+sk LE2]
        //                    [len LE2][inner...] = 7B header.
        if (actual.length >= 7
                && (actual[0] & 0xFF) == 0x13
                && (expected.length == 0
                        || (expected[0] & 0xFF) != 0x13)) {
            byte[] stripped = new byte[actual.length - 7];
            System.arraycopy(actual, 7, stripped, 0,
                    stripped.length);
            actual = stripped;
        }
        int n = Math.min(expected.length, actual.length);
        int firstDiff = -1;
        for (int i = 0; i < n; i++) {
            if (expected[i] != actual[i]
                    && !isSessionDerivedByte(expected, i)) {
                firstDiff = i;
                break;
            }
        }
        if (expected.length == actual.length && firstDiff < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (expected.length != actual.length) {
            sb.append("len ")
                    .append(expected.length).append(" vs ")
                    .append(actual.length);
        }
        if (firstDiff >= 0) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("first byte diff @offset ")
                    .append(firstDiff)
                    .append(": expected 0x")
                    .append(String.format("%02x",
                            expected[firstDiff] & 0xFF))
                    .append(" got 0x")
                    .append(String.format("%02x",
                            actual[firstDiff] & 0xFF));
        }
        return sb.toString();
    }

    /** Format the packet's outer + sub-tag (or first byte if not
     *  a 0x03/* reliable) for grouping divergences. Examples:
     *  {@code "0x03/0x2c"}, {@code "0x04"}, {@code "0x13/0x03/0x1b"}.
     *  Useful for "where are most of the divergences clustered"
     *  question. */
    static String subTagKeyOf(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "(empty)";
        int b0 = bytes[0] & 0xFF;
        if (b0 == 0x03 && bytes.length >= 4) {
            return String.format("0x03/0x%02x",
                    bytes[3] & 0xFF);
        }
        if (b0 == 0x13 && bytes.length >= 8) {
            int inner = bytes[7] & 0xFF;
            if (inner == 0x03 && bytes.length >= 11) {
                return String.format("0x13/0x03/0x%02x",
                        bytes[10] & 0xFF);
            }
            return String.format("0x13/0x%02x", inner);
        }
        return String.format("0x%02x", b0);
    }

    /** Heuristic: is {@code retailBytes} a "spare" UDPAlive that
     *  Ceres-J hasn't emitted? True iff retail has a 7B 0x04
     *  packet at this position AND the bytes Ceres-J just emitted
     *  do NOT also start with 0x04 (i.e. Ceres-J emitted something
     *  else, like a 0x13-wrapped reliable). When true, the harness
     *  advances past the retail entry without counting it as a
     *  divergence.
     *
     *  <p>Why: HANNIBAL pcap shows retail emits a UDPAlive every
     *  ~3.2s through the session (8 in HANNIBAL, only 4 of which
     *  are the handshake-reply burst). Ceres-J doesn't yet emit
     *  this periodic keepalive (task #158). Without this skip,
     *  every retail UDPAlive without a Ceres-J match shows up as
     *  a false divergence. */
    static boolean isSpareUDPAlive(byte[] retailBytes,
                                   byte[] cerJBytes) {
        if (retailBytes.length != 7) return false;
        if ((retailBytes[0] & 0xFF) != 0x04) return false;
        if (cerJBytes.length == 0) return false;
        // Ceres-J also emitted a 7B 0x04 — pair them, don't skip.
        if (cerJBytes.length == 7
                && (cerJBytes[0] & 0xFF) == 0x04) {
            return false;
        }
        return true;
    }

    /** Heuristic: is {@code retailBytes} an unreplicable position
     *  broadcast that Ceres-J's test fixture has no entity-state to
     *  mirror?
     *
     *  <p>True iff retail emits a raw {@code 0x1b} 19B
     *  ObjectPositionBroadcast or a reliable {@code 0x03/0x1b}
     *  PlayerPositionUpdate AND Ceres-J's just-emitted bytes do NOT
     *  start with {@code 0x1b} or {@code 0x03/0x1b}. Skipping these
     *  keeps the harness's S→C alignment intact through retail
     *  capture sessions that have live NPCs and other players —
     *  state our test fixture does not have.
     *
     *  <p><b>Why this is a skip and not a fix</b>: the on-wire
     *  19B raw {@code 0x1b} layout is verified against retail (3
     *  pcaps, 30 samples, see {@code tools/extract-1b-broadcasts.py}
     *  — bytes [0,3,4,5,15,16] constant; [1..2] obj id, [6..11]
     *  YZX, [12] orient, [13..14] entity-class id, [17..18]
     *  state). Reproducing the entity-class id and trailer state
     *  byte-for-byte requires populating the test fixture with the
     *  exact NPC roster from each retail capture, which is brittle
     *  and out of scope. The packet builder
     *  ({@code ObjectPositionBroadcast.java}) emits the verified
     *  19B layout — this skip lets the harness ignore retail's
     *  position-broadcast emissions when Ceres-J has nothing to
     *  echo, focusing it on real packet-byte regressions instead.
     */
    /** Heuristic: is {@code retailBytes} a retail "init-burst" or
     *  zone-state packet that Ceres-J's test fixture emits no
     *  matching response for at this step?
     *
     *  <p>True iff {@code cerJBytes} is exactly a 9B raw {@code 0x0b}
     *  SPing reply (Ceres-J's response to a CPing) AND
     *  {@code retailBytes} is something other than a SPing reply —
     *  meaning retail's queue interleaves an extra emission (e.g.
     *  init-burst {@code 0x02}, reliable {@code 0x03/0x23}
     *  InfoResponse, {@code 0x03/0x33} ChatList) at this step that
     *  Ceres-J doesn't emit because its WorldEntryEvent never fired
     *  in the replay (no real handshake → no init burst).
     *
     *  <p><b>Why this skip is correct</b>: when the harness sees a
     *  CPing C→S, it expects a SPing reply S→C — both Ceres-J and
     *  retail emit the same SPing reply. But retail's S→C queue
     *  also contains additional, unrelated emissions queued at this
     *  point in time (NPC broadcasts, init-burst replays, etc.).
     *  Without this skip, the harness mispairs Ceres-J's SPing
     *  against retail's NEXT queue entry, which is one of the
     *  extras — counting it as a divergence.
     *
     *  <p>The matching SPing comes after the extras in retail's
     *  queue. Skipping the non-SPing extras lets the harness pair
     *  Ceres-J's SPing against retail's matching SPing.
     */
    static boolean isUnreplicableInitBurst(byte[] retailBytes,
                                           byte[] cerJBytes) {
        // Only fires when Ceres-J emitted exactly a 9B raw SPing
        // reply (CPing → SPing, the only thing the harness handles
        // mid-session reliably).
        if (cerJBytes.length != 9) return false;
        if ((cerJBytes[0] & 0xFF) != 0x0b) return false;
        if (retailBytes.length == 0) return false;
        // If retail's bytes are also a SPing reply (raw 9B 0x0b),
        // pair them — don't skip.
        if (retailBytes.length == 9
                && (retailBytes[0] & 0xFF) == 0x0b) {
            return false;
        }
        return true;
    }

    /** Heuristic: is {@code retailBytes} an entity-state emission
     *  (NPC AI, gameplay event, world snapshot) that Ceres-J's
     *  empty test fixture has no source data to replicate?
     *
     *  <p>Sub-tags treated as unreplicable entity-state when
     *  Ceres-J emits a DIFFERENT sub-tag at this step:
     *  <ul>
     *    <li>{@code 0x03/0x1f} — GamePackets tag bursts (NPC AI,
     *        weapon-fire, item interactions, etc.)</li>
     *    <li>{@code 0x03/0x2d} — NPC data sub-actions (positions,
     *        animation tags)</li>
     *    <li>{@code 0x03/0x28} — WorldInfo (zone-resident object
     *        states; without NPCs Ceres-J emits the empty form)</li>
     *    <li>{@code 0x03/0x2e} — vehicle / drone state</li>
     *    <li>{@code 0x03/0x32} — subway/transit state</li>
     *    <li>{@code 0x03/0x07} — multipart fragments (CharInfo
     *        content varies per character; large multipart bursts
     *        depend on entity state)</li>
     *    <li>raw {@code 0x1f}, raw {@code 0x2d} — unwrapped
     *        gameplay broadcasts</li>
     *  </ul>
     *
     *  <p>Same skip pattern as {@link #isUnreplicableNpcBroadcast}.
     *  Verified 2026-05-09: HANNIBAL replay shows 21 "false" SPing
     *  divergences are alignment-drift from these unreplicable
     *  emissions interleaving with the genuine SPing pairs.
     */
    static boolean isUnreplicableEntityState(byte[] retailBytes,
                                             byte[] cerJBytes) {
        if (retailBytes.length == 0) return false;
        int b0 = retailBytes[0] & 0xFF;
        boolean retailIsTarget = false;
        if (b0 == 0x1f || b0 == 0x2d) {
            retailIsTarget = true;
        } else if (b0 == 0x03 && retailBytes.length >= 4) {
            int sub = retailBytes[3] & 0xFF;
            retailIsTarget = (sub == 0x1f || sub == 0x2d
                    || sub == 0x28 || sub == 0x2e
                    || sub == 0x32 || sub == 0x07);
        }
        if (!retailIsTarget) return false;
        // If Ceres-J also emitted the same sub-tag, pair them.
        if (cerJBytes.length == 0) return true;
        int c0 = cerJBytes[0] & 0xFF;
        // Strip 0x13 outer wrapper for comparison.
        byte[] cerJStripped = cerJBytes;
        if (c0 == 0x13 && cerJBytes.length >= 8) {
            cerJStripped = new byte[cerJBytes.length - 7];
            System.arraycopy(cerJBytes, 7, cerJStripped, 0,
                    cerJStripped.length);
            c0 = cerJStripped[0] & 0xFF;
        }
        // Direct match: both retail and Ceres-J emit same first byte.
        if (c0 == b0) {
            // For 0x03/* check sub-tag too.
            if (b0 == 0x03 && cerJStripped.length >= 4
                    && retailBytes.length >= 4
                    && (cerJStripped[3] & 0xFF)
                            == (retailBytes[3] & 0xFF)) {
                return false;
            }
            if (b0 != 0x03) return false;
        }
        return true;
    }

    static boolean isUnreplicableNpcBroadcast(byte[] retailBytes,
                                              byte[] cerJBytes) {
        if (retailBytes.length == 0) return false;
        boolean retailIsRaw1b = retailBytes.length == 19
                && (retailBytes[0] & 0xFF) == 0x1b;
        boolean retailIsRel03_1b = retailBytes.length >= 4
                && (retailBytes[0] & 0xFF) == 0x03
                && (retailBytes[3] & 0xFF) == 0x1b;
        if (!retailIsRaw1b && !retailIsRel03_1b) return false;
        // If Ceres-J also emitted a position broadcast at this step,
        // pair them — don't skip.
        if (cerJBytes.length == 0) return true;
        boolean cerJIsRaw1b = cerJBytes.length >= 1
                && (cerJBytes[0] & 0xFF) == 0x1b;
        boolean cerJIsRel03_1b = cerJBytes.length >= 4
                && (cerJBytes[0] & 0xFF) == 0x03
                && (cerJBytes[3] & 0xFF) == 0x1b;
        boolean cerJIsWrapped1b = cerJBytes.length >= 8
                && (cerJBytes[0] & 0xFF) == 0x13
                && (cerJBytes[7] & 0xFF) == 0x1b;
        boolean cerJIsWrappedRel03_1b = cerJBytes.length >= 11
                && (cerJBytes[0] & 0xFF) == 0x13
                && (cerJBytes[7] & 0xFF) == 0x03
                && (cerJBytes[10] & 0xFF) == 0x1b;
        return !(cerJIsRaw1b || cerJIsRel03_1b
                || cerJIsWrapped1b || cerJIsWrappedRel03_1b);
    }

    /** Whether {@code packet[offset]} is a per-session random byte
     *  that the harness must exempt from byte-equal comparison.
     *
     *  <p>Currently masks:
     *  <ul>
     *    <li>{@code 0x04} UDPAlive bytes 3..6 — {@code -sessionkey
     *    LE2 + port LE2}, both server-random.</li>
     *  </ul>
     *
     *  <p>Add more masks here as the harness encounters new
     *  session-derived layouts. Each mask should cite its
     *  reference packet doc (e.g. {@code udp_s2c_04.md}). */
    static boolean isSessionDerivedByte(byte[] packet, int offset) {
        // 0x04 UDPAlive: bytes 3..6 are -sessionkey LE2 + port LE2
        if (packet.length == 7
                && (packet[0] & 0xFF) == 0x04
                && offset >= 3 && offset <= 6) {
            return true;
        }
        // 0x0b SPing reply: ALL body bytes (1..8) are session-
        // derived for harness purposes:
        //   - bytes 1..4 = server_time LE32 (Timer.getIngametime() —
        //     wall-clock derived, can never match retail).
        //   - bytes 5..8 = "client_time echo" in Ceres-J's emit, but
        //     retail's matching field doesn't always equal the
        //     C→S CPing's client_time at the same harness step.
        //     Possible cause: retail emits server-initiated S→C
        //     0x0b pings (not just replies), so the alignment
        //     between C→S CPing and retail's S→C 0x0b queue entry
        //     drifts. Verified 2026-05-09 across HANNIBAL: 21/21
        //     SPing pairs had non-matching byte-5..8 even with
        //     server_time masked. Pin layout via SPingByteIdentityTest
        //     instead of trusting harness echo equality.
        if (packet.length == 9
                && (packet[0] & 0xFF) == 0x0b
                && offset >= 1 && offset <= 8) {
            return true;
        }
        // 0x03/0x23 zoneInfo variant — body[2] = wire offset 6 is
        // session/zone state. Verified 2026-05-09 across 4 retail
        // pcaps: HANNIBAL=0x10, NORMAN=0x01, DRSTONE3=0x84,
        // AUGUSTO=0x00. The 7-byte zoneInfo body is
        // {@code 20 00 ?? 00 00 00}; constants at body[0..1] and
        // [3..5], variable byte at [2]. Matches the
        // {@code [03 (sub-op 0x23) 20 00 ?? 00 00 00]} 7-byte form
        // only — longer 0x03/0x23 variants (sessionInfo,
        // postTransitionInfo) have different body shapes that don't
        // collide with this offset.
        if (packet.length == 10
                && (packet[0] & 0xFF) == 0x03
                && (packet[3] & 0xFF) == 0x23
                && (packet[4] & 0xFF) == 0x20
                && (packet[5] & 0xFF) == 0x00
                && offset == 6) {
            return true;
        }
        // 0x03/0x0d TimeSync — body bytes 1..4 (= wire offset
        // 4..7) are the server_time LE32, derived from
        // Timer.getIngametime() at emit-time. Test fixture's Timer
        // state ≠ retail's. Body bytes 9..12 (= wire offset 12..15)
        // are the trailing world/zone tag — bytes 9..10 are
        // CONSTANT (0xd5 0x0a) but bytes 11..12 vary per session
        // (0x0020 dominant, 0x0051 in DRSTONE3). Mask the variable
        // server_time AND byte 14 (the variable tag byte).
        // Verified 2026-05-09 across HANNIBAL/NORMAN/DRSTONE3/AUGUSTO.
        if (packet.length == 16
                && (packet[0] & 0xFF) == 0x03
                && (packet[3] & 0xFF) == 0x0d) {
            // server_time LE32
            if (offset >= 4 && offset <= 7) return true;
            // session-state tag low byte
            if (offset == 14) return true;
        }
        // 0x03/0x2c StartPos body[3..end] — all session/character
        // state (entity ID, position floats, character model,
        // texture indices). Verified 2026-05-09 against 4 retail
        // pcaps (HANNIBAL, NORMAN, AUGUSTO, DRSTONE3): the only
        // constant bytes are body[0..2] = 0x2c 0x01 0x01. The
        // rest depends on retail player state we cannot mirror in
        // the test fixture (spawn coords, MODEL_HEAD/TORSO/LEG,
        // texture indices, class). Layout pinning lives in
        // PositionUpdateByteIdentityTest; the harness checks the
        // packet is emitted at the right step with the right
        // wire size + constants and ignores the rest.
        if (packet.length >= 4
                && (packet[0] & 0xFF) == 0x03
                && (packet[3] & 0xFF) == 0x2c
                && offset >= 6) {
            return true;
        }
        // 0x03 reliable-channel sub-packet: bytes 1..2 are the
        // sequence counter (LE2). Each side's session counter is
        // independent — retail's may have advanced through dozens
        // of reliable packets before any given one fires; the
        // harness's only fired a few. Compare the sub-tag (byte
        // 3) and body (4..) but exempt the seq.
        if (packet.length >= 3
                && (packet[0] & 0xFF) == 0x03
                && (offset == 1 || offset == 2)) {
            return true;
        }
        return false;
    }

    private static String hexPreview(byte[] b, int n) {
        if (b == null) return "(null)";
        int m = Math.min(n, b.length);
        StringBuilder sb = new StringBuilder(m * 2);
        for (int i = 0; i < m; i++) {
            sb.append(String.format("%02x", b[i] & 0xFF));
        }
        if (m < b.length) sb.append("..");
        return sb.toString();
    }

    /**
     * Smoke test (always runs): just verify the pcap loader
     * doesn't crash on the corpus's smallest capture, and that
     * we got SOME records out of it. This is the minimum bar
     * for "the harness loads pcaps" — orthogonal to the
     * divergence diff above.
     */
    @Test
    public void pcapLoaderRunsOnRetailCorpus() throws Exception {
        File pcap = resolvePcap();
        assumeTrue("retail pcap not found at " + DEFAULT_PCAP,
                pcap != null);
        // Suppress per-packet stdout from the loader's
        // dependents (none today, but future-proof).
        PrintStream origOut = System.out;
        try {
            System.setOut(new PrintStream(
                    new ByteArrayOutputStream()));
            PcapReplay.Loaded loaded = PcapReplay.load(pcap);
            assertNotNull(loaded);
            assertTrue("pcap had zero records",
                    !loaded.records.isEmpty());
        } finally {
            System.setOut(origOut);
        }
    }
}
