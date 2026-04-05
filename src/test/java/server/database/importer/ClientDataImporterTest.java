package server.database.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.SqliteDatabase;

/**
 * Exercises {@link ClientDataImporter#runIfNeeded(Connection, InputStream)}
 * against an in-memory SQLite database. Verifies idempotency by running
 * the importer twice and asserting the row count is unchanged.
 */
public class ClientDataImporterTest {

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        SqliteDatabase.initWithConnection(conn);
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    private InputStream fixture() {
        InputStream in = getClass().getClassLoader()
            .getResourceAsStream("importer/worlds_sample.ini");
        assertNotNull("classpath fixture importer/worlds_sample.ini missing", in);
        return in;
    }

    private int worldDefsCount() throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM world_defs")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    public void importsFromInjectedStream() throws Exception {
        assertEquals(0, worldDefsCount());
        ClientDataImporter.runIfNeeded(conn, fixture());
        assertEquals(5, worldDefsCount());

        // Spot-check one row end-to-end.
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT path, bsp_name FROM world_defs WHERE id = 1")) {
            assertEquals(true, rs.next());
            assertEquals("plaza/plaza_p1", rs.getString("path"));
            assertEquals("plaza_p1.bsp", rs.getString("bsp_name"));
        }
    }

    @Test
    public void runIfNeededIsIdempotent() throws Exception {
        ClientDataImporter.runIfNeeded(conn, fixture());
        int firstCount = worldDefsCount();

        // Second call should detect the populated table and short-circuit.
        ClientDataImporter.runIfNeeded(conn, fixture());
        int secondCount = worldDefsCount();

        assertEquals(5, firstCount);
        assertEquals(firstCount, secondCount);
    }

    @Test
    public void skipsWhenStreamHasNoEntries() throws Exception {
        // A stream with only comments should not throw and should leave
        // the table empty.
        String onlyComments = "/* nothing here */\r\n// nope\r\n";
        ClientDataImporter.runIfNeeded(conn,
            new ByteArrayInputStream(onlyComments.getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0, worldDefsCount());
    }
}
