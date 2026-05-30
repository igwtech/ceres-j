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

    // ────────────────────────── task #254 — retail 13B variant
    //
    // Live retail (2026-05-22 Braine, 2026-05-24 RETRY3 portal
    // cross) consistently sends `0xa0/0x02` with an 8B trailer
    // identical to SessionReady's payload: `15 00 00 00 00 00 80 3f`
    // (LE32=21 + float32=1.0). Ceres-J emits 2B by default to
    // preserve catalog parity with the 224 historic samples, but
    // the 13B variant via `new InteractionAck(true)` is what the
    // portal/chair/door interaction handlers must use.

    @Test
    public void retailPayloadVariantEmitsExpected13Bytes() {
        InteractionAck pkt = new InteractionAck(true);
        // 3-byte FE frame + 2-byte opcode + 8-byte payload = 13B.
        assertEquals(13, pkt.size());
        byte[] data = pkt.getData();
        byte[] expected = {
                (byte) 0xfe, 0x0a, 0x00,        // FE frame, len=10
                (byte) 0xa0, 0x02,              // opcode
                0x15, 0x00, 0x00, 0x00,         // LE32 = 21
                0x00, 0x00, (byte) 0x80, 0x3f   // float32 LE = 1.0
        };
        byte[] actual = new byte[13];
        System.arraycopy(data, 0, actual, 0, 13);
        assertArrayEquals(
                "task #254: 13B retail variant byte-identical",
                expected, actual);
    }

    @Test
    public void retailPayloadIsStableAcrossInstances() {
        byte[] a = new InteractionAck(true).getData();
        byte[] b = new InteractionAck(true).getData();
        byte[] aSliced = new byte[13];
        byte[] bSliced = new byte[13];
        System.arraycopy(a, 0, aSliced, 0, 13);
        System.arraycopy(b, 0, bSliced, 0, 13);
        assertArrayEquals(aSliced, bSliced);
    }

    @Test
    public void defaultConstructorStillEmits2BCatalogForm() {
        // Backwards-compat: the 224-sample catalog parity test
        // (`emitsRetailExactBytes` above) drives the 2B form. The
        // no-arg ctor MUST continue to emit 2B for that to keep
        // passing.
        InteractionAck pkt = new InteractionAck();
        assertEquals("no-arg ctor must remain 5B wire (2B body)",
                5, pkt.size());
    }

    @Test
    public void retailPayloadConstantMatchesSessionReady() {
        // Both InteractionAck(true) and SessionReady use the SAME
        // 8B retail payload. If a future retail capture proves
        // they diverge, this pin will catch it and force an
        // explicit constant split.
        assertArrayEquals(
                "InteractionAck retail payload must match "
                        + "SessionReady payload",
                SessionReady.RETAIL_BODY_PAYLOAD_2_5,
                InteractionAck.RETAIL_PAYLOAD_2_5);
    }

    @Test
    public void distinctFromSessionReadyByOneBit() {
        // InteractionAck (a0 02) and SessionReady (a0 01) share
        // the a0 prefix but differ at the second byte. SessionReady
        // gained an 8-byte payload in May 2026 retail (so they now
        // have DIFFERENT lengths). A future refactor that
        // accidentally collapses them must fail this test.
        byte[] interaction = BytesIdenticalAssertion.sliceWire(
                new InteractionAck());  // 5B framed
        byte[] sessionReady = BytesIdenticalAssertion.sliceWire(
                new SessionReady());    // 13B framed (10B body)

        // Different opcode (second body byte 0x02 vs 0x01) — even
        // if lengths drift back together someday, this byte must
        // remain distinct.
        assertNotEquals(
                "InteractionAck and SessionReady must stay distinct at body[1]",
                interaction[4], sessionReady[4]);
    }
}
