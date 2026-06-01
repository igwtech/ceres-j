package server.networktools;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Pins the size-based dispatch contract of
 * {@link PacketBuilderUDP130307}: bodies ≤ 900 bytes are emitted
 * as a single {@code 0x03/0x2c} reliable packet; larger bodies
 * are split into {@code 0x03/0x07} multipart fragments.
 *
 * <p>Verified 2026-05-09 in the SYNCHRONIZING-overlay
 * investigation — Bug #3 from
 * {@code docs/protocol/_overlay_investigation.md} ("CharInfo
 * always multiparts") was a false alarm; this dispatcher is
 * already correct. This test prevents a regression that
 * would re-introduce the unconditional multipart path.
 *
 * <p>Wire format reminder (relative to the 0x13 frame):
 * <pre>
 * single   : [0x03][seq LE2][0x2c] [02 01 &lt;sections...&gt;]
 * multipart: [0x03][seq LE2][0x07] [frag_idx LE2][total_frags LE4]
 *            [discriminator=0x01][total_size LE4=chain-key=0x00 high]
 *            [chunk bytes...]
 * </pre>
 */
public class PacketBuilderUDP130307SizeDispatchTest {

    /** Position of the reliable-type byte inside the on-wire
     *  datagram. The packet layout is:
     *  <pre>
     *  [0]      0x13 outer-frame marker
     *  [1..2]   UDP-channel counter (LE2)
     *  [3..4]   counter + udp13 session key (LE2)
     *  [5..6]   sub-packet length (LE2)
     *  [7]      0x03 reliable wrapper
     *  [8..9]   reliable sequence (LE2)
     *  [10]     reliable sub-tag — 0x2c or 0x07
     *  </pre> */
    private static final int RELIABLE_TYPE_OFFSET = 10;

    private static final int SUB_TAG_SINGLE = 0x2c;
    private static final int SUB_TAG_MULTIPART = 0x07;

    private static int subTag(DatagramPacket dp) {
        return dp.getData()[dp.getOffset() + RELIABLE_TYPE_OFFSET] & 0xff;
    }

    /** Fill a builder with a section of {@code n} known bytes. */
    private static void writeBody(PacketBuilderUDP130307 pb, int n) {
        pb.newSection(1);
        for (int i = 0; i < n; i++) {
            pb.write(0xab);
        }
    }

    @Test
    public void smallBodyEmitsAsSingle0x2c() {
        Player pl = PacketTestFixture.newPlayer();
        PacketBuilderUDP130307 pb = new PacketBuilderUDP130307(pl);
        // 40B body — under the 60-byte single-packet threshold, so the
        // resulting single 0x2c datagram stays ≤82B (the receive ceiling).
        writeBody(pb, 40);
        DatagramPacket[] dps = pb.getDatagramPackets();

        assertEquals("40B body must emit exactly 1 datagram",
                1, dps.length);
        assertEquals("40B body sub-tag must be 0x2c (single)",
                SUB_TAG_SINGLE, subTag(dps[0]));
        // The single datagram must itself fit under the 82B receive ceiling.
        assertTrue("single 0x2c datagram must be ≤82B (" + dps[0].getLength()
                + "B)", dps[0].getLength() <= 82);
    }

    @Test
    public void aboveThresholdBodyEmitsAsMultipart0x07() {
        Player pl = PacketTestFixture.newPlayer();
        PacketBuilderUDP130307 pb = new PacketBuilderUDP130307(pl);
        // 850B body — well over the 60-byte threshold. The previous 900-byte
        // threshold sent this as one oversized 0x2c datagram the client
        // silently dropped (>82B receive ceiling); it must now multipart so
        // every fragment stays deliverable.
        writeBody(pb, 850);
        DatagramPacket[] dps = pb.getDatagramPackets();

        assertTrue("850B body must multipart, got " + dps.length,
                dps.length >= 2);
        for (DatagramPacket dp : dps) {
            assertEquals("every fragment carries sub-tag 0x07",
                    SUB_TAG_MULTIPART, subTag(dp));
            assertTrue("fragment datagram ≤82B (" + dp.getLength() + "B)",
                    dp.getLength() <= 82);
        }
    }

    @Test
    public void largeBodyEmitsAsMultipart0x07() {
        Player pl = PacketTestFixture.newPlayer();
        PacketBuilderUDP130307 pb = new PacketBuilderUDP130307(pl);
        // 2000B body — well over the 900-byte threshold; should
        // produce multiple 0x07 multipart fragments.
        writeBody(pb, 2000);
        DatagramPacket[] dps = pb.getDatagramPackets();

        assertTrue("2000B body must emit ≥2 fragments, got " + dps.length,
                dps.length >= 2);
        for (DatagramPacket dp : dps) {
            assertEquals("every multipart fragment must carry sub-tag 0x07",
                    SUB_TAG_MULTIPART, subTag(dp));
        }
    }

    @Test
    public void multipartFragmentsCarrySequentialIndices() {
        // Fragment-index LE2 lives at offset 11/12 — one byte
        // after the reliable sub-tag byte at offset 10.
        Player pl = PacketTestFixture.newPlayer();
        PacketBuilderUDP130307 pb = new PacketBuilderUDP130307(pl);
        writeBody(pb, 1500);
        DatagramPacket[] dps = pb.getDatagramPackets();

        assertTrue(dps.length >= 2);
        for (int i = 0; i < dps.length; i++) {
            byte[] data = dps[i].getData();
            int off = dps[i].getOffset();
            int fragIdxLo = data[off + RELIABLE_TYPE_OFFSET + 1] & 0xff;
            int fragIdxHi = data[off + RELIABLE_TYPE_OFFSET + 2] & 0xff;
            int fragIdx = fragIdxLo | (fragIdxHi << 8);
            assertEquals("fragment[" + i + "].idx must equal "
                    + i, i, fragIdx);
        }
    }

    /**
     * Fragmentation shape pin (revised 2026-06-01).
     *
     * <p><b>Why not retail's 1000-byte chunk:</b> retail splits Krafteo's
     * 2201-byte CharInfo into 3 fragments of ~1000B each, and the retail
     * client reassembles them fine on retail's network. On Ceres's
     * Docker-bridge ↔ Wine-client path, however, a ~1018-byte S→C reliable
     * datagram is <em>consistently dropped</em> before it reaches the
     * client's UDP recv hook — proven live (Frida trace
     * {@code pos_ceres_20260601_012154}): the client received reliable
     * seqs 2..46 but NEVER seq=1 (the 1018B CharInfo fragment), NAK'd it 3×,
     * the server re-sent the same 1018B datagram, it was dropped again, and
     * the client gave up. With no CharInfo the F1 skill screen renders
     * garbage (bogus "Ceres Wisdom" slot + negative rank) and the toolbelt
     * greys out (the client's skill-requirement check runs on uninitialised
     * CHARSYS data). Every received datagram in that trace was ≤ 86 bytes.
     *
     * <p><b>Fix:</b> the client on this transport NEVER receives an S→C
     * plaintext datagram larger than 82 bytes (measured across live traces),
     * so {@code FRAGMENT_CHUNK_BYTES} is 48 — each fragment datagram is
     * ≈70 B plaintext (22 B framing + 48 chunk), comfortably ≤82. A
     * 2201-byte CharInfo then splits into 46 fragments (45×48 + 41). This
     * test pins that count + the per-fragment payload sizes + the ≤82 B
     * datagram ceiling so the chunk can't silently inflate back to an
     * un-deliverable value.
     */
    @Test
    public void chunkSizeProducesDeliverableFragmentCount() {
        Player pl = PacketTestFixture.newPlayer();
        PacketBuilderUDP130307 pb = new PacketBuilderUDP130307(pl);
        // Section header is 3 bytes (id + LE2 len); 2198 body bytes makes
        // the `complete` buffer 2201 — the exact retail CharInfo size.
        writeBody(pb, 2198);
        DatagramPacket[] dps = pb.getDatagramPackets();

        // ceil(2201 / 48) = 46 fragments; last = 2201 - 45*48 = 41.
        assertEquals("2201B CharInfo must split into 46 deliverable fragments",
                46, dps.length);
        for (DatagramPacket dp : dps) {
            assertEquals(SUB_TAG_MULTIPART, subTag(dp));
        }

        // Per-fragment payload = datagram length minus the fixed framing:
        //   [13][ctr2][ctr+sk2][subLen2] = 7 outer
        //   [03][seq2][07]               = 4 reliable+op
        //   [frag_idx2][total_frags4][disc1][total_size4] = 11 header
        // => 22 bytes of framing before the chunk.
        final int FRAMING = 7 + 4 + 11;
        for (int i = 0; i < dps.length; i++) {
            int chunk = dps[i].getLength() - FRAMING;
            int expected = (i < 45) ? 48 : 41; // 2201 - 45*48 = 41
            assertEquals("fragment[" + i + "] payload size",
                    expected, chunk);
            // Each fragment datagram must stay ≤82B plaintext — the hard
            // receive ceiling measured on the bridge ↔ Wine transport.
            assertTrue("fragment[" + i + "] datagram must be ≤82B ("
                    + dps[i].getLength() + "B)", dps[i].getLength() <= 82);
        }
    }

    @Test
    public void singleSubTagIsCanonicalNotAccidentalDefault() {
        // Catch a regression that might silently switch the
        // single-mode constant to anything other than 0x2c.
        // A pure literal pin so a future "let's reuse 0x2d for
        // both" change fails loudly here.
        Player pl = PacketTestFixture.newPlayer();
        PacketBuilderUDP130307 pb = new PacketBuilderUDP130307(pl);
        writeBody(pb, 50);
        DatagramPacket[] dps = pb.getDatagramPackets();
        assertEquals(0x2c, subTag(dps[0]));
    }
}
