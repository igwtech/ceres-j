package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.CharInfo;
import server.gameserver.packets.server_udp.InfoResponse;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.PositionUpdate;
import server.testtools.CapturingUDPConnection;
import server.interfaces.ServerUDPPacket;

/**
 * Functional test for {@link RequestPositionUpdate} — the C→S
 * 0x2a handler. Verifies the retail emit sequence:
 *
 * <ol>
 *   <li>{@link PositionUpdate} — 0x03/0x2c StartPos</li>
 *   <li>{@link CharInfo} — multipart 0x03/0x2c or 0x03/0x07
 *       (size-based dispatch)</li>
 *   <li>{@link InfoResponse} zoneInfo — 0x03/0x23
 *       {@code [20 00 ?? 00 00 00]}</li>
 * </ol>
 *
 * <p>The InfoResponse emit at the END was added 2026-05-09 after
 * NORMAN pcap-replay step 9 showed retail emits zoneInfo here, not
 * in GetTimeSync. body[2] is session/zone state — varies per
 * capture (0x10/0x01/0x84/0x00).
 */
public class RequestPositionUpdateTest {

    /** Build a 5-byte RequestPositionUpdate body: {@code 0x2a [4 unknown bytes]}.
     *  Handler doesn't parse the body content (marked
     *  "content is still unknown" in source). */
    private static byte[] buildBody() {
        return new byte[]{0x2a, 0x00, 0x00, 0x00, 0x00};
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
    public void executeEmitsPositionThenCharInfoThenInfoResponse()
            throws Exception {
        Player pl = PacketTestFixture.newPlayerWithZone();
        CapturingUDPConnection cap = installCapturing(pl);

        new RequestPositionUpdate(buildBody()).execute(pl);

        java.util.List<ServerUDPPacket> emitted = cap.received();
        assertTrue("execute() must emit ≥3 UDP packets, got "
                + emitted.size(), emitted.size() >= 3);
        assertTrue("first emission must be PositionUpdate, got "
                + emitted.get(0).getClass().getSimpleName(),
                emitted.get(0) instanceof PositionUpdate);
        assertTrue("second emission must be CharInfo, got "
                + emitted.get(1).getClass().getSimpleName(),
                emitted.get(1) instanceof CharInfo);
        assertTrue("third emission must be InfoResponse zoneInfo, "
                + "got "
                + emitted.get(2).getClass().getSimpleName(),
                emitted.get(2) instanceof InfoResponse);
    }

    @Test
    public void emittedInfoResponseHasZoneInfoBody() throws Exception {
        // Verify the InfoResponse emitted is the 7B zoneInfo
        // variant, not sessionInfo or postTransitionInfo.
        Player pl = PacketTestFixture.newPlayerWithZone();
        CapturingUDPConnection cap = installCapturing(pl);

        new RequestPositionUpdate(buildBody()).execute(pl);

        java.util.List<byte[]> raws = cap.rawBytes();
        assertTrue("≥3 raw datagrams captured", raws.size() >= 3);
        // raws[2] is the InfoResponse — 17B total (7B 0x13 wrapper +
        // 1B 0x03 + 2B seq + 1B 0x23 + 6B body).
        byte[] ir = raws.get(2);
        assertEquals("zoneInfo total wire size = 17B",
                17, ir.length);
        assertEquals("0x13 outer", 0x13, ir[0] & 0xFF);
        assertEquals("0x03 reliable", 0x03, ir[7] & 0xFF);
        assertEquals("sub-op 0x23", 0x23, ir[10] & 0xFF);
        // Body at ir[11..16] = `20 00 10 00 00 00`.
        assertEquals(0x20, ir[11] & 0xFF);
        assertEquals(0x00, ir[12] & 0xFF);
        assertEquals(0x10, ir[13] & 0xFF);
        assertEquals(0x00, ir[14] & 0xFF);
        assertEquals(0x00, ir[15] & 0xFF);
        assertEquals(0x00, ir[16] & 0xFF);
    }
}
