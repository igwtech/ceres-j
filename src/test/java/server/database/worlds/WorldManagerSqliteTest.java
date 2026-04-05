package server.database.worlds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.SqliteDatabase;

/**
 * Covers the three WorldManager boot paths after the SQLite rewire:
 *
 * <ol>
 *   <li>SQLite populated — rows come from {@code world_defs}.</li>
 *   <li>SQLite empty, no client mounted — boot cleanly with empty map.</li>
 *   <li>SQLite empty, test-injected ini stream — legacy parser fills map.</li>
 * </ol>
 */
public class WorldManagerSqliteTest {

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        SqliteDatabase.initWithConnection(conn);
        WorldManager.reset();
    }

    @After
    public void tearDown() throws Exception {
        WorldManager.reset();
        if (conn != null) conn.close();
    }

    private void insertWorld(int id, String path, String bsp) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "INSERT INTO world_defs (id, path, bsp_name) VALUES ("
                + id + ", '" + path + "', '" + bsp + "')");
        }
    }

    @Test
    public void loadsFromSqliteWhenPopulated() throws Exception {
        insertWorld(1, "plaza/plaza_p1", "plaza_p1.bsp");
        insertWorld(2, "plaza/plaza_p2", "plaza_p2.bsp");
        insertWorld(42, "pepper/pepper_p1", "pepper_p1.bsp");

        WorldManager.init();

        assertEquals("plaza/plaza_p1", WorldManager.getWorldname(1));
        assertEquals("plaza/plaza_p2", WorldManager.getWorldname(2));
        assertEquals("pepper/pepper_p1", WorldManager.getWorldname(42));
        assertNull(WorldManager.getWorldname(999));
    }

    @Test
    public void bootsCleanlyWhenSqliteEmptyAndNoClient() throws Exception {
        // world_defs is empty, no ini stream override, VirtualFileSystem
        // will return null on this host (no NC2ClientPath configured in
        // the test JVM). init() must not throw.
        WorldManager.init();

        // getWorldname returns null for every lookup; this is the
        // contract existing callers already handle.
        assertNull(WorldManager.getWorldname(1));
        assertNull(WorldManager.getWorldname(42));
    }

    @Test
    public void fallsBackToInjectedIniStream() throws Exception {
        // world_defs empty -> fall back path -> injected ini stream.
        String ini =
            "set 5 \".\\worlds\\plaza\\plaza_p1.bsp\";\r\n" +
            "set 9 \".\\worlds\\pepper\\pepper_p2.bsp\";\r\n";
        WorldManager.testIniStreamOverride =
            new ByteArrayInputStream(ini.getBytes(StandardCharsets.US_ASCII));

        WorldManager.init();

        assertEquals("plaza/plaza_p1", WorldManager.getWorldname(5));
        assertEquals("pepper/pepper_p2", WorldManager.getWorldname(9));
    }
}
