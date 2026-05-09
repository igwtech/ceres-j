package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-level regression test for {@link PlayerDeath}.
 *
 * <p>Wire path: {@code 0x13 → 0x03 → 0x1f → 0x16}. The
 * {@code 0x1f} GamePackets wrapper carries [mapId LE2][sub-tag
 * 0x16][killer LE2][padding 2B] for an 8-byte inner payload
 * (after the {@code 0x03} reliable wrapper). Triggers the
 * client's death screen.
 */
public class PlayerDeathByteIdentityTest {

    private static byte[] datagramBytes(PlayerDeath pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP1303):
     *  {@code [0x13][counter LE2][counter+sk LE2][size LE2][0x03][seq LE2][0x1f][body...]}
     *  Body starts at offset 11. */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertTrue("datagram too short", datagram.length >= 11 + len);
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x1f", 0x1f, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void deathBodyEncodesMapIdSubTagAndKiller() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0x1234);

        byte[] body = extractInnerBody(
                datagramBytes(new PlayerDeath(pl, 0xCAFE)), 7);
        // [mapId LE2] [0x16] [killer LE2] [00] [00]
        assertEquals(0x34, body[0] & 0xFF);   // mapId LE lo
        assertEquals(0x12, body[1] & 0xFF);   // mapId LE hi
        assertEquals("Death sub-tag", 0x16, body[2] & 0xFF);
        assertEquals(0xFE, body[3] & 0xFF);   // killer LE lo
        assertEquals(0xCA, body[4] & 0xFF);   // killer LE hi
        assertEquals(0x00, body[5] & 0xFF);
        assertEquals(0x00, body[6] & 0xFF);
    }

    @Test
    public void noKillerConstructorPassesZero() {
        // The convenience constructor for environment / self kills.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0x1234);

        byte[] body = extractInnerBody(
                datagramBytes(new PlayerDeath(pl)), 7);
        // killer LE2 should be 00 00
        assertEquals(0x00, body[3] & 0xFF);
        assertEquals(0x00, body[4] & 0xFF);
    }

    @Test
    public void totalDatagramSizeIsEighteenBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x1f) + 7 (body) = 18 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(1);
        assertEquals(18, datagramBytes(new PlayerDeath(pl, 0)).length);
    }

    @Test
    public void mapIdEncodesLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0xABCD);

        byte[] body = extractInnerBody(
                datagramBytes(new PlayerDeath(pl, 0)), 7);
        assertEquals(0xCD, body[0] & 0xFF);
        assertEquals(0xAB, body[1] & 0xFF);
    }

    @Test
    public void killerIdEncodesLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0);

        byte[] body = extractInnerBody(
                datagramBytes(new PlayerDeath(pl, 0x9999)), 7);
        assertEquals(0x99, body[3] & 0xFF);
        assertEquals(0x99, body[4] & 0xFF);
    }
}
