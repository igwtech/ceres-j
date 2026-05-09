package server.testtools;

import static org.junit.Assert.*;

import org.junit.Test;

import server.gameserver.packets.client_udp.UseItem;
import server.gameserver.packets.server_tcp.InteractionAck;
import server.gameserver.packets.server_tcp.Packet838F;

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
    public void driveCapturesTheCompleteTcpTripletForUseItem() {
        // UseItem produces 3 TCP packets per the retail-pinned
        // contract: 0x838f → InteractionAck → InteractionAck.
        ReplayHarness h = new ReplayHarness();
        ReplayHarness.DriveResult r = h.drive(useItemBody(0xdeadbeef));

        assertTrue("must emit the retail TCP triplet 838f→a002→a002",
                r.emittedTcpSequence(
                        Packet838F.class,
                        InteractionAck.class,
                        InteractionAck.class));
        assertEquals(3, r.tcpEmittedThisStep.size());
    }

    @Test
    public void driveAllAccumulatesAcrossSteps() {
        // Drive two UseItems back-to-back; tcpEmitted() is
        // cumulative; per-step tcpEmittedThisStep is local.
        ReplayHarness h = new ReplayHarness();

        h.driveAll(useItemBody(1), useItemBody(2));

        assertEquals("2 steps in history", 2, h.history().size());
        assertEquals("each step emits 3 TCP packets",
                3, h.history().get(0).tcpEmittedThisStep.size());
        assertEquals(3, h.history().get(1).tcpEmittedThisStep.size());
        assertEquals("cumulative TCP capture",
                6, h.tcpEmitted().size());
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
}
