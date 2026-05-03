package server.gameserver.npc;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit + functional tests for {@link DroneControlDecoder}.
 *
 * <p>Functional fixtures come from
 * {@code RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715}'s
 * {@code DRONE_INUSE} marker. The catalog evidence truncates samples
 * at 32 bytes; tests pad the trailer with zeros — the decoder treats
 * the 20-byte trailer as opaque.
 */
public class DroneControlDecoderTest {

    private static byte[] hex(String h) {
        h = h.replaceAll("\\s+", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static byte[] padToControl(byte[] prefix) {
        byte[] full = new byte[DroneControlDecoder.CONTROL_LEN];
        System.arraycopy(prefix, 0, full, 0,
                Math.min(prefix.length, full.length));
        return full;
    }

    // ─── 41-byte control frame ─────────────────────────────────────

    @Test
    public void decodesRetailDroneControlSample() {
        // Sample: d603000002 ce2bbb43 a452dbc3 188a4545 + tail
        byte[] prefix = hex(
            "d6030000 02" +
            "ce2bbb43" +
            "a452dbc3" +
            "188a4545" +
            "00000000000000000000000000");
        byte[] inner = padToControl(prefix);
        DroneControlDecoder.DroneControl c =
                DroneControlDecoder.decodeControl(inner);
        assertNotNull(c);
        assertEquals(0x000003d6, c.droneId);
        assertEquals(DroneControlDecoder.CLASS_DRONE, c.classByte);
        // X = 0x43bb2bce ≈ 374.34
        assertEquals(374.34f, c.posX, 0.5f);
        // Y = 0xc3db52a4 ≈ -438.65
        assertEquals(-438.65f, c.posY, 0.5f);
        // Z = 0x45458a18 ≈ 3160.63
        assertEquals(3160.63f, c.posZ, 1.0f);
        assertEquals(20, c.tail.length);
    }

    @Test
    public void successiveFramesShowMonotonicYProgress() {
        // Three consecutive packets from the timeline: drone slid
        // sideways while X and Z stayed constant. Verify our decoder
        // sees the same monotonic Y progression.
        byte[] f1 = padToControl(hex("d6030000 02 ce2bbb43 a452dbc3 188a4545"));
        byte[] f2 = padToControl(hex("d6030000 02 ce2bbb43 edcadbc3 188a4545"));
        byte[] f3 = padToControl(hex("d6030000 02 ce2bbb43 933edcc3 188a4545"));

        DroneControlDecoder.DroneControl c1 = DroneControlDecoder.decodeControl(f1);
        DroneControlDecoder.DroneControl c2 = DroneControlDecoder.decodeControl(f2);
        DroneControlDecoder.DroneControl c3 = DroneControlDecoder.decodeControl(f3);

        // Same X, Z across all three frames.
        assertEquals(c1.posX, c2.posX, 0.0001f);
        assertEquals(c2.posX, c3.posX, 0.0001f);
        assertEquals(c1.posZ, c2.posZ, 0.0001f);
        assertEquals(c2.posZ, c3.posZ, 0.0001f);
        // Y monotonically more negative (drone sliding).
        assertTrue("Y should decrease across frames",
                c1.posY > c2.posY);
        assertTrue(c2.posY > c3.posY);
    }

    @Test
    public void rejectsNonDroneClassByte() {
        // Class byte != 0x02 means a different (un-decoded) entity
        // type — the decoder should return null so the caller can
        // route accordingly.
        byte[] inner = padToControl(hex(
            "ff000000 03" +    // class 0x03 instead of 0x02
            "00000000 00000000 00000000"));
        assertNull(DroneControlDecoder.decodeControl(inner));
    }

    @Test
    public void rejectsWrongLength() {
        assertNull(DroneControlDecoder.decodeControl(null));
        assertNull(DroneControlDecoder.decodeControl(new byte[36]));
        assertNull(DroneControlDecoder.decodeControl(new byte[38]));
    }

    // ─── 5-byte heartbeat ──────────────────────────────────────────

    @Test
    public void decodesRetailHeartbeatSample() {
        // Sample: 00 00 00 00 0b
        byte[] inner = hex("000000000b");
        DroneControlDecoder.DroneHeartbeat h =
                DroneControlDecoder.decodeHeartbeat(inner);
        assertNotNull(h);
        assertEquals(0, h.droneId);
        assertEquals(0x0b, h.statusByte);
    }

    @Test
    public void heartbeatRejectsWrongLength() {
        assertNull(DroneControlDecoder.decodeHeartbeat(null));
        assertNull(DroneControlDecoder.decodeHeartbeat(new byte[4]));
        assertNull(DroneControlDecoder.decodeHeartbeat(new byte[6]));
    }
}
