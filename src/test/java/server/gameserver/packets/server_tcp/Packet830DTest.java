package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import org.junit.Test;

import server.testtools.BytesIdenticalAssertion;

/**
 * Unit tests for {@link Packet830D} — the GameinfoReady (0x83 0x0d)
 * server→client TCP packet sent at the start of every world entry
 * and after a Zoning1/Zoning2 transition.
 *
 * <p>This used to coexist with a byte-identical legacy class named
 * {@code Sync}; that duplication was removed and {@code Packet830D}
 * is the sole construction site. These tests pin the on-the-wire
 * bytes so the impending rename to {@code GameinfoReady} (and any
 * future cleanup of the {@code PacketBuilderTCP} FE-framing) cannot
 * regress what the client actually receives.
 */
public class Packet830DTest {

    /** Slice exactly the bytes the TCP send-path will write to the
     *  socket (see GameServerTCPConnection: writes data, 0, size).
     *  The raw {@code getData()} buffer is BAOS-allocated at 32 B
     *  and tail-padded with zeros — the network never sees the
     *  padding. */
    private static byte[] wireBytes(Packet830D pkt) {
        byte[] data = pkt.getData();
        int n = pkt.size();
        byte[] sliced = new byte[n];
        System.arraycopy(data, 0, sliced, 0, n);
        return sliced;
    }

    @Test
    public void writesFourBytePayloadInsideFeFrame() {
        Packet830D pkt = new Packet830D();
        // size() reports the meaningful prefix the socket will see.
        assertEquals("expected 7 wire bytes", 7, pkt.size());
        byte[] data = pkt.getData();
        // FE-frame envelope: 0xfe + LE16 body length + body.
        // Body is [0x83, 0x0d, 0x00, 0x00] → length=4 → total 7B.
        assertEquals((byte) 0xfe, data[0]);
        assertEquals("LE16 length lo", 4, data[1] & 0xff);
        assertEquals("LE16 length hi", 0, data[2] & 0xff);
        assertEquals("opcode hi (0x83 subsystem)",
                (byte) 0x83, data[3]);
        assertEquals("opcode lo (0x0d GameinfoReady)",
                (byte) 0x0d, data[4]);
        assertEquals("trailing pad byte 1", 0, data[5]);
        assertEquals("trailing pad byte 2", 0, data[6]);
    }

    @Test
    public void multipleConstructionsProduceIdenticalBytes() {
        // The constructor is parameter-less — every instance must
        // produce the same wire bytes. This catches accidental
        // reliance on shared mutable state in PacketBuilderTCP.
        byte[] a = wireBytes(new Packet830D());
        byte[] b = wireBytes(new Packet830D());
        assertArrayEquals("two Packet830D instances must serialise "
                + "to identical bytes", a, b);
    }

    @Test
    public void exactBytesMatchRetailCapture() {
        // Verified hex from PROTOCOL.md (line 100, 1392): retail
        // emits exactly `fe 04 00 83 0d 00 00` for GameinfoReady.
        byte[] expected = {
                (byte) 0xfe, 0x04, 0x00,
                (byte) 0x83, 0x0d, 0x00, 0x00
        };
        assertArrayEquals(expected, wireBytes(new Packet830D()));
    }

    @Test
    public void retailCatalogMatchViaAssertionUtility() {
        // Same check as exactBytesMatchRetailCapture but routed
        // through BytesIdenticalAssertion — this is the
        // recommended one-liner pattern for new pin tests.
        // The utility strips the FE-frame envelope and diffs
        // against the catalog body bytes (`830d0000`).
        BytesIdenticalAssertion.assertMatchesRetail(
                BytesIdenticalAssertion.sliceWire(new Packet830D()),
                "tcp_s2c_830d");
    }
}
