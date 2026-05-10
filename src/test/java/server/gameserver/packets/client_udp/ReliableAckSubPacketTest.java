package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.net.InetAddress;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.ChatList;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.testtools.CapturingUDPConnection;
import server.interfaces.ServerUDPPacket;

/**
 * Functional test for {@link ReliableAckSubPacket}'s retransmit
 * responder (task #136 / #151 P1 overlay-clear blocker).
 *
 * <p>Wire flow under test:
 * <ol>
 *   <li>Server emits a reliable {@code 0x03/seq=N/...} packet —
 *       PacketBuilderUDP1303 records it into the ring.</li>
 *   <li>Client sends C→S {@code 0x01 [seq=N LE2]} ack-request.</li>
 *   <li>Handler looks up seq=N in the ring → emits
 *       {@code 0x02 [counter LE2] [original sub-op + body]}.</li>
 * </ol>
 *
 * <p>Tests use {@link ChatList} (0x03/0x33) as a representative
 * reliable. ChatList body is the constant 3-byte sequence
 * {@code 33 ff 00} (sub-op + 2-byte payload).
 */
public class ReliableAckSubPacketTest {

    private static CapturingUDPConnection installCapturing(Player pl)
            throws Exception {
        InetAddress addr = InetAddress.getByName("127.0.0.1");
        CapturingUDPConnection cap =
                new CapturingUDPConnection(addr, 5000, pl);
        pl.setUdpConnection(cap);
        return cap;
    }

    @Test
    public void executeEmitsRetransmitForKnownSeq() throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        CapturingUDPConnection cap = installCapturing(pl);

        // Emit a reliable so seq=1 is recorded into the ring.
        ChatList orig = new ChatList(pl);
        // Force finalize to populate the ring.
        orig.getDatagramPackets();
        // The ChatList datagram itself isn't sent through cap (we
        // just finalized it; pl.send wasn't called). But the ring
        // record happens on getDatagramPackets() → seq=1 stored.
        assertNotNull("seq=1 must be in ring",
                pl.getUdpConnection().reliableRing().get(1));
        assertEquals("nothing emitted yet (we didn't pl.send)",
                0, cap.received().size());

        // Now drive a C→S 0x01 [seq=1 LE2] ack-request.
        ReliableAckSubPacket ack = new ReliableAckSubPacket(
                new byte[]{0x01, 0x01, 0x00});
        ack.execute(pl);

        // Handler must have emitted exactly one 0x02-wrapped
        // retransmit.
        assertEquals("retransmit emit count",
                1, cap.received().size());
        ServerUDPPacket emitted = cap.received().get(0);
        DatagramPacket[] dps = emitted.getDatagramPackets();
        assertEquals(1, dps.length);
        byte[] wire = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, wire, 0, wire.length);

        // Wire format: [0x13][counter LE2][counter+sk LE2]
        // [size LE2][0x02][seq LE2][sub-op = 0x33][body = ff 00]
        // = 7B outer + 3B inner-prefix + 3B body = 13 bytes.
        assertEquals("retransmit wire size", 13, wire.length);
        assertEquals("0x13 outer", 0x13, wire[0] & 0xFF);
        assertEquals("0x02 simplified-reliable", 0x02,
                wire[7] & 0xFF);
        // Body at wire[10..12] = 33 ff 00 (the original ChatList
        // body, retransmitted unchanged).
        assertEquals(0x33, wire[10] & 0xFF);
        assertEquals(0xFF, wire[11] & 0xFF);
        assertEquals(0x00, wire[12] & 0xFF);
    }

    @Test
    public void executeIsNoOpForUnknownSeq() throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        CapturingUDPConnection cap = installCapturing(pl);

        // No reliable emitted → ring is empty → ack for seq=99
        // must be silently ignored.
        ReliableAckSubPacket ack = new ReliableAckSubPacket(
                new byte[]{0x01, 0x63, 0x00});
        ack.execute(pl);

        assertEquals("nothing emitted for unknown seq",
                0, cap.received().size());
    }

    @Test
    public void ackSeqDecodesLittleEndian() {
        ReliableAckSubPacket ack = new ReliableAckSubPacket(
                new byte[]{0x01, 0x44, 0x5a});
        // LE16 = 0x5a44 = 23108
        assertEquals(0x5a44, ack.ackSeq());
    }

    @Test
    public void ackSeqReturnsNegativeOnTooShortBody() {
        // 1-byte body → can't decode LE16.
        ReliableAckSubPacket ack = new ReliableAckSubPacket(
                new byte[]{0x01});
        assertEquals(-1, ack.ackSeq());
    }

    @Test
    public void retransmitPreservesOriginalBodyBytes() throws Exception {
        // After multiple reliables, retransmit of seq=2 must be
        // byte-for-byte the original ChatList body — not a
        // re-built one (which would have different bytes if any
        // session-derived field changed).
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        CapturingUDPConnection cap = installCapturing(pl);

        new ChatList(pl).getDatagramPackets();   // seq=1
        new ChatList(pl).getDatagramPackets();   // seq=2
        new ChatList(pl).getDatagramPackets();   // seq=3

        byte[] originalBody = pl.getUdpConnection()
                .reliableRing().get(2);
        assertNotNull(originalBody);

        // Request retransmit of seq=2
        new ReliableAckSubPacket(new byte[]{0x01, 0x02, 0x00})
                .execute(pl);

        assertEquals(1, cap.received().size());
        DatagramPacket dp = cap.received().get(0)
                .getDatagramPackets()[0];
        // Inner body (post 0x13/0x02/seq wrapper = 10B) must
        // equal original body.
        byte[] retransmittedBody = new byte[dp.getLength() - 10];
        System.arraycopy(dp.getData(), 10, retransmittedBody, 0,
                retransmittedBody.length);
        assertArrayEquals("retransmit body = original ChatList body "
                + "byte-for-byte (no re-emit drift)",
                originalBody, retransmittedBody);
    }
}
