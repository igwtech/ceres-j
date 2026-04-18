package server.networktools;

import static org.junit.Assert.*;
import java.net.DatagramPacket;
import org.junit.Test;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Verify that PacketBuilderUDP13.newSubPacket() correctly frames
 * multiple sub-packets inside a single 0x13 datagram.
 *
 * Each sub-packet should be: [2-byte LE length][data bytes].
 * The sub-packet data should start with the first byte written after
 * the constructor (sub-packet 1) or after newSubPacket() (sub-packets 2+).
 */
public class CompoundSubPacketTest {

    @Test
    public void twoSubPacketsFramedCorrectly() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        PacketBuilderUDP13 pb = new PacketBuilderUDP13(pl);
        // Sub-packet 1: type 0x1b + 2 data bytes
        pb.write(0x1b);
        pb.write(0xAA);
        pb.write(0xBB);

        // Seal sub-packet 1, start sub-packet 2
        pb.newSubPacket();

        // Sub-packet 2: type 0x03 + 2 data bytes
        pb.write(0x03);
        pb.write(0xCC);
        pb.write(0xDD);

        DatagramPacket[] dps = pb.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        // Outer header: 0x13 + 4 counter bytes = 5 bytes
        assertEquals("outer header", 0x13, b[0] & 0xFF);

        // Sub-packet 1 length at offset 5-6 (2-byte LE) = 3 (0x1b + AA + BB)
        int len1 = (b[5] & 0xFF) | ((b[6] & 0xFF) << 8);
        assertEquals("sub-packet 1 length", 3, len1);

        // Sub-packet 1 data starts at offset 7
        assertEquals("sub-packet 1 byte 0 (type)", 0x1b, b[7] & 0xFF);
        assertEquals("sub-packet 1 byte 1", 0xAA, b[8] & 0xFF);
        assertEquals("sub-packet 1 byte 2", 0xBB, b[9] & 0xFF);

        // Sub-packet 2 length at offset 10-11 (2-byte LE) = 3 (0x03 + CC + DD)
        int len2 = (b[10] & 0xFF) | ((b[11] & 0xFF) << 8);
        assertEquals("sub-packet 2 length", 3, len2);

        // Sub-packet 2 data starts at offset 12
        assertEquals("sub-packet 2 byte 0 (type)", 0x03, b[12] & 0xFF);
        assertEquals("sub-packet 2 byte 1", 0xCC, b[13] & 0xFF);
        assertEquals("sub-packet 2 byte 2", 0xDD, b[14] & 0xFF);

        // Total: 5 (header) + 2 (len1) + 3 (data1) + 2 (len2) + 3 (data2) = 15
        assertEquals("total packet length", 15, b.length);
    }

    @Test
    public void threeSubPacketsFramedCorrectly() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        PacketBuilderUDP13 pb = new PacketBuilderUDP13(pl);
        // Sub-packet 1: 0x1b
        pb.write(0x1b);
        pb.write(0x01);

        pb.newSubPacket();
        // Sub-packet 2: 0x2d
        pb.write(0x2d);
        pb.write(0x02);

        pb.newSubPacket();
        // Sub-packet 3: 0x28
        pb.write(0x28);
        pb.write(0x03);

        DatagramPacket[] dps = pb.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        // Header = 5, then 3 × (2 len + 2 data) = 5 + 12 = 17
        assertEquals("total length", 17, b.length);

        // Sub 1
        assertEquals("s1 len", 2, (b[5] & 0xFF) | ((b[6] & 0xFF) << 8));
        assertEquals("s1 type", 0x1b, b[7] & 0xFF);
        assertEquals("s1 data", 0x01, b[8] & 0xFF);

        // Sub 2
        assertEquals("s2 len", 2, (b[9] & 0xFF) | ((b[10] & 0xFF) << 8));
        assertEquals("s2 type", 0x2d, b[11] & 0xFF);
        assertEquals("s2 data", 0x02, b[12] & 0xFF);

        // Sub 3
        assertEquals("s3 len", 2, (b[13] & 0xFF) | ((b[14] & 0xFF) << 8));
        assertEquals("s3 type", 0x28, b[15] & 0xFF);
        assertEquals("s3 data", 0x03, b[16] & 0xFF);
    }
}
