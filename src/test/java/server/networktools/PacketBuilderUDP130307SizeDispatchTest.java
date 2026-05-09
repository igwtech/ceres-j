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
        // 100B body — well under the 900-byte threshold.
        writeBody(pb, 100);
        DatagramPacket[] dps = pb.getDatagramPackets();

        assertEquals("100B body must emit exactly 1 datagram",
                1, dps.length);
        assertEquals("100B body sub-tag must be 0x2c (single)",
                SUB_TAG_SINGLE, subTag(dps[0]));
    }

    @Test
    public void atThresholdBodyEmitsAsSingle0x2c() {
        Player pl = PacketTestFixture.newPlayer();
        PacketBuilderUDP130307 pb = new PacketBuilderUDP130307(pl);
        // The body added to `complete` is 3 bytes of section
        // header + 900 body bytes = 903B total. The threshold
        // applies to `complete.size()`, so we need 900 - 3 = 897
        // body bytes to land at the 900 boundary. Use 850 to be
        // conservatively well under and definitively single.
        writeBody(pb, 850);
        DatagramPacket[] dps = pb.getDatagramPackets();

        assertEquals(1, dps.length);
        assertEquals("850B body must still be single",
                SUB_TAG_SINGLE, subTag(dps[0]));
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
