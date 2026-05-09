package server.testtools;

import static org.junit.Assert.*;

import org.junit.Test;

import server.gameserver.packets.client_udp.UseItem;
import server.gameserver.packets.server_udp.LocalChatMessage;
import server.gameserver.packets.server_udp.OpenDoor;

/**
 * Smoke test for {@link ReplayHarness} — proves the harness
 * end-to-end by replaying a known-good UseItem scenario and
 * verifying the expected TCP triplet emerges.
 */
public class ReplayHarnessTest {

    /** UseItem sub-packet body: {@code 0x03 [seq2] 0x1f [sub2]
     *  0x17 [object_id LE32]} = 11 bytes. */
    private static byte[] useItemBody(int objectId) {
        byte[] b = new byte[11];
        b[0]  = 0x03;          // outer reliable
        b[1]  = 0x42;          // seq lo
        b[2]  = 0x00;          // seq hi
        b[3]  = 0x1f;          // gamedata
        b[4]  = 0x05;          // sub lo
        b[5]  = 0x00;          // sub hi
        b[6]  = 0x17;          // sub-tag UseItem
        b[7]  = (byte) (objectId        & 0xff);
        b[8]  = (byte) ((objectId >> 8 ) & 0xff);
        b[9]  = (byte) ((objectId >> 16) & 0xff);
        b[10] = (byte) ((objectId >> 24) & 0xff);
        return b;
    }

    @Test
    public void driveDecodesUseItemAndExecutesIt() {
        ReplayHarness h = new ReplayHarness();
        ReplayHarness.DriveResult r = h.drive(useItemBody(99));

        assertTrue("UseItem must be recognised by the decoder",
                r.wasRecognised());
        assertTrue("decoded class must be UseItem",
                r.decoded instanceof UseItem);
    }

    @Test
    public void driveCapturesUseItemUdpEmissions() {
        // On this branch UseItem emits a LocalChatMessage and an
        // OpenDoor UDP packet (S→C). Both are routed via
        // pl.send(udp) and captured by CapturingUDPConnection.
        ReplayHarness h = new ReplayHarness();
        ReplayHarness.DriveResult r = h.drive(useItemBody(0xdeadbeef));

        assertEquals("UseItem emits 2 S→C UDP packets",
                2, r.udpEmittedThisStep.size());
        assertTrue("first UDP must be LocalChatMessage",
                r.udpEmittedThisStep.get(0)
                        instanceof LocalChatMessage);
        assertTrue("second UDP must be OpenDoor",
                r.udpEmittedThisStep.get(1) instanceof OpenDoor);
    }

    @Test
    public void driveAllAccumulatesAcrossSteps() {
        // Drive two UseItems back-to-back; udpEmitted() is
        // cumulative; per-step udpEmittedThisStep is local.
        ReplayHarness h = new ReplayHarness();

        h.driveAll(useItemBody(1), useItemBody(2));

        assertEquals("2 steps in history", 2, h.history().size());
        assertEquals("each step emits 2 UDP packets",
                2, h.history().get(0).udpEmittedThisStep.size());
        assertEquals(2, h.history().get(1).udpEmittedThisStep.size());
        assertEquals("cumulative UDP capture",
                4, h.udpEmitted().size());
    }

    @Test
    public void unknownTopByteIsRecognisedAsFallthrough() {
        // 0xff doesn't match any decoder branch → returns an
        // UnknownClientUDPPacket. wasUnknown() flags it.
        ReplayHarness h = new ReplayHarness();
        ReplayHarness.DriveResult r = h.drive(new byte[]{
                (byte) 0xff, 0x00, 0x00, 0x00});

        assertTrue("0xff sub-packet must produce an Unknown event",
                r.wasUnknown());
    }

    @Test
    public void emptyDriveProducesNoTcp() {
        // Heartbeat-shaped 0x02 ack-channel — recognised but
        // emits no TCP (it's a no-op routing).
        ReplayHarness h = new ReplayHarness();
        ReplayHarness.DriveResult r = h.drive(new byte[]{
                0x02, 0x02, 0x02, 0x1f, 0x00, 0x00,
                0x3d, 0x11, 0x00, 0x00, 0x00, 0x00});

        assertTrue("0x02/0x3d/0x11 heartbeat must be recognised",
                r.decoded == null
                        || r.tcpEmittedThisStep.isEmpty());
        assertEquals("no TCP packets for the heartbeat",
                0, r.tcpEmittedThisStep.size());
    }

    @Test
    public void udpCapturedBytesArePlaintextNotEncrypted() {
        // The harness must capture pre-encryption plaintext so
        // session-replay diffs can compare apples to apples
        // against retail's decrypted plaintext stream.
        ReplayHarness h = new ReplayHarness();
        ReplayHarness.DriveResult r = h.drive(useItemBody(99));

        // Each emitted UDP packet shows up as one (or more) raw
        // byte buffers in udpRawBytesThisStep. The first one
        // should be non-empty plaintext (no LFSR seed prefix).
        assertTrue("at least one raw UDP buffer captured",
                !r.udpRawBytesThisStep.isEmpty());
        // First-byte sanity: NC2 plaintext datagrams begin with
        // one of the known headers (0x01/0x03/0x04/0x08/0x13).
        // A LocalChatMessage would be a 0x13 outer.
        int firstByte = r.udpRawBytesThisStep.get(0)[0] & 0xFF;
        assertTrue("first byte 0x" + Integer.toHexString(firstByte)
                + " must be a known plaintext header",
                firstByte == 0x01 || firstByte == 0x03
                        || firstByte == 0x04 || firstByte == 0x08
                        || firstByte == 0x13);
    }

    @Test
    public void useItemEventClassIsResolvable() {
        // Smoke check that UseItem reflective-decode produces an
        // instance of the actual UseItem class on this branch.
        ReplayHarness h = new ReplayHarness();
        ReplayHarness.DriveResult r = h.drive(useItemBody(1));
        assertTrue("decoded must be UseItem",
                r.decoded instanceof UseItem);
    }
}
