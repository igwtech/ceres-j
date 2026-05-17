package server.database.worlds;

import static org.junit.Assert.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.SqliteDatabase;

/**
 * Covers {@link WorldManager#resolveByName(String)} added in task
 * #182 so the {@code zone} GM command accepts a zone name as well as
 * a numeric id. The {@code ZoneCommand.resolveZone} numeric-vs-name
 * dispatch is covered in {@code GmCommandFunctionalTest} (same
 * package as the package-private helper).
 */
public class WorldManagerResolveByNameTest {

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        SqliteDatabase.initWithConnection(conn);
        WorldManager.reset();
        insertWorld(1, "plaza/plaza_p1", "plaza_p1.bsp");
        insertWorld(101, "plaza/plaza_p3", "plaza_p3.bsp");
        insertWorld(5, "pepper/pepper_p1", "pepper_p1.bsp");
        insertWorld(7, "pepper/pepper_p3", "pepper_p3.bsp");
        WorldManager.init();
    }

    @After
    public void tearDown() throws Exception {
        WorldManager.reset();
        if (conn != null) conn.close();
    }

    private void insertWorld(int id, String path, String bsp)
            throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "INSERT INTO world_defs (id, path, bsp_name) VALUES ("
                + id + ", '" + path + "', '" + bsp + "')");
        }
    }

    @Test
    public void resolvesFullPath() {
        assertEquals(1, WorldManager.resolveByName("plaza/plaza_p1"));
        assertEquals(7, WorldManager.resolveByName("pepper/pepper_p3"));
    }

    @Test
    public void resolvesBasename() {
        assertEquals(101, WorldManager.resolveByName("plaza_p3"));
        assertEquals(5, WorldManager.resolveByName("pepper_p1"));
    }

    @Test
    public void resolvesCompactShortForm() {
        // "pepper1" ↔ pepper/pepper_p1 (the _p sector separator
        // elided) — the exact case the legacy cmdWarp rejected.
        assertEquals(5, WorldManager.resolveByName("pepper1"));
        assertEquals(7, WorldManager.resolveByName("pepper3"));
    }

    @Test
    public void resolutionIsCaseInsensitive() {
        assertEquals(1, WorldManager.resolveByName("PLAZA/Plaza_P1"));
        assertEquals(5, WorldManager.resolveByName("Pepper_P1"));
    }

    @Test
    public void unknownNameReturnsMinusOne() {
        assertEquals(-1, WorldManager.resolveByName("no_such_zone"));
        assertEquals(-1, WorldManager.resolveByName(""));
        assertEquals(-1, WorldManager.resolveByName(null));
    }
}
