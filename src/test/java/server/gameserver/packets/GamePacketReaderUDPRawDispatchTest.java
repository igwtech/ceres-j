package server.gameserver.packets;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.InetAddress;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.client_udp.HandshakeUDP;
import server.gameserver.packets.client_udp.ReliableAckSubPacket;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.GameServerEvent;

/**
 * Pin the size-based dispatch for raw outer-frame {@code 0x01}
 * UDP C→S packets in
 * {@link GamePacketReaderUDP#decode(DatagramPacket, Player)}.
 *
 * <p>Two distinct wire shapes share the {@code 0x01} opcode:
 * <ul>
 *   <li>≥10 B: UDP 3-way handshake — must dispatch to
 *       {@link HandshakeUDP}.</li>
 *   <li>3 B: post-handshake reliable-ACK request shaped
 *       {@code [0x01][seq LE2]} — must dispatch to
 *       {@link ReliableAckSubPacket}, NOT to
 *       {@link HandshakeUDP}.</li>
 * </ul>
 *
 * <p>Routing every {@code 0x01} to {@code HandshakeUDP} caused
 * the server to fire two spurious {@code UDPAlive} replies for
 * each client ACK request — observed at ~70 retail captures'
 * worth of post-handshake 0x01 traffic before the discriminator
 * was added.
 */
public class GamePacketReaderUDPRawDispatchTest {

    private static GameServerEvent decode(byte[] datagramBytes,
                                          Player pl) throws Exception {
        DatagramPacket dp = new DatagramPacket(
                datagramBytes, datagramBytes.length);
        dp.setAddress(InetAddress.getByName("127.0.0.1"));
        dp.setPort(51769);
        Method m = GamePacketReaderUDP.class.getDeclaredMethod(
                "decode", DatagramPacket.class, Player.class);
        m.setAccessible(true);
        return (GameServerEvent) m.invoke(null, dp, pl);
    }

    @Test
    public void tenByteHandshakeRoutesToHandshakeUDP()
            throws Exception {
        // Real handshake sample from catalog (RETAIL_VEHICLE_DRONE):
        // 01 d1 84 21 e2 21 e2 11 a0 00
        byte[] dgm = new byte[]{
                0x01, (byte)0xd1, (byte)0x84, 0x21,
                (byte)0xe2, 0x21, (byte)0xe2, 0x11,
                (byte)0xa0, 0x00};
        GameServerEvent ev = decode(dgm,
                PacketTestFixture.newPlayer());
        assertTrue("10B 0x01 must dispatch to HandshakeUDP, "
                + "got " + (ev == null ? "null"
                        : ev.getClass().getSimpleName()),
                ev instanceof HandshakeUDP);
    }

    @Test
    public void threeByteAckRoutesToReliableAck()
            throws Exception {
        // Post-handshake reliable-ACK request: 01 [seq LE2]. Must
        // NOT route to HandshakeUDP (would emit a spurious
        // UDPAlive for every ACK).
        byte[] dgm = new byte[]{0x01, 0x05, 0x00};
        GameServerEvent ev = decode(dgm,
                PacketTestFixture.newPlayer());
        assertTrue("3B 0x01 must dispatch to ReliableAckSubPacket, "
                + "got " + (ev == null ? "null"
                        : ev.getClass().getSimpleName()),
                ev instanceof ReliableAckSubPacket);

        // Sanity-check the body decoded the seq correctly.
        assertEquals(5, ((ReliableAckSubPacket) ev).ackSeq());
    }

    @Test
    public void ninthByteIsTheCutoff() throws Exception {
        // A 9-byte 0x01 — strictly below the 10B handshake
        // threshold — should NOT dispatch to HandshakeUDP, since
        // HandshakeUDP.execute() does skip(9)+read() and would
        // read past the buffer end.
        byte[] dgm = new byte[]{
                0x01, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00};
        GameServerEvent ev = decode(dgm,
                PacketTestFixture.newPlayer());
        assertFalse("9B 0x01 must NOT route to HandshakeUDP "
                + "(would read past buffer end)",
                ev instanceof HandshakeUDP);
    }

    @Test
    public void exactlyTenBytesIsHandshake() throws Exception {
        // The boundary: exactly 10 B routes to HandshakeUDP.
        byte[] dgm = new byte[]{
                0x01, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00};
        GameServerEvent ev = decode(dgm,
                PacketTestFixture.newPlayer());
        assertTrue("10B 0x01 must route to HandshakeUDP",
                ev instanceof HandshakeUDP);
    }

    @Test
    public void ackSeqIsLittleEndian() throws Exception {
        // 01 11 00 = ackSeq 0x0011 = 17 (LE16). This is the high-
        // sequence end of retail's typical 17-packet ACK burst
        // (010100..011100). Catches a regression that flips byte
        // order to big-endian.
        byte[] dgm = new byte[]{0x01, 0x11, 0x00};
        GameServerEvent ev = decode(dgm,
                PacketTestFixture.newPlayer());
        assertEquals(0x11, ((ReliableAckSubPacket) ev).ackSeq());
    }
}
