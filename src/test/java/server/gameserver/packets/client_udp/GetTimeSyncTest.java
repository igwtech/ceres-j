package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.InfoResponse;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.TimeSync;
import server.testtools.CapturingUDPConnection;
import server.interfaces.ServerUDPPacket;

/**
 * Functional test for {@link GetTimeSync} — the C→S 0x0c handler
 * that emits both the {@link TimeSync} reply AND a
 * {@link InfoResponse#zoneInfo(Player)} packet (the latter added
 * 2026-05-09 to match retail behavior verified in
 * HANNIBAL/NORMAN/DRSTONE3/AUGUSTO captures).
 *
 * <p>Pcap-replay harness against DRSTONE3 step 10 surfaced the
 * missing zoneInfo emit: retail emits the 7-byte zoneInfo
 * {@code [03 (sub-op 0x23) 20 00 ?? 00 00 00]} after every
 * TimeSync reply. body[2] is session/zone state — varies per
 * capture (0x10/0x01/0x84/0x00). The harness masks that one byte;
 * everything else must match.
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
    public void executeEmitsInfoResponseThenTimeSyncInRetailOrder()
            throws Exception {
        // Retail emit order for C→S GetTimeSync (verified DRSTONE3
        // step 10): InfoResponse 0x23 zoneInfo FIRST, TimeSync 0x0d
        // SECOND. The pcap-replay harness pairs S→C by index against
        // retail's queue, so reversing the order would manufacture
        // two divergences (0x23 vs 0x0d, 0x0d vs 0x23).
        Player pl = PacketTestFixture.newPlayerWithZone();
        CapturingUDPConnection cap = installCapturing(pl);

        new GetTimeSync(buildBody(0xdeadbeef)).execute(pl);

        java.util.List<ServerUDPPacket> emitted = cap.received();
        assertTrue("execute() must emit ≥2 UDP packets, got "
                + emitted.size(), emitted.size() >= 2);
        assertTrue("first emission must be InfoResponse zoneInfo, "
                + "got "
                + emitted.get(0).getClass().getSimpleName(),
                emitted.get(0) instanceof InfoResponse);
        assertTrue("second emission must be TimeSync, got "
                + emitted.get(1).getClass().getSimpleName(),
                emitted.get(1) instanceof TimeSync);
    }

    @Test
    public void emittedInfoResponseHasZoneInfoBody() throws Exception {
        // Verify the InfoResponse emitted is the zoneInfo variant
        // (body[0..1]=0x20 0x00, body[3..5]=00 00 00) — not
        // sessionInfo or postTransitionInfo. Pin against the
        // retail 7B form `[20 00 ?? 00 00 00]`.
        Player pl = PacketTestFixture.newPlayerWithZone();
        CapturingUDPConnection cap = installCapturing(pl);

        new GetTimeSync(buildBody(0)).execute(pl);

        java.util.List<byte[]> raws = cap.rawBytes();
        // emitted[0] is the InfoResponse (retail emit order, verified
        // DRSTONE3 step 10). Raws follow the same order:
        // raws[0] is InfoResponse's wire bytes, raws[1] is TimeSync's.
        assertTrue("≥2 raw datagrams captured", raws.size() >= 2);
        byte[] ir = raws.get(0);
        // 0x13 wrapper (7B) + 0x03 reliable + seq LE2 + sub-op 0x23
        // + 6B body = 17 bytes total.
        assertEquals("zoneInfo total wire size = 17B",
                17, ir.length);
        assertEquals("0x13 outer", 0x13, ir[0] & 0xFF);
        assertEquals("0x03 reliable", 0x03, ir[7] & 0xFF);
        assertEquals("sub-op 0x23", 0x23, ir[10] & 0xFF);
        // Body at ir[11..16] = `20 00 10 00 00 00` (factory's
        // placeholder; body[2] = 0x10 matches HANNIBAL retail).
        assertEquals("body[0]", 0x20, ir[11] & 0xFF);
        assertEquals("body[1]", 0x00, ir[12] & 0xFF);
        assertEquals("body[2] (placeholder, retail varies)",
                0x10, ir[13] & 0xFF);
        assertEquals("body[3]", 0x00, ir[14] & 0xFF);
        assertEquals("body[4]", 0x00, ir[15] & 0xFF);
        assertEquals("body[5]", 0x00, ir[16] & 0xFF);
    }
}
