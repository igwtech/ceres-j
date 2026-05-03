package server.database.importer;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Functional tests for {@link WorldDatImporter}.
 *
 * <p>Each test boots an in-memory SQLite, materialises the two
 * canonical fixture files into a {@code worlds/} subdirectory of a
 * temp dir, runs the importer against that root, and asserts the row
 * counts and a few decoded values.
 *
 * <p>The fixtures live in {@code src/test/resources/worlds/} and were
 * copied verbatim from a retail NC2 client install — they are not
 * generated.
 */
public class WorldDatImporterTest {

    private Connection conn;
    private Path tempRoot;
    private Path worldsDir;

    @Before
    public void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        tempRoot = Files.createTempDirectory("ceres-bsp-test");
        worldsDir = tempRoot.resolve("worlds");
        Files.createDirectories(worldsDir);
        copyFixture("/worlds/pak_arena001.dat",
                worldsDir.resolve("pak_arena001.dat"));
        copyFixture("/worlds/pak_reaktor_nc.dat",
                worldsDir.resolve("pak_reaktor_nc.dat"));
        copyFixture("/worlds/pak_datalink_nc.dat",
                worldsDir.resolve("pak_datalink_nc.dat"));
        copyFixture("/worlds/pak_mainframe.dat",
                worldsDir.resolve("pak_mainframe.dat"));
        copyFixture("/worlds/pak_out_app_1_c.dat",
                worldsDir.resolve("pak_out_app_1_c.dat"));
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null) conn.close();
        if (tempRoot != null) {
            // Best-effort cleanup.
            Files.walk(tempRoot)
                .sorted((a, b) -> b.toString().compareTo(a.toString()))
                .forEach(p -> { try { Files.deleteIfExists(p); }
                                catch (IOException ignore) {} });
        }
    }

    private static void copyFixture(String resource, Path dst)
            throws IOException {
        try (InputStream in = WorldDatImporterTest.class.getResourceAsStream(resource)) {
            assertNotNull("missing fixture " + resource, in);
            Files.copy(in, dst);
        }
    }

    private int countRows(String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private int countRowsWhere(String table, String where) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table
                                            + " WHERE " + where)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    @Test
    public void runForRootImportsObjectsDoorsAndNpcs() throws Exception {
        WorldDatImporter.runForRoot(conn, tempRoot.toFile(),
                new String[]{"worlds"});

        // arena       (4 obj, 0 door, 0 npc, 0 passive,  0 marker, 0 region)
        // reaktor     (9, 2, 2, 0,  6,  0)
        // datalink    (2, 0, 1, 16, 0,  0)
        // mainframe   (2, 0, 8, 0,  13, 0)
        // out_app_1_c (4, 3, 0, 0,  0,  4)
        assertEquals(21, countRows("world_objects"));
        assertEquals(5,  countRows("world_doors"));
        assertEquals(11, countRows("world_npcs"));
        assertEquals(16, countRows("world_passive_objects"));
        assertEquals(19, countRows("world_position_markers"));
        assertEquals(4,  countRows("world_regions"));
    }

    @Test
    public void perWorldPathPartitioning() throws Exception {
        WorldDatImporter.runForRoot(conn, tempRoot.toFile(),
                new String[]{"worlds"});
        // Each fixture's rows are tagged with a unique world_path.
        assertEquals(4, countRowsWhere("world_objects",
                "world_path = 'worlds/pak_arena001.dat'"));
        assertEquals(9, countRowsWhere("world_objects",
                "world_path = 'worlds/pak_reaktor_nc.dat'"));
        assertEquals(2, countRowsWhere("world_doors",
                "world_path = 'worlds/pak_reaktor_nc.dat'"));
    }

    @Test
    public void doorActorTypeAndParamsRoundTrip() throws Exception {
        WorldDatImporter.runForRoot(conn, tempRoot.toFile(),
                new String[]{"worlds"});
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT actor_type, params FROM world_doors"
                   + " WHERE world_path='worlds/pak_reaktor_nc.dat'"
                   + " ORDER BY door_id LIMIT 1")) {
            assertTrue(rs.next());
            assertEquals("DDOOR", rs.getString(1));
            assertEquals("2,4,4,2", rs.getString(2));
        }
    }

    @Test
    public void rerunIsIdempotent() throws Exception {
        WorldDatImporter.runForRoot(conn, tempRoot.toFile(),
                new String[]{"worlds"});
        int firstObj = countRows("world_objects");
        WorldDatImporter.runForRoot(conn, tempRoot.toFile(),
                new String[]{"worlds"});
        int secondObj = countRows("world_objects");
        assertEquals("re-running must not duplicate rows",
                firstObj, secondObj);
    }

    @Test
    public void missingClientRootDoesNotThrow() throws Exception {
        // Pretend the client isn't installed: pass a non-existent dir.
        File nope = new File(tempRoot.toFile(), "no-such-dir");
        WorldDatImporter.runForRoot(conn, nope, new String[]{"worlds"});
        // Tables are created (so subsequent runs work); rows are 0.
        assertEquals(0, countRows("world_objects"));
        assertEquals(0, countRows("world_doors"));
        assertEquals(0, countRows("world_npcs"));
    }

    @Test
    public void corruptFileIsSkippedNotFatal() throws Exception {
        // Drop a bogus file alongside the real ones.
        Path bogus = worldsDir.resolve("pak_bogus.dat");
        Files.write(bogus, new byte[]{0, 0, 0, 0, 0, 0, 0, 0,
                                      0, 0, 0, 0, 0, 0, 0, 0});
        WorldDatImporter.runForRoot(conn, tempRoot.toFile(),
                new String[]{"worlds"});
        // The valid fixtures are still imported.
        assertEquals(21, countRows("world_objects"));
        // The bogus file produced no rows (since it failed parse).
        assertEquals(0, countRowsWhere("world_objects",
                "world_path = 'worlds/pak_bogus.dat'"));
    }

    @Test
    public void passiveObjectsHaveValidPositions() throws Exception {
        WorldDatImporter.runForRoot(conn, tempRoot.toFile(),
                new String[]{"worlds"});
        // Every passive row has finite position floats.
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT pos_x, pos_y, pos_z FROM world_passive_objects"
                   + " WHERE world_path='worlds/pak_datalink_nc.dat'")) {
            int rows = 0;
            while (rs.next()) {
                rows++;
                float x = rs.getFloat(1), y = rs.getFloat(2), z = rs.getFloat(3);
                assertFalse(Float.isInfinite(x) || Float.isNaN(x));
                assertFalse(Float.isInfinite(y) || Float.isNaN(y));
                assertFalse(Float.isInfinite(z) || Float.isNaN(z));
            }
            assertEquals(16, rows);
        }
    }

    @Test
    public void positionMarkersAreTaggedByElementType() throws Exception {
        WorldDatImporter.runForRoot(conn, tempRoot.toFile(),
                new String[]{"worlds"});
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT element_type, COUNT(*) FROM world_position_markers"
                   + " GROUP BY element_type ORDER BY element_type")) {
            assertTrue(rs.next());
            assertEquals(WorldDatParser.TYPE_POS_MARKER_9,  rs.getInt(1));
            // 1 from reaktor + 1 from mainframe = 2.
            assertEquals(2, rs.getInt(2));
            assertTrue(rs.next());
            assertEquals(WorldDatParser.TYPE_POS_MARKER_10, rs.getInt(1));
            // 5 from reaktor + 12 from mainframe = 17.
            assertEquals(17, rs.getInt(2));
            assertFalse(rs.next());
        }
    }

    @Test
    public void regionFieldsRoundTripIntoTable() throws Exception {
        WorldDatImporter.runForRoot(conn, tempRoot.toFile(),
                new String[]{"worlds"});
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT pos_x, pos_y, pos_z, dim1, dim2, flag, region_id"
                   + " FROM world_regions"
                   + " WHERE world_path = 'worlds/pak_out_app_1_c.dat'"
                   + " ORDER BY id LIMIT 1")) {
            assertTrue(rs.next());
            assertEquals(214.62f, rs.getFloat(1), 0.01f);
            assertEquals(137.18f, rs.getFloat(2), 0.01f);
            assertEquals(-218.0f, rs.getFloat(3), 0.01f);
            assertEquals(110.0f,  rs.getFloat(4), 0.01f);
            assertEquals(92.0f,   rs.getFloat(5), 0.01f);
            assertEquals(0x0002,  rs.getInt(6));
            assertEquals(0x0017,  rs.getInt(7));
        }
    }

    @Test
    public void regionsAreScopedByWorldPath() throws Exception {
        WorldDatImporter.runForRoot(conn, tempRoot.toFile(),
                new String[]{"worlds"});
        // Only out_app_1_c has regions in our fixture set.
        assertEquals(4, countRowsWhere("world_regions",
                "world_path = 'worlds/pak_out_app_1_c.dat'"));
        assertEquals(0, countRowsWhere("world_regions",
                "world_path = 'worlds/pak_arena001.dat'"));
    }

    @Test
    public void positionMarkerTrailerPreserved() throws Exception {
        WorldDatImporter.runForRoot(conn, tempRoot.toFile(),
                new String[]{"worlds"});
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT trailer FROM world_position_markers LIMIT 1")) {
            assertTrue(rs.next());
            byte[] trailer = rs.getBytes(1);
            assertEquals(8, trailer.length);
        }
    }

    @Test
    public void passiveRawByteBlobIsPreserved() throws Exception {
        WorldDatImporter.runForRoot(conn, tempRoot.toFile(),
                new String[]{"worlds"});
        // Every passive row carries the verbatim 76-byte payload so
        // future RE can refine the schema without re-walking the FS.
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT raw FROM world_passive_objects LIMIT 1")) {
            assertTrue(rs.next());
            byte[] raw = rs.getBytes(1);
            assertEquals(76, raw.length);
        }
    }

    @Test
    public void npcRowsAndWaypointTablesExist() throws Exception {
        WorldDatImporter.runForRoot(conn, tempRoot.toFile(),
                new String[]{"worlds"});
        // Both NPCs in pak_reaktor_nc.dat carry a single waypoint
        // each (verified offline; the byte at offset 24 is the
        // waypoint count, not a "moving" flag — even STATIC actors
        // can have it set to 1).
        assertEquals(11, countRows("world_npc_waypoints"));
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT actor_name FROM world_npcs"
                   + " WHERE world_path='worlds/pak_reaktor_nc.dat'"
                   + " ORDER BY npc_id LIMIT 1")) {
            assertTrue(rs.next());
            assertEquals("STATIC", rs.getString(1));
        }
    }

    @Test
    public void npcWaypointPositionsAreLinkedToOwningNpc() throws Exception {
        WorldDatImporter.runForRoot(conn, tempRoot.toFile(),
                new String[]{"worlds"});
        // Each waypoint row should join cleanly back to its NPC.
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM world_npc_waypoints w"
                   + "  JOIN world_npcs n ON w.npc_row_id = n.id")) {
            assertTrue(rs.next());
            assertEquals(11, rs.getInt(1));
        }
    }
}
