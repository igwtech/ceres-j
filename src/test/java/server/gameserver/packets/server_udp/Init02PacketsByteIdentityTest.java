package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.net.DatagramPacket;

import org.junit.Before;
import org.junit.Test;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1302;

/**
 * Byte-identity tests for the small {@code 0x02} simplified-
 * reliable initialization packets sent during the world-entry
 * burst:
 *
 * <ul>
 *   <li>{@link InitSoullight02} — {@code 0x02/0x1f} carrying
 *       Soullight as IEEE-754 float</li>
 *   <li>{@link InitInfoResponse02} — {@code 0x02/0x23} session
 *       info ({@code 0f 00 03 00 01 00})</li>
 * </ul>
 *
 * <p>The {@code 0x02} wrapper is fire-and-forget initialization
 * (not ACKed by the client reliable layer). These tests pin the
 * exact wire bytes against retail evidence so a future cleanup
 * of {@link PacketBuilderUDP1302}'s sequence-counter handling
 * can't regress the body.
 */
public class Init02PacketsByteIdentityTest {

    @Before
    public void resetSeqCounter() throws Exception {
        // PacketBuilderUDP1302 has a static `seq` counter that
        // increments per instance. Reset to 1 so tests are
        // order-independent on the seq field (we don't pin it
        // anyway, but resetting keeps surrounding tests stable).
        Field f = PacketBuilderUDP1302.class.getDeclaredField("seq");
        f.setAccessible(true);
        f.setInt(null, 1);
    }

    private static byte[] datagramBytes(java.net.DatagramPacket dp) {
        byte[] b = new byte[dp.getLength()];
        System.arraycopy(dp.getData(), 0, b, 0, b.length);
        return b;
    }

    private static byte[] firstDatagram(InitSoullight02 pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        return datagramBytes(dps[0]);
    }

    private static byte[] firstDatagram(InitInfoResponse02 pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        return datagramBytes(dps[0]);
    }

    @Test
    public void initSoullight02BodyMatchesRetailFloat() {
        // Frame layout (PacketBuilderUDP1302):
        //   [0x13][counter LE2][counter+sk LE2][size LE2]
        //   [0x02][seq LE2][sub-opcode 0x1f][body...]
        //   Body starts at offset 10.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] datagram = firstDatagram(new InitSoullight02(pl));

        assertEquals("outer 0x13",       0x13, datagram[0] & 0xFF);
        assertEquals("0x02 wrapper",     0x02, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x1f",  0x1f, datagram[10] & 0xFF);

        // Body after 0x1f sub-opcode:
        //   01 00 25 1f [float LE = 100.0f → 00 00 c8 42]
        // Total 8 bytes.
        byte[] expected = {
                0x01, 0x00, 0x25, 0x1f,
                0x00, 0x00, (byte) 0xc8, 0x42
        };
        for (int i = 0; i < expected.length; i++) {
            assertEquals("body byte " + i,
                    expected[i], datagram[11 + i]);
        }
    }

    @Test
    public void initSoullight02FloatLittleEndianIs100() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] datagram = firstDatagram(new InitSoullight02(pl));

        // Float bytes at offset 15..18 (after 0x1f sub-op + 4
        // constant tag bytes).
        int rawIntLE = (datagram[15] & 0xFF)
                | ((datagram[16] & 0xFF) << 8)
                | ((datagram[17] & 0xFF) << 16)
                | ((datagram[18] & 0xFF) << 24);
        float v = Float.intBitsToFloat(rawIntLE);
        assertEquals("Soullight = 100.0f", 100.0f, v, 0.0f);
    }

    @Test
    public void initInfoResponse02BodyMatchesRetail() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] datagram = firstDatagram(new InitInfoResponse02(pl));

        assertEquals("outer 0x13",       0x13, datagram[0] & 0xFF);
        assertEquals("0x02 wrapper",     0x02, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x23",  0x23, datagram[10] & 0xFF);

        // Body after 0x23 sub-opcode: `0f 00 03 00 01 00` (6B)
        byte[] expected = {
                0x0f, 0x00, 0x03, 0x00, 0x01, 0x00
        };
        for (int i = 0; i < expected.length; i++) {
            assertEquals("body byte " + i,
                    expected[i], datagram[11 + i]);
        }
    }

    @Test
    public void initSoullight02TotalSizeIsNineteenBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x02) + 2 (seq) + 1 (0x1f) + 8 (body) = 19 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        DatagramPacket[] dps = new InitSoullight02(pl).getDatagramPackets();
        assertEquals(19, dps[0].getLength());
    }

    @Test
    public void initInfoResponse02TotalSizeIsSeventeenBytes() {
        // 1 + 2 + 2 + 2 + 1 + 2 + 1 + 6 = 17 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        DatagramPacket[] dps = new InitInfoResponse02(pl).getDatagramPackets();
        assertEquals(17, dps[0].getLength());
    }
}
