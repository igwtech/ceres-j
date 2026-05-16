package server.gameserver.packets;

import static org.junit.Assert.assertEquals;

import java.net.DatagramPacket;
import java.net.InetAddress;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.testtools.CapturingUDPConnection;

/**
 * Pins the reliable-channel behaviour of
 * {@link GamePacketReaderUDP#readPacket}.
 *
 * <p><strong>Retail ground truth (RETAIL_PLAZA_CROSSZONE,
 * byte-diffed vs a localhost Ceres-J capture 2026-05-16):</strong>
 * the server emits {@code S→C 0x01} <em>zero</em> times in an
 * entire session. NC2's reliable layer is fire-and-forget: the
 * sender never waits for a positive per-packet ack. Recovery is
 * receiver-driven only — a side that detects a gap sends a
 * {@code 0x01} retransmit-request and the peer resends via
 * {@code 0x02} (retail counts: C→S 0x01 = 11, S→C 0x02 = 12,
 * S→C 0x01 = 0, C→S 0x02 = 0).
 *
 * <p>The handler therefore must NOT emit anything in reply to a
 * client reliable. The earlier "ack every 0x03" code made the
 * client read {@code S→C 0x01[seq]} as "resend seq N" and
 * retransmit Zoning1 forever instead of advancing to Zoning2 —
 * the plaza_p1 → plaza_p3 hang.
 */
public class GamePacketReaderUDPDualAckTest {

    private static DatagramPacket buildReliableBurst(int seq,
                                                     byte[] innerBody)
            throws Exception {
        int subLen = 3 + innerBody.length;
        byte[] wire = new byte[7 + subLen];
        wire[0] = 0x13;
        wire[1] = 0x00; wire[2] = 0x00;
        wire[3] = 0x00; wire[4] = 0x00;
        wire[5] = (byte) (subLen & 0xFF);
        wire[6] = (byte) ((subLen >> 8) & 0xFF);
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
    public void reliableSubPacketGetsNoServerAck() throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0x4242);
        CapturingUDPConnection cap = new CapturingUDPConnection(
                InetAddress.getByName("127.0.0.1"), 5000, pl);
        pl.setUdpConnection(cap);

        byte[] inner = new byte[]{0x24, 0x01, 0x00};
        DatagramPacket dp = buildReliableBurst(0x1234, inner);

        GamePacketReaderUDP.readPacket(dp, pl);

        // Retail emits S→C 0x01 zero times: the server must send
        // NOTHING in reply to a client 0x03 reliable.
        assertEquals("server must not ack client reliables "
                + "(retail emits S→C 0x01 zero times)",
                0, cap.received().size());
    }

    @Test
    public void nonReliableSubPacketGetsNoServerAck()
            throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        CapturingUDPConnection cap = new CapturingUDPConnection(
                InetAddress.getByName("127.0.0.1"), 5000, pl);
        pl.setUdpConnection(cap);

        // 0x13 burst with a 0x02-prefixed sub-packet.
        byte[] wire = new byte[]{
                0x13, 0x00, 0x00, 0x00, 0x00,
                0x07, 0x00,
                0x02, 0x00, 0x00, 0x1f, 0x00, 0x00, 0x3d
        };
        DatagramPacket dp = new DatagramPacket(wire, wire.length);
        dp.setAddress(InetAddress.getByName("127.0.0.1"));
        dp.setPort(51769);

        GamePacketReaderUDP.readPacket(dp, pl);

        assertEquals("0x02 sub-packet must not trigger emission",
                0, cap.received().size());
    }
}
