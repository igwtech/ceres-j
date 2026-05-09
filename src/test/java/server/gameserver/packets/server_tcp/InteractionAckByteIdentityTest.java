package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import org.junit.Test;

import server.testtools.BytesIdenticalAssertion;

/**
 * Byte-identity test for {@link InteractionAck} (TCP S→C
 * 2-byte transaction acknowledgement, body {@code a0 02}).
 *
 * <p>This test is the first to exercise the
 * {@link BytesIdenticalAssertion} utility against a real
 * catalog sample — proving the utility works end-to-end on
 * the production parity-test path.
 */
public class InteractionAckByteIdentityTest {

    @Test
    public void emitsRetailExactBytes() {
        // Catalog: 224 retail samples, all `a002`. Use the new
        // BytesIdenticalAssertion utility which strips the FE
        // frame envelope automatically and diffs against the
        // catalog body bytes.
        BytesIdenticalAssertion.assertMatchesRetail(
                BytesIdenticalAssertion.sliceWire(new InteractionAck()),
                "tcp_s2c_a002");
    }

    @Test
    public void exactWireBytesIncludingFrame() {
        // Belt-and-suspenders pin: the framed FE bytes that
        // actually go on the wire (FE-frame + body).
        InteractionAck pkt = new InteractionAck();
        byte[] wire = BytesIdenticalAssertion.sliceWire(pkt);
        // Catalog body `a002` framed = fe 02 00 a0 02 (5 bytes
        // total wire — same shape as SessionReady's a0 01).
        assertEquals(5, pkt.size());

        // Find the body within the framed wire bytes by
        // looking past the FE-frame envelope (the helper
        // already validates the envelope).
        // Verify the body bytes directly:
        byte[] data = pkt.getData();
        assertEquals((byte) 0xfe, data[0]);
        assertEquals(2, data[1] & 0xFF);
        assertEquals(0, data[2] & 0xFF);
        assertEquals((byte) 0xa0, data[3]);
        assertEquals(0x02, data[4]);
        assertNotNull(wire);
    }

    @Test
    public void multipleInstancesAreByteIdentical() {
        // Pure constant — every instance must produce the
        // same wire bytes. Catches accidental dependency on
        // shared mutable state.
        byte[] a = BytesIdenticalAssertion.sliceWire(new InteractionAck());
        byte[] b = BytesIdenticalAssertion.sliceWire(new InteractionAck());
        assertArrayEquals(a, b);
    }

    @Test
    public void distinctFromSessionReadyByOneBit() {
        // InteractionAck (a0 02) and SessionReady (a0 01) share
        // the a0 prefix but differ at the second byte. A future
        // refactor that accidentally collapses them into one
        // class will fail this test.
        byte[] interaction = BytesIdenticalAssertion.sliceWire(
                new InteractionAck());
        byte[] sessionReady = BytesIdenticalAssertion.sliceWire(
                new SessionReady());

        // Same length, same FE header, same first opcode byte.
        assertEquals(interaction.length, sessionReady.length);
        // Differ at the second opcode byte (last byte of body).
        assertNotEquals("InteractionAck and SessionReady must "
                + "stay distinct at the trailing byte",
                interaction[interaction.length - 1],
                sessionReady[sessionReady.length - 1]);
    }
}
