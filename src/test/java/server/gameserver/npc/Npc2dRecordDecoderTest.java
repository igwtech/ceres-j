package server.gameserver.npc;

import static org.junit.Assert.*;

import org.junit.Test;

import server.gameserver.npc.Npc2dRecordDecoder.Decoded;
import server.gameserver.npc.Npc2dRecordDecoder.Form;

/**
 * Functional tests for {@link Npc2dRecordDecoder}.
 *
 * <p>Every fixture is a verbatim 0x03/0x2d body (offset 0 = the
 * {@code 0x2d} sub-opcode) pulled from a named retail capture by
 * {@code tools/extract_2d_layouts.py} / {@code verify_2d_record71.py}
 * over the 17-capture corpus. The hex strings are reused as test
 * vectors so the asserted field offsets are exactly what the wire
 * carried — no synthesised bytes.
 */
public class Npc2dRecordDecoderTest {

    private static byte[] hex(String h) {
        h = h.replaceAll("\\s+", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    // ─── 0x71 ENTITY record — RETAIL_VEHICLE_DRONE / CREATION ──────

    /** sub=0xee cat=0x0003, byte5=0x71, route=0x20. Byte-identical
     *  across CREATION_LEVELING + DRSTONE captures (1,569 obs). */
    @Test
    public void decodesEntityRecord_ee_fromRetail() {
        byte[] b = hex(
            "2dee030000712056ccc245ffffffff45ceec4a448effae" +
            "c504148b45ce2c43448effaec543000080060000000100" +
            "000081ca090088630b");
        assertEquals(55, b.length);
        Decoded d = Npc2dRecordDecoder.decode(b);
        assertEquals(Form.ENTITY_RECORD, d.form);
        assertEquals(0xee, d.subAction);
        assertEquals(0x0003, d.category);
        assertEquals(0x71, d.recordDisc);
        assertEquals(0x20, d.route);
        // posX at [7..10] = 56 cc c2 45 = 6233.542
        assertEquals(6233.542f, d.posX, 0.01f);
        assertEquals(Npc2dRecordDecoder.NO_TARGET, d.secondRef);
        assertFalse(d.hasTarget());
        // f2 == f5 (posY echo): [19..22] == [31..34] = 44 8e ff ae
        assertEquals(Float.floatToRawIntBits(d.f2),
                     Float.floatToRawIntBits(d.f5));
        assertTrue(d.isPositionBearing());
        assertTrue("invariant block must match retail",
                d.invariantOk);
        assertTrue("tail marker must match retail",
                d.tailMarkerOk);
        assertArrayEquals(hex("88630b"), d.tail);
    }

    /** sub=0xa0 cat=0x0003 from RETAIL_VEHICLE_DRONE. */
    @Test
    public void decodesEntityRecord_a0_fromRetail() {
        byte[] b = hex(
            "2da0030000712056ccc245ffffffff456c380e44801a94" +
            "452ba794456c780644801a944543000080060000000100" +
            "000081ca09008041ff");
        Decoded d = Npc2dRecordDecoder.decode(b);
        assertEquals(Form.ENTITY_RECORD, d.form);
        assertEquals(0xa0, d.subAction);
        assertEquals(0x71, d.recordDisc);
        assertEquals(0x20, d.route);
        assertEquals(6233.542f, d.posX, 0.01f);
        assertEquals(Float.floatToRawIntBits(d.f2),
                     Float.floatToRawIntBits(d.f5));
        assertTrue(d.invariantOk);
        assertTrue(d.tailMarkerOk);
        assertArrayEquals(hex("8041ff"), d.tail);
    }

    /** sub=0x54 cat=0x0003 from RETAIL_VEHICLE_DRONE (different
     *  posX / float-block high byte 0xc5). */
    @Test
    public void decodesEntityRecord_54_fromRetail() {
        byte[] b = hex(
            "2d540300007120ba55e444ffffffffc5de4bee4424502b" +
            "c567f288c5de6bea4424502bc543000080060000000100" +
            "000081ca0900e0e28c");
        Decoded d = Npc2dRecordDecoder.decode(b);
        assertEquals(Form.ENTITY_RECORD, d.form);
        assertEquals(0x54, d.subAction);
        // posX [7..10] = ba 55 e4 44 = 1826.679
        assertEquals(1826.679f, d.posX, 0.01f);
        assertEquals(Float.floatToRawIntBits(d.f2),
                     Float.floatToRawIntBits(d.f5));
        assertTrue(d.invariantOk);
        assertTrue(d.tailMarkerOk);
    }

    // ─── 0x75 LOCAL record — RETAIL captures ──────────────────────

    /** sub=0xfc cat=0x0003 byte5=0x75 from AUGUSTO. Handle-chain
     *  shape; surfaced opaque (insufficient-evidence per slot). */
    @Test
    public void decodesLocalRecord_fc_fromRetail() {
        byte[] b = hex(
            "2dfc03000075860f00809ac466e677c3008010c4804781" +
            "3f0000000050faef00dedb4e01b8ebef0affffffff8cfa" +
            "ef00793c4a017cfaef");
        assertEquals(55, b.length);
        Decoded d = Npc2dRecordDecoder.decode(b);
        assertEquals(Form.LOCAL_RECORD, d.form);
        assertEquals(0xfc, d.subAction);
        assertEquals(0x0003, d.category);
        assertEquals(0x75, d.recordDisc);
        // body[6] = 0x86 (route/sub-class for the 0x75 record).
        assertEquals(0x86, d.route);
        // localBody = bytes [7..54] (48 bytes), held verbatim.
        // body[7] = 0x0f → localBody[0].
        assertEquals(48, d.localBody.length);
        assertEquals((byte) 0x0f, d.localBody[0]);
        assertFalse(d.isPositionBearing());
        assertFalse(d.hasTarget());
    }

    /** sub=0x0a cat=0x0001 byte5=0x75 from RETAIL_VEHICLE_DRONE
     *  (1,710 obs across 8 captures — the lifecycle family). */
    @Test
    public void decodesLocalRecord_0a_cat1_fromRetail() {
        byte[] b = hex(
            "2d0a01000075ef0040faef00cb5f2c700000100100000" +
            "000d0606f0d50faef00985f2c70d0606f0d0000000074" +
            "faef0042254801d0606f");
        Decoded d = Npc2dRecordDecoder.decode(b);
        assertEquals(Form.LOCAL_RECORD, d.form);
        assertEquals(0x0a, d.subAction);
        assertEquals(0x0001, d.category);
        assertEquals(0x75, d.recordDisc);
        assertEquals(48, d.localBody.length);
    }

    // ─── 10-byte SHORT form ───────────────────────────────────────

    /** sub=0xfb cat=0x0003: 2d fb 03 00 00 0a 00 00 00 00.
     *  147 obs (null token). */
    @Test
    public void decodesShortForm_nullToken_fromRetail() {
        byte[] b = hex("2dfb0300000a00000000");
        assertEquals(10, b.length);
        Decoded d = Npc2dRecordDecoder.decode(b);
        assertEquals(Form.SHORT, d.form);
        assertEquals(0xfb, d.subAction);
        assertEquals(0x0003, d.category);
        // token = bytes [6..9] = 00 00 00 00
        assertEquals(0x00000000, d.shortToken);
    }

    /** sub=0xd1 cat=0x0003: 2d d1 03 00 00 0a 00 60 1f 45.
     *  91 obs — the recurring non-null token. */
    @Test
    public void decodesShortForm_valueToken_fromRetail() {
        byte[] b = hex("2dd10300000a00601f45");
        Decoded d = Npc2dRecordDecoder.decode(b);
        assertEquals(Form.SHORT, d.form);
        assertEquals(0xd1, d.subAction);
        // Body is exactly 10B: indices 6..9 = 00 60 1f 45, so the
        // LE32 token = 0x451f6000 (vs 0x00000000 for the null variant
        // — these are the only two trailing tokens seen in retail).
        assertEquals(0x451f6000, d.shortToken);
    }

    // ─── 6-byte PING form ─────────────────────────────────────────

    /** sub=0x01 cat=0x0001: 2d 01 01 00 00 06 (36 obs). */
    @Test
    public void decodesPingForm_fromRetail() {
        byte[] b = hex("2d0101000006");
        assertEquals(6, b.length);
        Decoded d = Npc2dRecordDecoder.decode(b);
        assertEquals(Form.PING, d.form);
        assertEquals(0x01, d.subAction);
        assertEquals(0x0001, d.category);
    }

    /** sub=0x22 cat=0x0001: 2d 22 01 00 00 06 (35 obs). */
    @Test
    public void decodesPingForm_sub22_fromRetail() {
        Decoded d = Npc2dRecordDecoder.decode(hex("2d2201000006"));
        assertEquals(Form.PING, d.form);
        assertEquals(0x22, d.subAction);
        assertEquals(0x0001, d.category);
    }

    // ─── robustness ───────────────────────────────────────────────

    @Test
    public void unknownFormForBadInput() {
        assertEquals(Form.UNKNOWN,
                Npc2dRecordDecoder.decode(null).form);
        assertEquals(Form.UNKNOWN,
                Npc2dRecordDecoder.decode(new byte[]{0x2d}).form);
        // wrong sub-opcode byte
        assertEquals(Form.UNKNOWN,
                Npc2dRecordDecoder.decode(hex("1f0101000006")).form);
        // 55B but unrecognised discriminator at [5]
        byte[] weird = new byte[55];
        weird[0] = 0x2d; weird[5] = (byte) 0xAB;
        assertEquals(Form.UNKNOWN,
                Npc2dRecordDecoder.decode(weird).form);
    }

    @Test
    public void invariantBlockConstantsAreThePinnedBytes() {
        assertArrayEquals(
            hex("430000800600000001000000"),
            Npc2dRecordDecoder.INVARIANT_BLOCK);
        assertArrayEquals(
            hex("81ca0900"),
            Npc2dRecordDecoder.TAIL_MARKER);
    }
}
