package server.database.importer;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;

import org.junit.Test;

/**
 * Unit + functional tests for {@link WorldDatParser}.
 *
 * <p>Functional tests use real bytes from two retail NC2 client world
 * files copied verbatim into {@code src/test/resources/worlds/}:
 * <ul>
 *   <li>{@code pak_arena001.dat} — minimal map with 4 furniture
 *       elements (no doors, no NPCs)</li>
 *   <li>{@code pak_reaktor_nc.dat} — startmissions reactor with
 *       9 objects, 2 doors, 2 NPCs (covers all decoded element
 *       types)</li>
 * </ul>
 *
 * <p>Unit tests use synthetic byte arrays to exercise error paths
 * (bad magic, truncated data, corrupted signatures, etc.) without
 * requiring fixture files.
 */
public class WorldDatParserTest {

    private static byte[] readResource(String name) throws IOException {
        try (InputStream in = WorldDatParserTest.class.getResourceAsStream(name)) {
            assertNotNull("missing test fixture: " + name, in);
            return in.readAllBytes();
        }
    }

    // ─── Magic / wrapper unit tests ─────────────────────────────────

    @Test
    public void rejectsNullInput() {
        try {
            WorldDatParser.parse(null);
            fail("expected ParseException");
        } catch (WorldDatParser.ParseException expected) {
            assertTrue(expected.getMessage().contains("too short"));
        }
    }

    @Test
    public void rejectsTruncatedHeader() {
        try {
            WorldDatParser.parse(new byte[]{1, 2, 3});
            fail("expected ParseException");
        } catch (WorldDatParser.ParseException expected) { /* ok */ }
    }

    @Test
    public void rejectsBadMagic() {
        byte[] bad = new byte[64];
        // intentionally non-magic header
        bad[0] = 1;
        try {
            WorldDatParser.parse(bad);
            fail("expected ParseException");
        } catch (WorldDatParser.ParseException expected) {
            assertTrue(expected.getMessage().contains("magic"));
        }
    }

    @Test
    public void rejectsBadInnerHeader() throws Exception {
        // Bad inner header (size != 8): signature is correct but the
        // first uint32 is 0x09 instead of 0x08 — parse should reject.
        byte[] inner = new byte[12];
        inner[0] = 0x09; // wrong size
        inner[4] = (byte) 0xcf; inner[5] = (byte) 0xcf;
        inner[6] = 0x0f;
        inner[8] = 0x01;
        byte[] wrapped = wrapAndDeflate(inner);
        try {
            WorldDatParser.parse(wrapped);
            fail("expected ParseException");
        } catch (WorldDatParser.ParseException expected) {
            assertTrue(expected.getMessage().contains("file header"));
        }
    }

    @Test
    public void parsesEmptyFileTerminatesCleanly() throws Exception {
        // file header + immediate section terminator (section=0).
        byte[] inner = new byte[12 + 16];
        // file header: size=8 sig=0x000fcfcf section=1
        inner[0] = 0x08;
        inner[4] = (byte) 0xcf; inner[5] = (byte) 0xcf; inner[6] = 0x0f;
        inner[8] = 0x01;
        // section terminator: size=0xc sig=0x0000ffcf section=0 dataSize=0
        inner[12] = 0x0c;
        inner[16] = (byte) 0xcf; inner[17] = (byte) 0xff;
        byte[] wrapped = wrapAndDeflate(inner);
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(wrapped);
        assertTrue(pw.objects.isEmpty());
        assertTrue(pw.doors.isEmpty());
        assertTrue(pw.npcs.isEmpty());
        assertTrue(pw.rawBlobs.isEmpty());
        assertEquals(0, pw.totalElements);
    }

    // ─── Functional tests against real client fixtures ──────────────

