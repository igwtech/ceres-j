package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;

import org.junit.Test;

/**
 * Byte-identical regression tests for the TCP login handshake
 * triplet (S→C 0x8001 / 0x8003, C→S 0x8000 — only S→C tested
 * here since the C→S side is decoded not emitted).
 *
 * <p>Catalog evidence (auto-generated): all 71 samples of each
 * are identical, confirming the packets are pure constants:
 *
 * <pre>
 *   HandshakeA (0x8001) — body `80 01 66` × 71 samples
 *   HandshakeC (0x8003) — body `80 03 68` × 71 samples
 * </pre>
 *
 * <p>Pinning these now means a future cleanup of PacketBuilderTCP's
 * FE-framing or the {@code write(byte[])} path cannot regress
 * the login bytes the modern client expects in its first
 * 50 ms of TCP traffic.
 */
public class HandshakeABCByteIdentityTest {

    private static byte[] wireBytes(ByteArrayOutputStream pkt) {
        // PacketBuilderTCP exposes the BAOS buffer via getData()
        // with trailing zero padding; size() is the wire-meaningful
        // prefix.
        byte[] data = ((server.networktools.PacketBuilderTCP) pkt).getData();
        int n = pkt.size();
        byte[] sliced = new byte[n];
        System.arraycopy(data, 0, sliced, 0, n);
        return sliced;
    }

    @Test
    public void handshakeAExactBytes() {
        // Catalog body `80 01 66` framed = fe 03 00 80 01 66
        byte[] expected = {
                (byte) 0xfe, 0x03, 0x00,
                (byte) 0x80, 0x01, 0x66
        };
        assertArrayEquals(expected, wireBytes(new HandshakeA()));
    }

    @Test
    public void handshakeCExactBytes() {
        // Catalog body `80 03 68` framed = fe 03 00 80 03 68
        byte[] expected = {
                (byte) 0xfe, 0x03, 0x00,
                (byte) 0x80, 0x03, 0x68
        };
        assertArrayEquals(expected, wireBytes(new HandshakeC()));
    }

    @Test
    public void handshakeATotalSize() {
        // 3-byte FE header + 3-byte body = 6 bytes total.
        assertEquals(6, new HandshakeA().size());
    }

    @Test
    public void handshakeCTotalSize() {
        assertEquals(6, new HandshakeC().size());
    }

    @Test
    public void handshakeABytesAreInvariantAcrossInstances() {
        byte[] a = wireBytes(new HandshakeA());
        byte[] b = wireBytes(new HandshakeA());
        assertArrayEquals("two instances of HandshakeA must "
                + "serialise identically", a, b);
    }

    @Test
    public void handshakeCBytesAreInvariantAcrossInstances() {
        byte[] a = wireBytes(new HandshakeC());
        byte[] b = wireBytes(new HandshakeC());
        assertArrayEquals(a, b);
    }
}
