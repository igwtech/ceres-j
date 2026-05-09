package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identical regression test for {@link RemoveWorldItem}
 * (UDP S→C reliable {@code 0x03/0x26}). Pins the 4-byte body
 * (entity_id LE32) against the 3 cataloged retail samples so a
 * future broadcast wiring change cannot regress the wire format.
 *
 * <p>Catalog evidence:
 *
 * <pre>
 *   sample #1  ce 03 00 00   entity 0x000003ce  (974)
 *   sample #2  b8 03 00 00   entity 0x000003b8  (952)
 *   sample #3  d5 03 00 00   entity 0x000003d5  (981)
 * </pre>
 */
public class RemoveWorldItemTest {

    private static byte[] datagramBytes(RemoveWorldItem pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP1303):
     *  {@code [0x13][counter LE2][counter+sk LE2][size LE2][0x03][seq LE2][0x26][body...]}
     *  — body starts at offset 11. */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertTrue("datagram too short, got " + datagram.length,
                datagram.length >= 11 + len);
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x26", 0x26, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void writesFourByteEntityIdLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        RemoveWorldItem pkt = new RemoveWorldItem(pl, 0x000003ce);

        byte[] body = extractInnerBody(datagramBytes(pkt), 4);
        byte[] expected = { (byte) 0xce, 0x03, 0x00, 0x00 };
        assertArrayEquals("body must match retail catalog sample #1 "
                + "(ce030000)", expected, body);
    }

    @Test
    public void allRetailSamplesByteEqual() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        int[][] samples = {
                {0x000003ce, 0xce},   // sample #1
                {0x000003b8, 0xb8},   // sample #2
                {0x000003d5, 0xd5},   // sample #3
        };
        for (int[] s : samples) {
            byte[] body = extractInnerBody(datagramBytes(
                    new RemoveWorldItem(pl, s[0])), 4);
            byte[] expected = { (byte) s[1], 0x03, 0x00, 0x00 };
            assertArrayEquals("entity 0x" + Integer.toHexString(s[0]),
                    expected, body);
        }
    }

    @Test
    public void largeEntityIdEncodesAcrossAllFourBytes() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        RemoveWorldItem pkt = new RemoveWorldItem(pl, 0x12345678);

        byte[] body = extractInnerBody(datagramBytes(pkt), 4);
        assertEquals(0x78, body[0] & 0xFF);
        assertEquals(0x56, body[1] & 0xFF);
        assertEquals(0x34, body[2] & 0xFF);
        assertEquals(0x12, body[3] & 0xFF);
    }

    @Test
    public void totalDatagramSizeIsFifteenBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x26) + 4 (body) = 15 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] datagram = datagramBytes(new RemoveWorldItem(pl, 1));
        assertEquals(15, datagram.length);
    }
}
