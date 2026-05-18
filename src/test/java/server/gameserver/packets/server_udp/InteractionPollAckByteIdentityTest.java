package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link InteractionPollAck} — the raw
 * (unreliable) reply to the C→S interaction poll
 * ({@code 1f &lt;id&gt; 01 55}).
 *
 * <p>Ground truth: {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}.
 * Every C→S {@code 1f &lt;id&gt; 01 55} is answered by the server with
 * the raw 8-byte sub-packet {@code 1f &lt;id&gt; 01 56 00 00 00 00}.
 * Observed pairs (1:1 in the capture): {@code 1f2b0155 →
 * 1f2b015600000000}, {@code 1fd50155 → 1fd5015600000000},
 * {@code 1f0a0155 → 1f0a015600000000}, {@code 1f0b0155 →
 * 1f0b015600000000}.
 */
public class InteractionPollAckByteIdentityTest {

    private static byte[] datagramBytes(InteractionPollAck pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP13): {@code [0x13][counter LE2]
     *  [counter+sk LE2][size LE2][body 8B]}. Body starts at offset 7. */
    private static byte[] extractInnerBody(byte[] datagram) {
        assertEquals("outer 0x13", 0x13, datagram[0] & 0xFF);
        byte[] body = new byte[8];
        System.arraycopy(datagram, 7, body, 0, 8);
        return body;
    }

    @Test
    public void retailPair2bByteEqual() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new InteractionPollAck(pl, 0x2b)));
        byte[] expected = {
                0x1f, 0x2b, 0x01, 0x56, 0x00, 0x00, 0x00, 0x00
        };
        assertArrayEquals(expected, body);
    }

    @Test
    public void retailPairsAllIdsByteEqual() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        int[] ids = { 0xd5, 0x0a, 0x0b };
        for (int id : ids) {
            byte[] body = extractInnerBody(datagramBytes(
                    new InteractionPollAck(pl, id)));
            byte[] expected = {
                    0x1f, (byte) id, 0x01, 0x56,
                    0x00, 0x00, 0x00, 0x00
            };
            assertArrayEquals("id=0x" + Integer.toHexString(id),
                    expected, body);
        }
    }

    @Test
    public void totalDatagramSizeIsFifteenBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   8 (body) = 15 bytes (== retail wire=15B for these acks).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(15, datagramBytes(
                new InteractionPollAck(pl, 0x2b)).length);
    }

    @Test
    public void idByteIsMaskedToOneByte() {
        // Defensive: a wide int id must not bleed past one byte.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new InteractionPollAck(pl, 0x123456d5)));
        assertEquals(0xd5, body[1] & 0xFF);
    }
}
