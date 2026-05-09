package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link AttributeUpdate3c} — the
 * universal attribute-update packet (UDP S→C raw {@code 0x3c}).
 *
 * <p>Catalog evidence: 345 retail samples (all 12 bytes), with
 * sample bodies showing the variant byte at offset 1 dominantly
 * {@code 0x01} (5 of 6 sampled), tag at offset 3 spanning
 * {@code 0x01/0x04/0x05/0x06/0x07/0x09}, and two trailing
 * {@code LE32} values:
 *
 * <pre>
 *   #1  3c 04 00 06  53 2d 00 00  ca 15 00 00     (variant 0x04)
 *   #2  3c 01 00 05  ef 15 00 00  88 6e 00 00     (variant 0x01)
 *   #3  3c 01 00 09  80 25 00 00  44 52 00 00     (variant 0x01)
 * </pre>
 *
 * <p>Ceres-J emits the common {@code 0x01} variant. The
 * occasional retail {@code 0x04} variant is not yet decoded
 * (open question — likely correlates with a specific
 * transaction class).
 */
public class AttributeUpdate3cByteIdentityTest {

    private static byte[] datagramBytes(AttributeUpdate3c pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP13): {@code [0x13][counter LE2]
     *  [counter+sk LE2][size LE2][body 12B]}. Body starts at offset 7. */
    private static byte[] extractInnerBody(byte[] datagram) {
        assertEquals("outer 0x13", 0x13, datagram[0] & 0xFF);
        byte[] body = new byte[12];
        System.arraycopy(datagram, 7, body, 0, 12);
        return body;
    }

    @Test
    public void retailSample2ByteEqual() {
        // Sample #2: 3c 01 00 05 ef 15 00 00 88 6e 00 00
        // tag=0x05, valueA=0x000015ef, valueB=0x00006e88
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new AttributeUpdate3c(pl, 0x05, 0x000015ef, 0x00006e88)));
        byte[] expected = {
                0x3c, 0x01, 0x00, 0x05,
                (byte) 0xef, 0x15, 0x00, 0x00,
                (byte) 0x88, 0x6e, 0x00, 0x00
        };
        assertArrayEquals(expected, body);
    }

    @Test
    public void retailSample3ByteEqual() {
        // Sample #3: 3c 01 00 09 80 25 00 00 44 52 00 00
        // tag=0x09 (HP/damage), valueA=0x00002580, valueB=0x00005244
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new AttributeUpdate3c(pl,
                        AttributeUpdate3c.TAG_HP_DAMAGE,
                        0x00002580, 0x00005244)));
        byte[] expected = {
                0x3c, 0x01, 0x00, 0x09,
                (byte) 0x80, 0x25, 0x00, 0x00,
                0x44, 0x52, 0x00, 0x00
        };
        assertArrayEquals(expected, body);
    }

    @Test
    public void cashFactorySetsBothValuesToWallet() {
        // The cash() factory sets valueA = valueB = newWallet.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                AttributeUpdate3c.cash(pl, 0x12345678)));

        // tag must be TAG_CASH
        assertEquals(AttributeUpdate3c.TAG_CASH, body[3] & 0xFF);
        // valueA at [4..7]
        assertEquals(0x78, body[4] & 0xFF);
        assertEquals(0x56, body[5] & 0xFF);
        assertEquals(0x34, body[6] & 0xFF);
        assertEquals(0x12, body[7] & 0xFF);
        // valueB at [8..11] — same value
        assertEquals(0x78, body[ 8] & 0xFF);
        assertEquals(0x56, body[ 9] & 0xFF);
        assertEquals(0x34, body[10] & 0xFF);
        assertEquals(0x12, body[11] & 0xFF);
    }

    @Test
    public void variantByteIsAlwaysZeroOne() {
        // Pin the dominant variant byte. Retail also has occasional
        // 0x04 variants but Ceres-J doesn't emit those today; if
        // anyone ever changes the constructor to accept a variant
        // parameter this test will catch it.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        for (int tag : new int[]{
                AttributeUpdate3c.TAG_UNKNOWN_01,
                AttributeUpdate3c.TAG_UNKNOWN_02,
                AttributeUpdate3c.TAG_CASH,
                AttributeUpdate3c.TAG_HP_DAMAGE}) {
            byte[] body = extractInnerBody(datagramBytes(
                    new AttributeUpdate3c(pl, tag, 0, 0)));
            assertEquals("variant byte must be 0x01 for tag " + tag,
                    0x01, body[1] & 0xFF);
            assertEquals("padding byte must be 0x00",
                    0x00, body[2] & 0xFF);
        }
    }

    @Test
    public void totalDatagramSizeIsNineteenBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   12 (body) = 19 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(19, datagramBytes(
                new AttributeUpdate3c(pl, 0x01, 0, 0)).length);
    }

    @Test
    public void bothLE32ValuesEncodeIndependently() {
        // Different valueA / valueB to catch a swap regression.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new AttributeUpdate3c(pl, 0x01,
                        0xAABBCCDD, 0x11223344)));
        // valueA at [4..7]
        assertEquals(0xDD, body[4] & 0xFF);
        assertEquals(0xCC, body[5] & 0xFF);
        assertEquals(0xBB, body[6] & 0xFF);
        assertEquals(0xAA, body[7] & 0xFF);
        // valueB at [8..11]
        assertEquals(0x44, body[ 8] & 0xFF);
        assertEquals(0x33, body[ 9] & 0xFF);
        assertEquals(0x22, body[10] & 0xFF);
        assertEquals(0x11, body[11] & 0xFF);
    }
}
