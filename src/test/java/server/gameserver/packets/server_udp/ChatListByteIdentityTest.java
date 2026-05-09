package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.ChatList;

/**
 * Byte-identical regression test for {@link ChatList}
 * (UDP S→C reliable {@code 0x03/0x33}). All 80 cataloged retail
 * samples are byte-identical with the body {@code ff 00} — pure
 * constant — so this is a straight pin.
 */
public class ChatListByteIdentityTest {

    private static byte[] datagramBytes(ChatList pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    private static byte[] extractInnerBody(byte[] datagram, int len) {
        // Frame layout (PacketBuilderUDP1303):
        //   [0x13][counter LE2][counter+sk LE2][size LE2]
        //   [0x03][seq LE2][0x33][body...]
        // Body starts at offset 11.
        assertTrue("datagram too short", datagram.length >= 11 + len);
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x33", 0x33, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void emitsTwoByteFFThenZero() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] datagram = datagramBytes(new ChatList(pl));
        byte[] body = extractInnerBody(datagram, 2);
        // Catalog: 80 retail samples, body `ff 00` × 80
        assertArrayEquals(new byte[] { (byte) 0xff, 0x00 }, body);
    }

    @Test
    public void totalDatagramSizeIsThirteenBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x33) + 2 (body) = 13 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(13, datagramBytes(new ChatList(pl)).length);
    }

    @Test
    public void multipleInstancesProduceIdenticalInnerBody() {
        // Even with the random session-counter, the inner body
        // must be invariant.
        Player pl1 = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        Player pl2 = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body1 = extractInnerBody(datagramBytes(new ChatList(pl1)), 2);
        byte[] body2 = extractInnerBody(datagramBytes(new ChatList(pl2)), 2);
        assertArrayEquals(body1, body2);
    }
}