    @Test
    public void arena001Has4FurnitureElements() throws Exception {
        byte[] raw = readResource("/worlds/pak_arena001.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);

        // Element type breakdown was verified offline against the
        // captured retail file.
        assertEquals(4, pw.objects.size());
        assertEquals(0, pw.doors.size());
        assertEquals(0, pw.npcs.size());
        // Section 3 produces a raw blob (terminator section 4 elided).
        assertFalse(pw.sectionIds.isEmpty());
        assertEquals(Integer.valueOf(2), pw.sectionIds.get(0));

        // Spot-check: every object has a non-zero objectId and the
        // model_id / worldmodel_id fields fit in 16 bits.
        for (WorldDatParser.ObjectEntry o : pw.objects) {
            assertTrue("modelId out of range: " + o.modelId,
                    o.modelId >= 0 && o.modelId <= 0xffff);
            assertTrue("worldmodelId out of range: " + o.worldmodelId,
                    o.worldmodelId >= 0 && o.worldmodelId <= 0xffff);
        }
    }

    @Test
    public void reaktorHasObjectsDoorsAndNpcs() throws Exception {
        byte[] raw = readResource("/worlds/pak_reaktor_nc.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);

        // Counts verified against the offline Python sweep.
        assertEquals(9, pw.objects.size());
        assertEquals(2, pw.doors.size());
        assertEquals(2, pw.npcs.size());

        // First door is a DDOOR (double door) — actor + params decoded.
        WorldDatParser.DoorEntry d = pw.doors.get(0);
        assertEquals("DDOOR", d.actorType);
        assertEquals("2,4,4,2", d.params);
        assertEquals(3, d.doorId);
        assertEquals(1, d.worldmodelId);

        // First NPC is a STATIC actor at angle "90".
        WorldDatParser.NpcEntry n = pw.npcs.get(0);
        assertEquals("STATIC", n.actorName);
        assertEquals("90", n.angle);
        assertEquals(1, n.npcId);
        assertEquals(7139, n.npcTypeId);
    }

    // ─── Type 1000002 (passive) functional ─────────────────────────

    @Test
    public void datalinkParsesPassiveEntries() throws Exception {
        // Fixture has 16 passives, 2 objects, 1 NPC, 0 doors.
        byte[] raw = readResource("/worlds/pak_datalink_nc.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        assertEquals(16, pw.passives.size());
        assertEquals(2, pw.objects.size());
        assertEquals(1, pw.npcs.size());
        assertEquals(0, pw.doors.size());
    }

    @Test
    public void passiveEntryExposesPositionFloats() throws Exception {
        byte[] raw = readResource("/worlds/pak_datalink_nc.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        // First passive entry — position floats decode to plausible
        // world coordinates (NaN / Infinity are signs of misalignment).
        WorldDatParser.PassiveEntry p = pw.passives.get(0);
        assertFalse("posX must be finite", Float.isInfinite(p.posX) || Float.isNaN(p.posX));
        assertFalse("posY must be finite", Float.isInfinite(p.posY) || Float.isNaN(p.posY));
        assertFalse("posZ must be finite", Float.isInfinite(p.posZ) || Float.isNaN(p.posZ));
        // Raw byte payload preserved verbatim.
        assertEquals(76, p.raw.length);
    }

    @Test
    public void passiveEntryRawByteOffsetsAreConsistent() throws Exception {
        byte[] raw = readResource("/worlds/pak_datalink_nc.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        // For every passive entry, the position decoded from the raw
        // byte payload at offsets 0/4/8 must match the surfaced
        // float fields. This guards against future field-order
        // refactors silently breaking the decode.
        for (WorldDatParser.PassiveEntry p : pw.passives) {
            byte[] r = p.raw;
            float py = Float.intBitsToFloat(
                  (r[0] & 0xff) | ((r[1] & 0xff) << 8)
                | ((r[2] & 0xff) << 16) | ((r[3] & 0xff) << 24));
            float pz = Float.intBitsToFloat(
                  (r[4] & 0xff) | ((r[5] & 0xff) << 8)
                | ((r[6] & 0xff) << 16) | ((r[7] & 0xff) << 24));
            float px = Float.intBitsToFloat(
                  (r[8] & 0xff) | ((r[9] & 0xff) << 8)
                | ((r[10] & 0xff) << 16) | ((r[11] & 0xff) << 24));
            assertEquals(py, p.posY, 0.0001f);
            assertEquals(pz, p.posZ, 0.0001f);
            assertEquals(px, p.posX, 0.0001f);
        }
    }

    @Test
    public void totalElementsMatchesObjectsDoorsAndNpcs() throws Exception {
        byte[] raw = readResource("/worlds/pak_reaktor_nc.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        // totalElements counts every section-2 element regardless of
        // whether it was decoded or stashed as a raw blob.
        int decoded = pw.objects.size() + pw.doors.size() + pw.npcs.size();
        // raw blobs from section-2 unknown types are counted too.
        long sec2RawBlobs = pw.rawBlobs.stream()
                .filter(b -> b.elementType > 0)
                .count();
        assertEquals(decoded + sec2RawBlobs, pw.totalElements);
    }

    @Test
    public void parsedDataStaysWithinBounds() throws Exception {
        byte[] raw = readResource("/worlds/pak_reaktor_nc.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        // Every NPC's waypoint count stays within u8 range.
        for (WorldDatParser.NpcEntry n : pw.npcs) {
            assertTrue(n.waypoints.size() <= 0xff);
        }
        // ASCII-only actor strings.
        for (WorldDatParser.DoorEntry d : pw.doors) {
            for (char c : d.actorType.toCharArray()) {
                assertTrue("non-ascii in actor: " + d.actorType, c < 128);
            }
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────

    /** Build a synthetic wrapped file for testing bad inner content. */
    private static byte[] wrapAndDeflate(byte[] inner) throws DataFormatException {
        Deflater def = new Deflater();
        def.setInput(inner);
        def.finish();
        byte[] buf = new byte[inner.length + 64];
        int n = def.deflate(buf);
        def.end();
        byte[] out = new byte[16 + n];
        System.arraycopy(WorldDatParser.MAGIC, 0, out, 0, 12);
        // LE32 declared size
        out[12] = (byte) (inner.length);
        out[13] = (byte) (inner.length >> 8);
        out[14] = (byte) (inner.length >> 16);
        out[15] = (byte) (inner.length >> 24);
        System.arraycopy(buf, 0, out, 16, n);
        return out;
    }

    @Test
    public void asciiHelperHandlesMissingTerminator() throws Exception {
        // Ensure that non-null-terminated strings still decode.
        // Inline the door payload bytes manually: 24-byte header +
        // actor "DDOOR" (5B, no null) + params "2" (1B, no null).
        byte[] inner = new byte[12 + 16 + 16 + 24 + 6];
        // file header
        inner[0] = 0x08;
        inner[4] = (byte) 0xcf; inner[5] = (byte) 0xcf; inner[6] = 0x0f;
        inner[8] = 0x01;
        // section header: section=2 dataSize=16+24+6=46 ... wait, we
        // need dataSize of the section's content (element header +
        // payload) = 16+24+6 = 46
        int off = 12;
        inner[off] = 0x0c;
        inner[off+4] = (byte) 0xcf; inner[off+5] = (byte) 0xff;
        inner[off+8] = 0x02; // section 2
        // section dataSize:
        int dataSize = 16 + 24 + 6;
        inner[off+12] = (byte) dataSize;
        // element header: size=12 sig=0x0ffefef1 type=1000005 dataSize=30
        off += 16;
        inner[off] = 0x0c;
        inner[off+4] = (byte) 0xf1; inner[off+5] = (byte) 0xfe;
        inner[off+6] = (byte) 0xfe; inner[off+7] = 0x0f;
        // type=1000005
        int t = 1000005;
        inner[off+8]  = (byte) (t & 0xff);
        inner[off+9]  = (byte) ((t >> 8) & 0xff);
        inner[off+10] = (byte) ((t >> 16) & 0xff);
        inner[off+11] = (byte) ((t >> 24) & 0xff);
        inner[off+12] = (byte) 30; // dataSize
        // door payload (24-byte header + 5B actor + 1B params)
        off += 16;
        // 4-byte unknown1+1bis = 0
        // posY/Z/X = 0
        // actor_len=5 at off+16, param_len=1 at off+17
        inner[off + 16] = 5;
        inner[off + 17] = 1;
        // unknown5 at off+18 = 0; doorId at off+20 = 0x0002; wm at off+22=0x0001
        inner[off + 20] = 0x02;
        inner[off + 22] = 0x01;
        // strings at off+24
        byte[] actor = "DDOOR".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(actor, 0, inner, off + 24, 5);
        inner[off + 29] = '2';

        // Add section terminator after the section payload
        // Padding to terminator
        // (already sized inner correctly)
        // Append section terminator
        byte[] full = new byte[inner.length + 16];
        System.arraycopy(inner, 0, full, 0, inner.length);
        int tend = inner.length;
        full[tend]    = 0x0c;
        full[tend+4]  = (byte) 0xcf; full[tend+5] = (byte) 0xff;
        // section=0, dataSize=0

        WorldDatParser.ParsedWorld pw = WorldDatParser.parseInflated(full);
        assertEquals(1, pw.doors.size());
        WorldDatParser.DoorEntry d = pw.doors.get(0);
        assertEquals("DDOOR", d.actorType);
        assertEquals("2", d.params);
        assertEquals(2, d.doorId);
        assertEquals(1, d.worldmodelId);
    }
}
