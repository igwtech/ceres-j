package server.database.accounts;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.SqliteDatabase;

/**
 * Verifies the SQLite-vs-PostgreSQL dialect switch in
 * {@link AccountManager#upsertSql()}. Production bug surfaced in
 * live logs where the SQLite-only {@code INSERT OR REPLACE} syntax
 * was emitted against PostgreSQL — fix uses {@code ON CONFLICT (id)
 * DO UPDATE SET ...} on PG.
 */
public class AccountManagerUpsertSqlTest {

    private boolean prevPostgres;

    @Before
    public void setUp() {
        prevPostgres = SqliteDatabase.isPostgres();
    }

    @After
    public void tearDown() {
        SqliteDatabase.setIsPostgresForTesting(prevPostgres);
    }

    @Test
    public void sqliteUsesInsertOrReplace() {
        SqliteDatabase.setIsPostgresForTesting(false);
        String sql = AccountManager.upsertSql();
        assertTrue(sql, sql.startsWith("INSERT OR REPLACE INTO accounts"));
        assertFalse("SQLite path must not have ON CONFLICT",
                sql.contains("ON CONFLICT"));
        assertEquals(8, sql.chars().filter(c -> c == '?').count());
    }

    @Test
    public void postgresUsesOnConflict() {
        SqliteDatabase.setIsPostgresForTesting(true);
        String sql = AccountManager.upsertSql();
        assertTrue(sql, sql.startsWith("INSERT INTO accounts"));
        assertFalse("PostgreSQL path must NOT have INSERT OR REPLACE",
                sql.contains("INSERT OR REPLACE"));
        assertTrue("PostgreSQL path needs ON CONFLICT",
                sql.contains("ON CONFLICT (id) DO UPDATE SET"));
        for (String col : new String[]{"username", "password", "char1",
                "char2", "char3", "char4", "status"}) {
            assertTrue(col + " update missing: " + sql,
                    sql.contains(col + " = EXCLUDED." + col));
        }
        assertEquals(8, sql.chars().filter(c -> c == '?').count());
    }

    @Test
    public void postgresExcludesIdFromSetClause() {
        SqliteDatabase.setIsPostgresForTesting(true);
        String sql = AccountManager.upsertSql();
        int conflictPos = sql.indexOf("DO UPDATE SET");
        String setClause = sql.substring(conflictPos);
        assertFalse("id should not self-assign in SET: " + setClause,
                setClause.matches(".*\\bid\\s*=.*"));
    }
}
