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

        // 4 (arena) + 9 (reaktor) = 13 objects
        assertEquals(13, countRows("world_objects"));
        // 0 (arena) + 2 (reaktor) = 2 doors
        assertEquals(2, countRows("world_doors"));
        // 0 (arena) + 2 (reaktor) = 2 NPCs
        assertEquals(2, countRows("world_npcs"));
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
        assertEquals(13, countRows("world_objects"));
        // The bogus file produced no rows (since it failed parse).
        assertEquals(0, countRowsWhere("world_objects",
                "world_path = 'worlds/pak_bogus.dat'"));
    }

    @Test
    public void npcRowsAndWaypointTablesExist() throws Exception {
        WorldDatImporter.runForRoot(conn, tempRoot.toFile(),
                new String[]{"worlds"});
        // Both NPCs in pak_reaktor_nc.dat carry a single waypoint
        // each (verified offline; the byte at offset 24 is the
        // waypoint count, not a "moving" flag — even STATIC actors
        // can have it set to 1).
        assertEquals(2, countRows("world_npc_waypoints"));
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
            assertEquals(2, rs.getInt(1));
        }
    }
}
