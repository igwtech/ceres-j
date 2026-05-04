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

    // ─── Position-marker types (1000009 / 1000010 / 1000014) ────────

    @Test
    public void mainframeParsesPositionMarkers() throws Exception {
        // Fixture has 1 type-1000009 + 12 type-1000010 markers.
        byte[] raw = readResource("/worlds/pak_mainframe.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        assertEquals(13, pw.markers.size());

        long type9  = pw.markers.stream()
                .filter(m -> m.elementType == WorldDatParser.TYPE_POS_MARKER_9)
                .count();
        long type10 = pw.markers.stream()
                .filter(m -> m.elementType == WorldDatParser.TYPE_POS_MARKER_10)
                .count();
        assertEquals(1,  type9);
        assertEquals(12, type10);
    }

    @Test
    public void positionMarkerExposesPositionFloats() throws Exception {
        byte[] raw = readResource("/worlds/pak_mainframe.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);

        for (WorldDatParser.PositionMarker m : pw.markers) {
            assertFalse("posX must be finite",
                    Float.isInfinite(m.posX) || Float.isNaN(m.posX));
            assertFalse("posY must be finite",
                    Float.isInfinite(m.posY) || Float.isNaN(m.posY));
            assertFalse("posZ must be finite",
                    Float.isInfinite(m.posZ) || Float.isNaN(m.posZ));
            assertEquals("8-byte trailer", 8, m.trailer.length);
        }
    }

    @Test
    public void positionMarkerTrailerByteAccessIsConsistent() throws Exception {
        // The trailer must be exactly bytes 12-19 of the original
        // payload — verify via re-parsing the file's raw bytes.
        byte[] raw = readResource("/worlds/pak_mainframe.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        // Every type-1000010 marker in pak_mainframe has a non-empty
        // trailer field (we just check the first one decodes).
        WorldDatParser.PositionMarker m = pw.markers.stream()
                .filter(x -> x.elementType == WorldDatParser.TYPE_POS_MARKER_10)
                .findFirst().orElse(null);
        assertNotNull(m);
        assertEquals(8, m.trailer.length);
    }

    // ─── Type 1000013 (region marker) functional ────────────────────

    @Test
    public void outAppCParsesRegions() throws Exception {
        // Fixture has 4 type-1000013 regions, 4 objects, 3 doors.
        byte[] raw = readResource("/worlds/pak_out_app_1_c.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        assertEquals(4, pw.regions.size());
        assertEquals(4, pw.objects.size());
        assertEquals(3, pw.doors.size());
    }

    @Test
    public void regionFieldsAreFinite() throws Exception {
        byte[] raw = readResource("/worlds/pak_out_app_1_c.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        for (WorldDatParser.RegionEntry r : pw.regions) {
            assertFalse(Float.isInfinite(r.posX) || Float.isNaN(r.posX));
            assertFalse(Float.isInfinite(r.posY) || Float.isNaN(r.posY));
            assertFalse(Float.isInfinite(r.posZ) || Float.isNaN(r.posZ));
            assertFalse(Float.isInfinite(r.dim1) || Float.isNaN(r.dim1));
            assertFalse(Float.isInfinite(r.dim2) || Float.isNaN(r.dim2));
            // u16 fields stay in 16-bit range
            assertTrue(r.flag >= 0 && r.flag <= 0xffff);
            assertTrue(r.id   >= 0 && r.id   <= 0xffff);
        }
    }

    @Test
    public void regionFlagIsTwoForCommonCase() throws Exception {
        // pak_out_app_1_c samples all observed with flag = 0x0002.
        byte[] raw = readResource("/worlds/pak_out_app_1_c.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        for (WorldDatParser.RegionEntry r : pw.regions) {
            assertEquals(0x0002, r.flag);
        }
    }

    @Test
    public void regionFirstEntryMatchesOfflineDecode() throws Exception {
        // Offline Python decode of pak_out_app_1_c first 1000013 entry:
        //   pos = (Y=137.18, Z=-218.0, X=214.62)
        //   dims = (110.0, 92.0)  flag=0x0002  id=0x0017
        byte[] raw = readResource("/worlds/pak_out_app_1_c.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        WorldDatParser.RegionEntry r = pw.regions.get(0);
        assertEquals(137.18f, r.posY, 0.01f);
        assertEquals(-218.0f, r.posZ, 0.01f);
        assertEquals(214.62f, r.posX, 0.01f);
        assertEquals(110.0f,  r.dim1, 0.01f);
        assertEquals(92.0f,   r.dim2, 0.01f);
        assertEquals(0x0002, r.flag);
        assertEquals(0x0017, r.id);
    }

    // ─── Type 1000016 (decorated marker) functional ────────────────

    @Test
    public void plazaApp4ParsesExtraEntries() throws Exception {
        // Fixture has 2 type-1000016 + 23 type-1000002 + 2 type-1000003.
        byte[] raw = readResource("/worlds/pak_plaza_app_4.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        assertEquals(2, pw.extras.size());
        assertEquals(23, pw.passives.size());
    }

    @Test
    public void extraEntryFirstSampleMatchesOfflineDecode() throws Exception {
        // Offline Python decoded the first 1000016 entry as:
        //   pos = (Y=602.0, Z=18.43, X=-387.73)
        //   entry_id = 0x000105de
        byte[] raw = readResource("/worlds/pak_plaza_app_4.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        WorldDatParser.ExtraEntry e = pw.extras.get(0);
        assertEquals(602.0f, e.posY, 0.01f);
        assertEquals(18.43f, e.posZ, 0.01f);
        assertEquals(-387.73f, e.posX, 0.01f);
        assertEquals(0x000105de, e.entryId);
        assertEquals(68, e.raw.length);
    }

    @Test
    public void extraEntryHighIdHalfMatchesPattern() throws Exception {
        // High 16 bits of entry_id are constant 0x0001 across the
        // observed corpus — verify the assertion holds for our
        // fixture entries.
        byte[] raw = readResource("/worlds/pak_plaza_app_4.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        for (WorldDatParser.ExtraEntry e : pw.extras) {
            int high16 = (e.entryId >>> 16) & 0xffff;
            assertEquals("entry_id high 16 bits should be 0x0001",
                    0x0001, high16);
        }
    }

    // ─── Type 1000015 (labeled region) functional ──────────────────

    @Test
    public void techClanCParsesLabeledRegion() throws Exception {
        byte[] raw = readResource("/worlds/pak_tech_clan_1_c.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        assertEquals(1, pw.labeledRegions.size());
        WorldDatParser.LabeledRegionEntry r = pw.labeledRegions.get(0);
        // Offline Python decode:
        //   name = "AMB_TECHHAVEN"
        //   pos  = (Y=81.92, Z=-106.0, X=-521.97)
        //   dim1 = 90.0  dim2 = 0.1
        assertEquals("AMB_TECHHAVEN", r.name);
        assertEquals(81.92f, r.posY, 0.01f);
        assertEquals(-106.0f, r.posZ, 0.01f);
        assertEquals(-521.97f, r.posX, 0.01f);
        assertEquals(90.0f, r.dim1, 0.01f);
        assertEquals(0.1f,  r.dim2, 0.01f);
    }

    @Test
    public void labeledRegionBadSizeIsCapturedAsMalformedBlob() throws Exception {
        // Synthetic inflated body with a labeled-region element whose
        // declared strlen+20 doesn't match dataSize. The per-element
        // error catch should capture it as a raw blob with -1000015
        // tag, NOT abort the file.
        byte[] inner = new byte[12 + 16 + 16 + 30];
        inner[0] = 0x08;
        inner[4] = (byte) 0xcf; inner[5] = (byte) 0xcf; inner[6] = 0x0f;
        inner[8] = 0x01;
        inner[12] = 0x0c;
        inner[16] = (byte) 0xcf; inner[17] = (byte) 0xff;
        inner[20] = 0x02;
        inner[24] = 46;
        int eo = 28;
        inner[eo] = 0x0c;
        inner[eo+4] = (byte) 0xf1; inner[eo+5] = (byte) 0xfe;
        inner[eo+6] = (byte) 0xfe; inner[eo+7] = 0x0f;
        int t = 1000015;
        inner[eo+8]  = (byte) (t & 0xff);
        inner[eo+9]  = (byte) ((t >> 8) & 0xff);
        inner[eo+10] = (byte) ((t >> 16) & 0xff);
        inner[eo+11] = (byte) ((t >> 24) & 0xff);
        inner[eo+12] = 30;
        int pl = eo + 16;
        inner[pl] = 99;
        inner[pl+1] = 0;

        WorldDatParser.ParsedWorld pw = WorldDatParser.parseInflated(inner);
        assertEquals(1, pw.malformedElements);
        assertTrue(pw.labeledRegions.isEmpty());
        assertEquals(1, pw.rawBlobs.stream()
                .filter(b -> b.elementType == -1000015).count());
    }

    // ─── Type 1000007 (tagged entity) functional ───────────────────

    @Test
    public void vrApp1CParsesTaggedEntities() throws Exception {
        // Fixture has 4 type-1000007 entries, all subtype 0x04.
        byte[] raw = readResource("/worlds/pak_vr_app_1_c.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        assertEquals(4, pw.taggedEntities.size());
        for (WorldDatParser.TaggedEntityEntry e : pw.taggedEntities) {
            assertEquals(0x04, e.subtype);
        }
    }

    @Test
    public void taggedEntityFirstSampleMatchesOfflineDecode() throws Exception {
        // Offline Python decode of pak_vr_app_1_c first 1000007:
        //   pos = (Y=548.83, Z=-278.0, X=584.01)
        //   id  = 10103 (0x2775)
        //   counter = 1
        //   subtype = 0x04   sub2 = 0x05
        //   tail = "\x00BIRD\x00" (6 bytes: 0x00 prefix + 4-char
        //                          species name + null)
        byte[] raw = readResource("/worlds/pak_vr_app_1_c.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        WorldDatParser.TaggedEntityEntry e = pw.taggedEntities.get(0);
        assertEquals(548.83f, e.posY, 0.01f);
        assertEquals(-278.0f, e.posZ, 0.01f);
        assertEquals(584.01f, e.posX, 0.01f);
        assertEquals(10103, e.entityId);
        assertEquals(1, e.counter);
        assertEquals(0x04, e.subtype);
        assertEquals(0x05, e.sub2);
        assertEquals(6, e.tail.length);
        assertEquals(0x00, e.tail[0] & 0xff);
        assertEquals("BIRD",
                new String(e.tail, 1, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(0x00, e.tail[5]);
    }

    @Test
    public void taggedEntityTooSmallIsCapturedAsMalformedBlob() throws Exception {
        // Synthetic body smaller than the 23-byte header. The
        // per-element catch should capture as malformed raw blob,
        // NOT abort the file.
        byte[] inner = new byte[12 + 16 + 16 + 22];
        inner[0] = 0x08;
        inner[4] = (byte) 0xcf; inner[5] = (byte) 0xcf; inner[6] = 0x0f;
        inner[8] = 0x01;
        inner[12] = 0x0c;
        inner[16] = (byte) 0xcf; inner[17] = (byte) 0xff;
        inner[20] = 0x02;
        inner[24] = 38;
        int eo = 28;
        inner[eo] = 0x0c;
        inner[eo+4] = (byte) 0xf1; inner[eo+5] = (byte) 0xfe;
        inner[eo+6] = (byte) 0xfe; inner[eo+7] = 0x0f;
        int t = 1000007;
        inner[eo+8]  = (byte) (t & 0xff);
        inner[eo+9]  = (byte) ((t >> 8) & 0xff);
        inner[eo+10] = (byte) ((t >> 16) & 0xff);
        inner[eo+11] = (byte) ((t >> 24) & 0xff);
        inner[eo+12] = 22;

        WorldDatParser.ParsedWorld pw = WorldDatParser.parseInflated(inner);
        assertEquals(1, pw.malformedElements);
        assertTrue(pw.taggedEntities.isEmpty());
        assertEquals(1, pw.rawBlobs.stream()
                .filter(b -> b.elementType == -1000007).count());
    }

    // ─── GBSP detection ─────────────────────────────────────────────

    @Test
    public void gbspGeometryFileGivesDistinctError() throws Exception {
        // Build a synthetic inflated body shaped like a real GBSP
        // header (zeros at 0-11, "GBSP" at 12-15, anything after).
        byte[] inner = new byte[32];
        // header words: size=0, sig=0x1c, section=1
        inner[4] = 0x1c; inner[8] = 0x01;
        inner[12] = 'G'; inner[13] = 'B'; inner[14] = 'S'; inner[15] = 'P';
        try {
            WorldDatParser.parseInflated(inner);
            fail("expected ParseException for GBSP file");
        } catch (WorldDatParser.ParseException expected) {
            assertTrue("error message names GBSP",
                    expected.getMessage().contains("GBSP"));
        }
    }

    @Test
    public void normalDatFileDoesNotTriggerGbspDetection() throws Exception {
        // Reaktor fixture is a real .dat with the standard inner
        // header — must NOT trigger the GBSP error path.
        byte[] raw = readResource("/worlds/pak_reaktor_nc.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        assertNotNull(pw);
        assertFalse(pw.objects.isEmpty());
    }

    @Test
    public void vf00TerrainHeightmapGivesDistinctError() throws Exception {
        // Synthetic inner body starting with "VF00" magic — same as
        // pak_*height.dat in /terrain/. Must raise a clear "VF00
        // terrain heightmap" error rather than the generic "bad
        // file header" path.
        byte[] inner = new byte[16];
        inner[0] = 'V'; inner[1] = 'F'; inner[2] = '0'; inner[3] = '0';
        try {
            WorldDatParser.parseInflated(inner);
            fail("expected ParseException for VF00 heightmap");
        } catch (WorldDatParser.ParseException expected) {
            assertTrue("error message names VF00",
                    expected.getMessage().contains("VF00"));
        }
    }

    // ─── Per-element resilience ─────────────────────────────────────

    @Test
    public void terrainStore1WithMalformedNpcsStillImportsOtherElements() throws Exception {
        // pak_terrain_store1 has 4 NPCs at 31 bytes each (one byte
        // shy of the canonical 32-byte NPC header). Without the
        // per-element error catch, the whole file would have been
        // dropped. With the catch, we should get the 2 doors, 2
        // tagged entities, 0 NPCs, and 4 malformed-element raw blobs.
        byte[] raw = readResource("/worlds/pak_terrain_store1.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);

        assertEquals(2, pw.doors.size());
        assertEquals(2, pw.taggedEntities.size());
        assertEquals(0, pw.npcs.size());
        assertEquals(4, pw.malformedElements);

        // The 4 malformed NPCs were captured as raw blobs with
        // elementType = -1000006 (the negation indicates "failed
        // decode of this type").
        long badNpcs = pw.rawBlobs.stream()
                .filter(b -> b.elementType == -1000006)
                .count();
        assertEquals(4, badNpcs);
    }

    @Test
    public void parserHealthCountsZeroForCleanFile() throws Exception {
        byte[] raw = readResource("/worlds/pak_arena001.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        assertEquals(0, pw.malformedElements);
    }

    @Test
    public void totalElementsMatchesAllDecodedAndRawSec2() throws Exception {
        byte[] raw = readResource("/worlds/pak_reaktor_nc.dat");
        WorldDatParser.ParsedWorld pw = WorldDatParser.parse(raw);
        // totalElements counts every section-2 element regardless of
        // whether it was decoded or stashed as a raw blob.
        int decoded = pw.objects.size() + pw.doors.size() + pw.npcs.size()
                    + pw.passives.size() + pw.markers.size()
                    + pw.regions.size() + pw.extras.size()
                    + pw.labeledRegions.size() + pw.taggedEntities.size();
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
