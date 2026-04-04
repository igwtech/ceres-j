package server.networktools;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.net.DatagramPacket;

import org.junit.Test;

/**
 * Tests the little-endian encoding primitives used by every server-side UDP
 * packet builder. These primitives are the shared foundation of the whole
 * world-entry sequence, so any accidental byte-order flip will break every
 * packet on the wire.
 */
public class PacketBuilderUDPTest {

    @Test
    public void writeShortIsLittleEndian() {
        PacketBuilderUDP pb = new PacketBuilderUDP();
        pb.writeShort(0x1234);
        DatagramPacket[] dps = pb.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        assertArrayEquals(new byte[]{0x34, 0x12}, b);
    }

    @Test
    public void writeIntIsLittleEndian() {
        PacketBuilderUDP pb = new PacketBuilderUDP();
        pb.writeInt(0x01020304);
        DatagramPacket[] dps = pb.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        assertArrayEquals(new byte[]{0x04, 0x03, 0x02, 0x01}, b);
    }

    @Test
    public void writeFloatIsIeee754LittleEndian() {
        PacketBuilderUDP pb = new PacketBuilderUDP();
        pb.writeFloat(1.0f); // 0x3f800000
        DatagramPacket[] dps = pb.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        assertArrayEquals(new byte[]{0x00, 0x00, (byte) 0x80, 0x3f}, b);
    }

    @Test
    public void writeShortTruncatesToLowTwoBytes() {
        PacketBuilderUDP pb = new PacketBuilderUDP();
        pb.writeShort(0xdeadbeef); // should only write the low two bytes
        DatagramPacket[] dps = pb.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        assertEquals(2, b.length);
        assertEquals((byte) 0xef, b[0]);
        assertEquals((byte) 0xbe, b[1]);
    }

    @Test
    public void writeByteArrayAppendsRaw() {
        PacketBuilderUDP pb = new PacketBuilderUDP();
        pb.write(new byte[]{0x01, 0x02, 0x03});
        pb.writeShort(0xaabb);
        DatagramPacket[] dps = pb.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03, (byte) 0xbb, (byte) 0xaa}, b);
    }
}
