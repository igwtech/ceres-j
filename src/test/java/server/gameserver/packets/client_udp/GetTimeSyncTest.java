package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.TimeSync;
import server.testtools.CapturingUDPConnection;
import server.interfaces.ServerUDPPacket;

/**
 * Functional test for {@link GetTimeSync} — the C→S 0x0c handler
 * that emits a {@link TimeSync} reply.
 *
 * <p>Note: a previous version of this handler also emitted a
 * {@code 0x03/0x23} InfoResponse zoneInfo, based on DRSTONE3 step
 * 10 evidence. Reverted 2026-05-09 — NORMAN replay showed retail
 * emits the zoneInfo at step 9 (RequestPositionUpdate), not step 10.
 * The DRSTONE3 step-10 zoneInfo is likely a buffered emission from
 * earlier (delayed by the 814B multipart 0x03/0x2c at step 9). The
 * zoneInfo emit moved to {@link RequestPositionUpdate}.
 */
public class GetTimeSyncTest {

    /** Build a 5-byte GetTimeSync body: {@code 0c [client_time LE32]}.
     *  Handler skips 1 byte then reads LE32. */
    private static byte[] buildBody(int clientTime) {
        byte[] b = new byte[5];
        b[0] = 0x0c;
        b[1] = (byte) (clientTime & 0xff);
        b[2] = (byte) ((clientTime >>  8) & 0xff);
        b[3] = (byte) ((clientTime >> 16) & 0xff);
        b[4] = (byte) ((clientTime >> 24) & 0xff);
        return b;
    }

    private static CapturingUDPConnection installCapturing(Player pl)
            throws Exception {
        java.net.InetAddress addr =
                java.net.InetAddress.getByName("127.0.0.1");
        CapturingUDPConnection cap =
                new CapturingUDPConnection(addr, 5000, pl);
        pl.setUdpConnection(cap);
        return cap;
    }

    @Test
    public void executeEmitsTimeSyncReply() throws Exception {
        // The first emission must be TimeSync (the reply to C→S
        // GetTimeSync). Subsequent emissions are zone-broadcasts
        // (sendPlayersinZone, sendnewPlayerinZone) which are 0 in
        // the test fixture (no zone-resident players).
        Player pl = PacketTestFixture.newPlayerWithZone();
        CapturingUDPConnection cap = installCapturing(pl);

        new GetTimeSync(buildBody(0xdeadbeef)).execute(pl);

        java.util.List<ServerUDPPacket> emitted = cap.received();
        assertFalse("execute() must emit at least one packet",
                emitted.isEmpty());
        assertTrue("first emission must be TimeSync, got "
                + emitted.get(0).getClass().getSimpleName(),
                emitted.get(0) instanceof TimeSync);
    }
}
