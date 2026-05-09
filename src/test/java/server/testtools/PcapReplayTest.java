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
                        && isSpareUDPAlive(
                                s2c.get(s2cIdx).bytes,
                                emitted)) {
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
        // 0x0b SPing reply: bytes 1..4 are server_time LE4
        // (Timer.getIngametime() — wall-clock derived, can never
        // match retail's value byte-for-byte). Bytes 5..8 are
        // client_time echo, which DO match.
        if (packet.length == 9
                && (packet[0] & 0xFF) == 0x0b
                && offset >= 1 && offset <= 4) {
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
