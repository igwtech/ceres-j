package server.gameserver.packets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.List;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.ServerReliableAck;
import server.interfaces.ServerUDPPacket;
import server.networktools.PacketBuilderUDP13;
import server.testtools.CapturingUDPConnection;

/**
 * Functional test for the dual-ack emission path in
 * {@link GamePacketReaderUDP#readPacket}: when the server receives
 * a reliable C→S sub-packet, it must emit BOTH retail-observed
 * ack forms:
 *
 * <ul>
 *   <li>0x13/0x01 form — 13 retail samples / 3 captures
 *       (see {@code udp_s2c_01.md}).</li>
 *   <li>0x03/0x09 form — 35 retail samples / 11 captures
 *       (see {@code udp_s2c_03_09.md} ServerReliableAck).</li>
 * </ul>
 *
 * <p>Inner body of both forms = {@code [ack_seq LE16]} where
 * ack_seq tracks the seq of the just-received client-reliable.
 *
 * <p>This test pins the wire-correct dual emission so a future
 * refactor doesn't accidentally drop one form.
 */
public class GamePacketReaderUDPDualAckTest {

    /**
     * Build a 0x13 burst datagram carrying one reliable sub-packet
     * with the given seq and inner body.
     */
    private static DatagramPacket buildReliableBurst(int seq,
                                                     byte[] innerBody)
            throws Exception {
        // Sub-packet = [0x03][seq LE2][innerBody]
        // Total wire = [0x13][counter LE2][counter+sk LE2]
        //              [sub_len LE2][sub_packet]
        int subLen = 3 + innerBody.length;
        byte[] wire = new byte[7 + subLen];
        wire[0] = 0x13;
        // counter LE2
        wire[1] = 0x00; wire[2] = 0x00;
        // counter + sk LE2
        wire[3] = 0x00; wire[4] = 0x00;
        // sub_len LE2
        wire[5] = (byte) (subLen & 0xFF);
        wire[6] = (byte) ((subLen >> 8) & 0xFF);
        // sub-packet body
        wire[7] = 0x03;
        wire[8] = (byte) (seq & 0xFF);
        wire[9] = (byte) ((seq >> 8) & 0xFF);
        System.arraycopy(innerBody, 0, wire, 10, innerBody.length);

        DatagramPacket dp = new DatagramPacket(wire, wire.length);
        dp.setAddress(InetAddress.getByName("127.0.0.1"));
        dp.setPort(51769);
        return dp;
    }

    @Test
    public void readPacketEmitsBothAckFormsForReliableSubPacket()
            throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0x4242);
        CapturingUDPConnection cap = new CapturingUDPConnection(
                InetAddress.getByName("127.0.0.1"), 5000, pl);
        pl.setUdpConnection(cap);

        // Build a reliable C→S 0x13 burst with seq=0x1234, inner
        // = [0x24][01 00] (matches udp_c2s_03_24 session-start
        // hello body).
        byte[] inner = new byte[]{0x24, 0x01, 0x00};
        DatagramPacket dp = buildReliableBurst(0x1234, inner);

        GamePacketReaderUDP.readPacket(dp, pl);

        // Both ack forms must have been emitted exactly once.
        List<ServerUDPPacket> sent = cap.received();
        assertEquals("expected 2 acks (0x01 + 0x03/0x09), got "
                + sent.size(), 2, sent.size());

        // First emitted: 0x13/0x01 form via PacketBuilderUDP13.
        // Wire: [13][counter LE2][counter+sk LE2][03 00][01][seq_lo][seq_hi]
        // Total = 7 (outer) + 3 (sub) = 10 bytes
        DatagramPacket ack01 = sent.get(0).getDatagramPackets()[0];
        assertEquals(PacketBuilderUDP13.class,
                sent.get(0).getClass());
        byte[] wire01 = new byte[ack01.getLength()];
        System.arraycopy(ack01.getData(), 0, wire01, 0, wire01.length);
        assertEquals("0x01 ack must be 10 bytes total", 10,
                wire01.length);
        assertEquals(0x13, wire01[0] & 0xFF);
        // sub_len at offset 5..6 = 3
        assertEquals(3, wire01[5] & 0xFF);
        // sub-packet [01][seq_lo][seq_hi] at offset 7..9
        assertEquals(0x01, wire01[7] & 0xFF);
        assertEquals(0x34, wire01[8] & 0xFF);
        assertEquals(0x12, wire01[9] & 0xFF);

        // Second emitted: 0x03/0x09 ServerReliableAck (13 bytes).
        ServerUDPPacket second = sent.get(1);
        assertTrue("second ack must be ServerReliableAck, got "
                + second.getClass().getSimpleName(),
                second instanceof ServerReliableAck);
        DatagramPacket ack09 = second.getDatagramPackets()[0];
        byte[] wire09 = new byte[ack09.getLength()];
        System.arraycopy(ack09.getData(), 0, wire09, 0, wire09.length);
        // 7 (outer) + 6 (inner [03][seq LE2][09][ack LE2]) = 13B
        assertEquals(13, wire09.length);
        // sub-tag 0x09 at offset 10
        assertEquals(0x09, wire09[10] & 0xFF);
        // ack_seq LE16 at offset 11..12 = 0x1234
        assertEquals(0x34, wire09[11] & 0xFF);
        assertEquals(0x12, wire09[12] & 0xFF);
    }

    @Test
    public void readPacketEmitsNoAckForNonReliableSubPacket()
            throws Exception {
        // 0x02 outer (simplified-reliable) is NOT acked in retail.
        // Verify both ack paths are skipped for non-0x03 subs.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        CapturingUDPConnection cap = new CapturingUDPConnection(
                InetAddress.getByName("127.0.0.1"), 5000, pl);
        pl.setUdpConnection(cap);

        // Build a 0x13 burst with a 0x02-prefixed sub-packet
        // (simplified-reliable: [0x02][seq LE2][1f][body]).
        // Layout: [13][00 00][00 00][07 00][02 00 00 1f 00 00 3d]
        byte[] wire = new byte[]{
                0x13, 0x00, 0x00, 0x00, 0x00,
                0x07, 0x00,
                0x02, 0x00, 0x00, 0x1f, 0x00, 0x00, 0x3d
        };
        DatagramPacket dp = new DatagramPacket(wire, wire.length);
        dp.setAddress(InetAddress.getByName("127.0.0.1"));
        dp.setPort(51769);

        GamePacketReaderUDP.readPacket(dp, pl);

        // 0x02 outer must NOT trigger any ack emission (the server
        // only acks the 0x03 reliable channel).
        assertEquals("0x02 sub-packet must NOT trigger ack emission",
                0, cap.received().size());
    }
}
