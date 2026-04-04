package server.gameserver.packets.server_udp;

import static org.junit.Assert.assertEquals;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Pinned byte-layout tests for {@link UDPAlive} (the 0x04 keepalive/ack
 * packet the server sends after the client's handshake and between sync
 * batches).
 *
 * Layout (7 bytes, raw UDP — NOT wrapped in 0x13):
 * <pre>
 *   0x00  0x04                keepalive header
 *   0x01  byte    mapId
 *   0x02  byte    interfaceId
 *   0x03  short   -sessionKey  (LE; note the signed negation)
 *   0x05  short   clientPort   (LE)
 * </pre>
 *
 * The Ceres-J client accepts this 7-byte form unencrypted; the retail server
 * instead sends an 11-byte variant wrapped inside a stream cipher (observed
 * in retail pcap packets #59-62). Because the Ceres-J client tolerates both
 * formats during handshake, we keep the simpler 7-byte form but pin it here
 * to catch any accidental layout change.
 */
public class UDPAliveTest {

    @Test
    public void basicLayout() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0x0102);
        pl.setMapID(1);
        pl.getUdpConnection().setInterfaceId(4);

        DatagramPacket[] dps = new UDPAlive(pl).getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        assertEquals("unexpected length: " + PacketTestFixture.hex(b), 7, b.length);
        assertEquals(0x04, b[0] & 0xFF);     // keepalive header
        assertEquals(1,    b[1] & 0xFF);     // mapId
        assertEquals(4,    b[2] & 0xFF);     // interfaceId

        // -sessionKey = -0x0102 = 0xfefe in two's complement 16-bit
        assertEquals(0xfe, b[3] & 0xFF);
        assertEquals(0xfe, b[4] & 0xFF);

        // Client port is 5000 = 0x1388 (LE: 0x88 0x13)
        assertEquals(0x88, b[5] & 0xFF);
        assertEquals(0x13, b[6] & 0xFF);
    }

    @Test
    public void differentMapIdAndInterface() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(42);
        pl.getUdpConnection().setInterfaceId(0x7f);

        DatagramPacket[] dps = new UDPAlive(pl).getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        assertEquals(42,   b[1] & 0xFF);
        assertEquals(0x7f, b[2] & 0xFF);
    }
}
