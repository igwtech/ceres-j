package server.gameserver.npc;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit + functional tests for {@link MobDataDecoder}.
 *
 * <p>The functional fixtures are the first 32 bytes of three retail
 * 54-byte broadcasts from the catalog evidence (the catalog
 * truncates samples at 32 bytes; the missing 18 bytes form the
 * opaque tail). For tests that need a full 50-byte body we pad the
 * tail with zero bytes — the decoder treats the tail as opaque and
 * must accept any contents.
 */
public class MobDataDecoderTest {

    private static byte[] hex(String h) {
        h = h.replaceAll("\\s+", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    /** Splice retail prefix + zero-pad to LONG_LEN. */
    private static byte[] padToLong(byte[] prefix) {
        byte[] full = new byte[MobDataDecoder.LONG_LEN];
        System.arraycopy(prefix, 0, full, 0,
                Math.min(prefix.length, full.length));
        return full;
    }

    // ─── Long variant — retail combat fixture ──────────────────────

    @Test
    public void decodesCombatSampleFromRetailCapture() {
        // From RETAIL_RETAIL_LONG_PARTY_B: state 0x71, npc 0x153.
        byte[] prefix = hex(
            "53010000 71 40 426a3a45 ffffffff" +
            "01b5282a000000aac20000aac2a8f92f0197");
        byte[] inner = padToLong(prefix);
        MobDataDecoder.DecodedMob m = MobDataDecoder.decodeLong(inner);
        assertNotNull(m);
        assertEquals(0x153, m.npcId);
        assertEquals(MobState.COMBAT, m.state);
        assertEquals(0x40, m.flagsByte);
        assertEquals(MobDataDecoder.NO_TARGET, m.targetId);
        assertTrue(m.isInCombat());
        assertFalse(m.hasTarget());
        // Altitude is 0x453a6a42 = ~2982.65 (confirmed via offline
        // float interpretation).
        assertEquals(2982.6504f, m.altitude, 1.0f);
    }

    @Test
    public void decodesIdleSampleFromRetailCapture() {
        // From RETAIL_RETAIL_VEHICLE_DRONE: state 0x75, npc 0x3ab.
        byte[] prefix = hex(
            "ab030000 75 00 000000c0 b0c42675" +
            "ac440000000000000000000000000000");
        byte[] inner = padToLong(prefix);
        MobDataDecoder.DecodedMob m = MobDataDecoder.decodeLong(inner);
        assertNotNull(m);
        assertEquals(0x3ab, m.npcId);
        assertEquals(MobState.IDLE, m.state);
        assertEquals(0x00, m.flagsByte);
        assertFalse(m.isInCombat());
    }

    @Test
    public void preservesUnmappedStateByteWithNullEnum() {
        // Synthesise a body with state = 0xAA (never observed). The
        // decoder must return a DecodedMob with null state and the
        // raw byte preserved so the caller can log + drop without
        // losing information.
        byte[] inner = padToLong(hex("01000000 aa 00 00000000 ffffffff"));
        MobDataDecoder.DecodedMob m = MobDataDecoder.decodeLong(inner);
        assertNotNull(m);
        assertNull(m.state);
        assertEquals(0xaa, m.rawStateByte);
    }

    @Test
    public void rareStateBytesAreRecognised() {
        for (int b : new int[]{0x70, 0x72, 0x6f}) {
            byte[] inner = padToLong(new byte[]{
                    1, 0, 0, 0, (byte) b, 0,
                    0, 0, 0, 0, (byte) 0xff, (byte) 0xff,
                    (byte) 0xff, (byte) 0xff});
            MobDataDecoder.DecodedMob m = MobDataDecoder.decodeLong(inner);
            assertNotNull(m);
            assertNotNull("expected enum for byte 0x"
                    + Integer.toHexString(b), m.state);
            assertEquals(b, m.rawStateByte);
        }
    }

    @Test
    public void rejectsWrongLength() {
        assertNull(MobDataDecoder.decodeLong(null));
        assertNull(MobDataDecoder.decodeLong(new byte[49]));
        assertNull(MobDataDecoder.decodeLong(new byte[51]));
    }

    @Test
    public void tailHasExpectedLength() {
        byte[] inner = padToLong(hex("01000000 75 00 00000000 ffffffff"));
        MobDataDecoder.DecodedMob m = MobDataDecoder.decodeLong(inner);
        assertNotNull(m);
        // 50 - 14 = 36 bytes of opaque trailer.
        assertEquals(36, m.tail.length);
    }

    // ─── Short variant — heartbeat ─────────────────────────────────

    @Test
    public void decodesShortHeartbeatSample() {
        // From the catalog evidence: 9-byte sample post-strip = 8B.
        // Sample bytes were `00 f8 09 00 08 bc 34 b5 42` (9B), so
        // the inner (after stripping the 0x2d sub-tag byte at front)
        // is 8 bytes: `f8 09 00 08 bc 34 b5 42`.
        byte[] inner = hex("f8090008bc34b542");
        MobDataDecoder.DecodedHeartbeat h = MobDataDecoder.decodeShort(inner);
        assertNotNull(h);
        assertEquals(0x09f8, h.mapId);
        // statusFlags = 0x42b534bc — opaque but should round-trip.
        assertEquals(0x42b534bc, h.statusFlags);
    }

    @Test
    public void shortRejectsWrongLength() {
        assertNull(MobDataDecoder.decodeShort(null));
        assertNull(MobDataDecoder.decodeShort(new byte[7]));
        assertNull(MobDataDecoder.decodeShort(new byte[9]));
    }
}
