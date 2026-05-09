package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for {@link Packet838F} — interaction-commit ack
 * (TCP S→C 0x83 0x8f). 1,392 retail samples across 17/17 captures
 * all carry the invariant body {@code 83 8f 00 00 00 00}; this
 * test pins those bytes so any future cleanup of PacketBuilderTCP
 * cannot regress what the client receives.
 */
public class Packet838FTest {

    /** Slice exactly the wire-meaningful bytes (BAOS buf has
     *  trailing zero padding; size() is the prefix the socket
     *  will write). */
    private static byte[] wireBytes(Packet838F pkt) {
        byte[] data = pkt.getData();
        int n = pkt.size();
        byte[] sliced = new byte[n];
        System.arraycopy(data, 0, sliced, 0, n);
        return sliced;
    }

    @Test
    public void writesTenByteFramedPacket() {
        // Catalog sample `838f0000000000` is the 7-byte BODY
        // (opcode + 5 zeros). PacketBuilderTCP frames it with
        // `fe LL LL` for a 10-byte total wire packet.
        Packet838F pkt = new Packet838F();
        assertEquals("expected 10 wire bytes", 10, pkt.size());
        byte[] data = pkt.getData();
        assertEquals((byte) 0xfe, data[0]);
        assertEquals("LE16 body length lo", 7, data[1] & 0xff);
        assertEquals("LE16 body length hi", 0, data[2] & 0xff);
        assertEquals("opcode hi", (byte) 0x83, data[3]);
        assertEquals("opcode lo", (byte) 0x8f, data[4]);
        for (int i = 5; i < 10; i++) {
            assertEquals("trailing zero at offset " + i, 0, data[i]);
        }
    }

    @Test
    public void bodyMatchesRetailCaptureSample() {
        // The retail catalog records the BODY as `838f0000000000`
        // (7 bytes); Ceres-J wraps that in PacketBuilderTCP's FE
        // frame for transmission. Pin the framed bytes here.
        byte[] expected = {
                (byte) 0xfe, 0x07, 0x00,
                (byte) 0x83, (byte) 0x8f,
                0x00, 0x00, 0x00, 0x00, 0x00
        };
        assertArrayEquals(expected, wireBytes(new Packet838F()));
    }

    @Test
    public void multipleInstancesProduceIdenticalBytes() {
        // Parameter-less constructor — every instance must
        // serialise to the same bytes. Catches accidental
        // dependency on shared mutable state.
        byte[] a = wireBytes(new Packet838F());
        byte[] b = wireBytes(new Packet838F());
        assertArrayEquals(a, b);
    }
}
