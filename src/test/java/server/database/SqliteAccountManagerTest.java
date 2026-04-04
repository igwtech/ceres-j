package server.database;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.accounts.Account;
import server.database.accounts.AccountManager;

/**
 * Tests for AccountManager backed by SQLite.
 * Uses an in-memory SQLite database for isolation.
 */
public class SqliteAccountManagerTest {

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        // Use in-memory SQLite for test isolation
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        SqliteDatabase.initWithConnection(conn);

        // Insert test accounts directly
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "INSERT INTO accounts (id, username, password, char1, char2, char3, char4, status) " +
                "VALUES (1, 'testuser', 'testpass', 0, 0, 0, 0, '')"
            );
            stmt.executeUpdate(
                "INSERT INTO accounts (id, username, password, char1, char2, char3, char4, status) " +
                "VALUES (2, 'admin', 'adminpass', 10, 20, 0, 0, 'admin')"
            );
            stmt.executeUpdate(
                "INSERT INTO accounts (id, username, password, char1, char2, char3, char4, status) " +
                "VALUES (3, 'banned', 'bannedpass', 0, 0, 0, 0, 'banned')"
            );
        }

        // Load accounts from SQLite into memory
        AccountManager.load();
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    @Test
    public void testLoadAccounts() throws Exception {
        // Verify accounts were loaded from SQLite
        AccountManager.save();

        // Verify data in SQLite
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM accounts")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
        }
    }

    @Test
    public void testSaveAndReloadAccounts() throws Exception {
        // Save current accounts to SQLite
        AccountManager.save();

        // Verify the accounts persist in SQLite
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT username, password, char1, char2 FROM accounts WHERE id = 2")) {
            assertTrue(rs.next());
            assertEquals("admin", rs.getString("username"));
            assertEquals("adminpass", rs.getString("password"));
            assertEquals(10, rs.getInt("char1"));
            assertEquals(20, rs.getInt("char2"));
        }

        // Reload from SQLite and verify
        AccountManager.load();
        AccountManager.save();

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM accounts")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
        }
    }

    @Test
    public void testCreateAccountDirectly() throws Exception {
        // Create a new account via SQL, then reload
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "INSERT INTO accounts (id, username, password, char1, char2, char3, char4, status) " +
                "VALUES (100, 'newuser', 'newpass', 0, 0, 0, 0, '')"
            );
        }

        AccountManager.load();
        AccountManager.save();

        // Verify it round-trips
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT username FROM accounts WHERE id = 100")) {
            assertTrue(rs.next());
            assertEquals("newuser", rs.getString("username"));
        }
    }

    @Test
    public void testDuplicateUsernameRejectedBySqlite() throws Exception {
        // SQLite UNIQUE constraint on username should prevent duplicates
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "INSERT INTO accounts (id, username, password, char1, char2, char3, char4, status) " +
                "VALUES (99, 'testuser', 'otherpass', 0, 0, 0, 0, '')"
            );
            fail("Should have thrown SQLException for duplicate username");
        } catch (java.sql.SQLException e) {
            // Expected: UNIQUE constraint violation
            assertTrue(e.getMessage().contains("UNIQUE") || e.getMessage().contains("unique"));
        }
    }

    @Test
    public void testSaveAccountImmediate() throws Exception {
        // Create an account object and save it immediately
        Account ua = new Account(50);
        ua.setUsername("immediate_user");
        ua.setPassword("immediate_pass");
        ua.setChar(0, 5);
        ua.setChar(1, 10);
        ua.setChar(2, 0);
        ua.setChar(3, 0);
        ua.setStatus("admin");

        AccountManager.saveAccount(ua);

        // Verify it was persisted
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM accounts WHERE id = 50")) {
            assertTrue(rs.next());
            assertEquals("immediate_user", rs.getString("username"));
            assertEquals("immediate_pass", rs.getString("password"));
            assertEquals(5, rs.getInt("char1"));
            assertEquals(10, rs.getInt("char2"));
            assertEquals("admin", rs.getString("status"));
        }
    }

    @Test
    public void testAccountStatusRoundTrip() throws Exception {
        // Verify that status strings survive save/reload
        AccountManager.save();

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT status FROM accounts WHERE username = 'admin'")) {
            assertTrue(rs.next());
            assertEquals("admin", rs.getString("status"));
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT status FROM accounts WHERE username = 'banned'")) {
            assertTrue(rs.next());
            assertEquals("banned", rs.getString("status"));
        }
    }

    @Test
    public void testAccountCharacterSlots() throws Exception {
        // Verify character slot IDs round-trip correctly
        Account ua = new Account(60);
        ua.setUsername("slottest");
        ua.setPassword("pass");
        ua.setChar(0, 100);
        ua.setChar(1, 200);
        ua.setChar(2, 300);
        ua.setChar(3, 400);
        ua.setStatus("");

        AccountManager.saveAccount(ua);

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT char1, char2, char3, char4 FROM accounts WHERE id = 60")) {
            assertTrue(rs.next());
            assertEquals(100, rs.getInt("char1"));
            assertEquals(200, rs.getInt("char2"));
            assertEquals(300, rs.getInt("char3"));
            assertEquals(400, rs.getInt("char4"));
        }
    }
}
