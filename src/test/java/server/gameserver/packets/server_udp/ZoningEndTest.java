package server.gameserver.packets.server_udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Tests for {@link ZoningEnd} — verifies the exact byte layout of the
 * reliable 0x13 -> 0x03 -> 0x08 terminator packet.
 *
 * Reference layout (total 14 bytes with 2-byte LE sub-packet length):
 * <pre>
 *   0x00  0x13                UDP gamedata header
 *   0x01  short    counter    outer counter (LE)
 *   0x03  short    ckey       counter + sessionkey
 *   0x05  short    size       2-byte LE inner payload length = total - 7
 *   0x07  byte     0x03       reliable wrapper sub-type
 *   0x08  short    seqCounter reliable sequence counter
 *   0x0a  byte     0x08       ZoningEnd sub-type
 *   0x0b  short    mapId      player's map id
 *   0x0d  byte     0x00       status flag
 * </pre>
 */
public class ZoningEndTest {

    @Test
    public void serialisesExpectedByteLayout() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0x1234);
        pl.setMapID(7);

        ZoningEnd pkt = new ZoningEnd(pl);
        DatagramPacket[] dps = pkt.getDatagramPackets();
        assertNotNull(dps);
        assertEquals("ZoningEnd must fit in a single UDP datagram", 1, dps.length);

        byte[] bytes = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, bytes, 0, bytes.length);

        // Total length: 7 (outer header: type + 2*counter + 2-byte size)
        // + 3 (reliable 0x03 + 16bit counter) + 1 (0x08 subtype) + 2 (mapId)
        // + 1 (status) = 14 bytes.
        assertEquals("unexpected packet length: " + PacketTestFixture.hex(bytes),
                14, bytes.length);

        // Offset 0x00: gamedata header.
        assertEquals(0x13, bytes[0] & 0xFF);

        // Offset 0x05-6: size of inner payload = total - 7 = 7 (LE).
        assertEquals(7, bytes[5] & 0xFF);
        assertEquals(0, bytes[6] & 0xFF);

        // Offset 0x07: reliable wrapper type 0x03.
        assertEquals(0x03, bytes[7] & 0xFF);

        // Offset 0x0a: ZoningEnd sub-type 0x08.
        assertEquals(0x08, bytes[10] & 0xFF);

        // Offset 0x0b..0x0c: mapId little-endian = 7.
        assertEquals(7, bytes[11] & 0xFF);
        assertEquals(0, bytes[12] & 0xFF);

        // Offset 0x0d: status byte.
        assertEquals(0x00, bytes[13] & 0xFF);
    }

    @Test
    public void byteExactWithKnownSessionKey() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0x1234);
        pl.setMapID(7);

        DatagramPacket[] dps = new ZoningEnd(pl).getDatagramPackets();
        byte[] actual = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, actual, 0, actual.length);

        byte[] expected = new byte[]{
                0x13,                   // [0] gamedata header
                0x01, 0x00,             // [1..2] outer counter (LE = 1)
                0x35, 0x12,             // [3..4] outer counter+sessionkey (LE = 0x1235)
                0x07, 0x00,             // [5..6] 2-byte LE inner size = 7
                0x03,                   // [7] reliable wrapper
                0x01, 0x00,             // [8..9] inner counter (LE = 1)
                0x08,                   // [10] ZoningEnd sub-type
                0x07, 0x00,             // [11..12] mapId (LE = 7)
                0x00                    // [13] status
        };

        assertEquals("byte-exact mismatch, got: " + PacketTestFixture.hex(actual),
                PacketTestFixture.hex(expected), PacketTestFixture.hex(actual));
    }

    @Test
    public void mapIdIsEncodedLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0x01b4); // 436

        DatagramPacket[] dps = new ZoningEnd(pl).getDatagramPackets();
        byte[] bytes = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, bytes, 0, bytes.length);

        assertEquals(0xb4, bytes[11] & 0xFF);
        assertEquals(0x01, bytes[12] & 0xFF);
    }

    @Test
    public void headerBytesMatchUdp13Reliable() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0x7fff);
        pl.setMapID(1);

        DatagramPacket[] dps = new ZoningEnd(pl).getDatagramPackets();
        byte[] bytes = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, bytes, 0, bytes.length);

        // Outer header is 0x13, inner wrapper type is 0x03.
        assertEquals("gamedata header", 0x13, bytes[0] & 0xFF);
        assertEquals("reliable wrapper", 0x03, bytes[7] & 0xFF);
        // Final sub-packet type must be ZoningEnd
        assertEquals("ZoningEnd sub-type", 0x08, bytes[10] & 0xFF);
        // Trailing status byte
        assertTrue("status byte present", bytes.length >= 14);
        assertEquals("exact total length", 14, bytes.length);
    }
}
