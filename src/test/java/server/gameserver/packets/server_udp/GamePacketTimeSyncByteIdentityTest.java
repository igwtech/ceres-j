package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link GamePacketTimeSync} — the periodic
 * server→client GamePackets-TimeSync heartbeat that gates the
 * "SYNCHRONIZING INTO CITY ZONE" overlay clearing in the modern
 * NCE 2.5 client.
 *
 * <p>Wire format (5-byte body after the {@code 0x1f} sub-tag):
 *
 * <pre>
 *   [0]      0x01           opcode variant
 *   [1]      0x00           reserved
 *   [2..3]   0x25 0x23      constant inner-opcode tag
 *   [4]      mapId & 0xFF   per-zone byte
 * </pre>
 *
 * <p>Retail sends 37–65 of these per session (~1 Hz). Without
 * them the world-alive watchdog eventually fires and the client
 * aborts the session.
 */
public class GamePacketTimeSyncByteIdentityTest {

    private static byte[] datagramBytes(GamePacketTimeSync pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Body starts at offset 11 (PacketBuilderUDP1303 frame). */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x1f", 0x1f, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void heartbeatBodyMatchesRetailPattern() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0x40);   // ACC1_CHAR1's logged-in zone byte

        byte[] body = extractInnerBody(datagramBytes(
                new GamePacketTimeSync(pl)), 5);
        // Catalog: retail emits `01 00 25 23 [mapId&0xFF]`
        byte[] expected = {
                0x01, 0x00, 0x25, 0x23, 0x40
        };
        assertArrayEquals("body must match retail heartbeat "
                + "pattern (01 00 25 23 [mapId])", expected, body);
    }

    @Test
    public void zoneByteIsLowByteOfMapId() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0x4040);   // high byte must be masked away

        byte[] body = extractInnerBody(datagramBytes(
                new GamePacketTimeSync(pl)), 5);
        assertEquals("mapId & 0xFF",
                0x40, body[4] & 0xFF);
    }

    @Test
    public void constantBytesArePinnedAcrossInstances() {
        // The first 4 body bytes must be invariant: opcode
        // variant, reserved, constant tag.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(1);
        byte[] body = extractInnerBody(datagramBytes(
                new GamePacketTimeSync(pl)), 5);
        assertEquals(0x01, body[0] & 0xFF);
        assertEquals(0x00, body[1] & 0xFF);
        assertEquals(0x25, body[2] & 0xFF);
        assertEquals(0x23, body[3] & 0xFF);
    }

    @Test
    public void totalDatagramSizeIsSixteenBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x1f) + 5 (body) = 16 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(1);
        assertEquals(16, datagramBytes(
                new GamePacketTimeSync(pl)).length);
    }
}
