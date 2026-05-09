package server.database;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.accounts.Account;
import server.database.accounts.AccountManager;
import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.PlayerCharacterManager;

/**
 * Schema-level tests for the v4 -> v5 migration that adds {@code uuid}
 * columns to {@code accounts} and {@code player_characters}.
 *
 * <p>The wire-protocol integer {@code id} columns are deliberately left
 * untouched (they are broadcast in 0x8305 / CharInfo / 0x1b and replacing
 * them would catastrophically break the client). UUIDs sit alongside as
 * the SOAP-API identifier matching the {@code guid} simple type used in
 * {@code LauncherInterface}, {@code SessionManagement}, and
 * {@code PublicInterface}.
 */
public class SqliteUuidSchemaTest {

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        SqliteDatabase.initWithConnection(conn);
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    // ---------- schema introspection ----------

    @Test
    public void accountsTableHasUuidColumnAfterFreshCreate() throws Exception {
        Set<String> cols = readColumns("accounts");
        assertTrue("accounts must include a uuid column: " + cols,
                cols.contains("uuid"));
    }

    @Test
    public void playerCharactersTableHasUuidColumnAfterFreshCreate() throws Exception {
        Set<String> cols = readColumns("player_characters");
        assertTrue("player_characters must include a uuid column: " + cols,
                cols.contains("uuid"));
    }

    @Test
    public void integerIdColumnsAreUntouchedByUuidMigration() throws Exception {
        // Wire-protocol load-bearing — must remain INTEGER PRIMARY KEY
        // on both tables. The UUID column is purely additive.
        DatabaseMetaData md = conn.getMetaData();
        try (ResultSet rs = md.getColumns(null, null, "accounts", "id")) {
            assertTrue(rs.next());
            int sqlType = rs.getInt("DATA_TYPE");
            assertEquals("accounts.id must remain integer-typed",
                    java.sql.Types.INTEGER, sqlType);
        }
        try (ResultSet rs = md.getColumns(null, null, "player_characters", "id")) {
            assertTrue(rs.next());
            int sqlType = rs.getInt("DATA_TYPE");
            assertEquals("player_characters.id must remain integer-typed",
                    java.sql.Types.INTEGER, sqlType);
        }
    }

    @Test
    public void schemaVersionIsAtLeastFiveAfterInit() throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_versions")) {
            assertTrue(rs.next());
            int v = rs.getInt(1);
            assertTrue("Schema version " + v + " must include the uuid migration (>=5)",
                    v >= 5);
        }
    }

    // ---------- runtime UUID behaviour ----------

    @Test
    public void accountSaveAutoMintsUuidWhenAbsent() throws Exception {
        Account ua = new Account(700);
        ua.setUsername("uuidless_user");
        ua.setPassword("p");
        ua.setStatus("");
        // Intentionally do NOT call setUuid — saveAccount must mint one.
        assertNull(ua.getUuid());

        AccountManager.saveAccount(ua);

        // Account object now has a UUID.
        assertNotNull(ua.getUuid());

        // ...and the DB row reflects the same UUID string.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid FROM accounts WHERE id = ?")) {
            ps.setInt(1, 700);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                String dbUuid = rs.getString(1);
                assertNotNull(dbUuid);
                assertEquals(ua.getUuid().toString(), dbUuid);
            }
        }
    }

    @Test
    public void twoAccountsGetDistinctUuids() throws Exception {
        Account a = new Account(710);
        a.setUsername("first"); a.setPassword("p"); a.setStatus("");
        AccountManager.saveAccount(a);

        Account b = new Account(711);
        b.setUsername("second"); b.setPassword("p"); b.setStatus("");
        AccountManager.saveAccount(b);

        assertNotNull(a.getUuid());
        assertNotNull(b.getUuid());
        assertNotEquals("Each account must mint its own UUID",
                a.getUuid(), b.getUuid());
    }

    @Test
    public void accountUuidColumnEnforcesUniqueness() throws Exception {
        UUID dup = UUID.randomUUID();

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO accounts (id, uuid, username, password, char1, char2, char3, char4, status) "
                + "VALUES (?, ?, ?, ?, 0, 0, 0, 0, '')")) {
            ps.setInt(1, 720);
            ps.setString(2, dup.toString());
            ps.setString(3, "first_dup");
            ps.setString(4, "p");
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO accounts (id, uuid, username, password, char1, char2, char3, char4, status) "
                + "VALUES (?, ?, ?, ?, 0, 0, 0, 0, '')")) {
            ps.setInt(1, 721);
            ps.setString(2, dup.toString());
            ps.setString(3, "second_dup");
            ps.setString(4, "p");
            try {
                ps.executeUpdate();
                fail("Inserting a duplicate uuid should violate the UNIQUE constraint");
            } catch (java.sql.SQLException expected) {
                String m = expected.getMessage().toLowerCase();
                assertTrue("Expected a UNIQUE-constraint failure, got: " + expected.getMessage(),
                        m.contains("unique") || m.contains("constraint"));
            }
        }
    }

    @Test
    public void accountManagerFindByUuidReturnsCorrectAccount() throws Exception {
        Account ua = new Account(730);
        ua.setUsername("findme"); ua.setPassword("p"); ua.setStatus("");
        AccountManager.saveAccount(ua);

        // Reload AccountManager so its in-memory list is populated from DB.
        AccountManager.load();
        UUID uuid = ua.getUuid();
        Account hit = AccountManager.findByUuid(uuid);

        assertNotNull("findByUuid should return the matching account", hit);
        assertEquals("findme", hit.getUsername());
        assertEquals(730, hit.getId());
    }

    @Test
    public void accountManagerFindByUuidReturnsNullForUnknown() throws Exception {
        AccountManager.load();
        assertNull(AccountManager.findByUuid(UUID.randomUUID()));
        assertNull(AccountManager.findByUuid(null));
    }

    @Test
    public void playerCharacterSaveAutoMintsUuidWhenAbsent() throws Exception {
        // Insert a "raw" character with explicit uuid, then load + clear
        // the uuid in memory + save. The save path must defensively mint
        // a fresh UUID rather than NULL-out the column (which would
        // violate NOT NULL).
        insertMinimalCharacter(conn, 800, "PreloadedChar", "preset-uuid-row-0000");
        PlayerCharacterManager.load();
        PlayerCharacter pc = PlayerCharacterManager.getCharacter(800);
        assertNotNull(pc);

        pc.setUuid(null);
        PlayerCharacterManager.saveCharacter(pc);

        // pc now has a freshly-minted UUID.
        assertNotNull("saveCharacter must defensively mint a UUID", pc.getUuid());
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid FROM player_characters WHERE id = ?")) {
            ps.setInt(1, 800);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(pc.getUuid().toString(), rs.getString(1));
            }
        }
    }

    @Test
    public void playerCharacterUuidRoundTripsThroughDb() throws Exception {
        UUID expected = UUID.randomUUID();
        insertMinimalCharacter(conn, 810, "RoundTripChar", expected.toString());

        PlayerCharacterManager.load();
        PlayerCharacter pc = PlayerCharacterManager.getCharacter(810);
        assertNotNull(pc);
        assertEquals(expected, pc.getUuid());
    }

    @Test
    public void playerCharacterManagerFindByUuidReturnsCorrectCharacter() throws Exception {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        insertMinimalCharacter(conn, 820, "First", u1.toString());
        insertMinimalCharacter(conn, 821, "Second", u2.toString());

        PlayerCharacterManager.load();
        PlayerCharacter hit = PlayerCharacterManager.findByUuid(u2);
        assertNotNull(hit);
        assertEquals("Second", hit.getName());
        assertNull(PlayerCharacterManager.findByUuid(UUID.randomUUID()));
        assertNull(PlayerCharacterManager.findByUuid(null));
    }

    @Test
    public void playerCharacterUuidColumnEnforcesUniqueness() throws Exception {
        UUID dup = UUID.randomUUID();
        insertMinimalCharacter(conn, 830, "FirstDup", dup.toString());
        try {
            insertMinimalCharacter(conn, 831, "SecondDup", dup.toString());
            fail("Duplicate player_characters.uuid should violate UNIQUE");
        } catch (Exception expected) {
            String m = expected.getMessage().toLowerCase();
            assertTrue("Expected UNIQUE violation: " + expected.getMessage(),
                    m.contains("unique") || m.contains("constraint"));
        }
    }

    // ---------- legacy-DB migration backfill ----------

    @Test
    public void legacyAccountsRowsBackfilledWithUuid() throws Exception {
        // Drop the modern table and recreate the v4 schema by hand to
        // simulate a legacy DB. The schema_versions row gets reset to 4.
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS accounts");
            stmt.execute(
                "CREATE TABLE accounts ("
                + "  id INTEGER PRIMARY KEY,"
                + "  username TEXT NOT NULL UNIQUE,"
                + "  password TEXT NOT NULL,"
                + "  char1 INTEGER DEFAULT 0,"
                + "  char2 INTEGER DEFAULT 0,"
                + "  char3 INTEGER DEFAULT 0,"
                + "  char4 INTEGER DEFAULT 0,"
                + "  status TEXT DEFAULT ''"
                + ")"
            );
            stmt.executeUpdate("INSERT INTO accounts (id, username, password) VALUES (1, 'legacy_a', 'p')");
            stmt.executeUpdate("INSERT INTO accounts (id, username, password) VALUES (2, 'legacy_b', 'p')");
            stmt.executeUpdate("INSERT INTO accounts (id, username, password) VALUES (3, 'legacy_c', 'p')");

            // Force the migration to re-run by resetting the version row.
            stmt.execute("DELETE FROM schema_versions");
            stmt.execute("INSERT INTO schema_versions (version) VALUES (4)");
        }

        // Re-run init: this will see version 4 and apply the v4->v5
        // migration that adds + backfills the uuid column.
        SqliteDatabase.initWithConnection(conn);

        // Every legacy row now has a non-null UUID.
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, uuid FROM accounts ORDER BY id")) {
            Set<String> uuids = new LinkedHashSet<>();
            int rows = 0;
            while (rs.next()) {
                String u = rs.getString("uuid");
                assertNotNull("Legacy account id=" + rs.getInt("id") + " missing UUID after migration", u);
                // Validate it's a real UUID — UUID.fromString throws on malformed.
                UUID.fromString(u);
                assertTrue("Backfilled UUIDs must be unique: duplicate " + u,
                        uuids.add(u));
                rows++;
            }
            assertEquals(3, rows);
        }
    }

    @Test
    public void legacyPlayerCharactersRowsBackfilledWithUuid() throws Exception {
        // Wipe + recreate player_characters as a v4-era schema. To keep
        // the helper small, only declare the columns needed to satisfy
        // the NOT NULL/UNIQUE constraints — the migration only cares
        // about adding/backfilling uuid, not other columns.
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS player_characters");
            stmt.execute(
                "CREATE TABLE player_characters ("
                + "  id INTEGER PRIMARY KEY,"
                + "  name TEXT NOT NULL"
                + ")"
            );
            stmt.executeUpdate("INSERT INTO player_characters (id, name) VALUES (1, 'legacy_pc1')");
            stmt.executeUpdate("INSERT INTO player_characters (id, name) VALUES (2, 'legacy_pc2')");
            stmt.executeUpdate("INSERT INTO player_characters (id, name) VALUES (3, 'legacy_pc3')");
            stmt.executeUpdate("INSERT INTO player_characters (id, name) VALUES (4, 'legacy_pc4')");

            stmt.execute("DELETE FROM schema_versions");
            stmt.execute("INSERT INTO schema_versions (version) VALUES (4)");
        }

        SqliteDatabase.initWithConnection(conn);

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, uuid FROM player_characters ORDER BY id")) {
            Set<String> uuids = new HashSet<>();
            int rows = 0;
            while (rs.next()) {
                String u = rs.getString("uuid");
                assertNotNull("Legacy player_characters id=" + rs.getInt("id")
                        + " missing UUID after migration", u);
                UUID.fromString(u);
                assertTrue("Backfilled UUIDs must be unique: duplicate " + u,
                        uuids.add(u));
                rows++;
            }
            assertEquals(4, rows);
        }
    }

    @Test
    public void migrationIsIdempotent() throws Exception {
        // Running the migration twice on a fresh DB should be a no-op
        // and not blow up on duplicate UNIQUE INDEX creation.
        SqliteDatabase.initWithConnection(conn);
        SqliteDatabase.initWithConnection(conn);
        // Re-checking the schema also confirms uuid column survives.
        Set<String> cols = readColumns("accounts");
        assertTrue(cols.contains("uuid"));
        cols = readColumns("player_characters");
        assertTrue(cols.contains("uuid"));
    }

    // ---------- helpers ----------

    private Set<String> readColumns(String table) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) out.add(rs.getString("name"));
        }
        return out;
    }

    /**
     * Inserts a player_characters row populating only id/uuid/name plus
     * the absolute minimum of NOT-NULL columns. All other columns rely on
     * their CREATE TABLE DEFAULTs.
     */
    private static void insertMinimalCharacter(Connection conn, int id, String name, String uuid) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO player_characters (id, uuid, name) VALUES (?, ?, ?)")) {
            ps.setInt(1, id);
            ps.setString(2, uuid);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }
}
