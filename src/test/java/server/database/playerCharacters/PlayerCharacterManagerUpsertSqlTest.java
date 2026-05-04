package server.database.playerCharacters;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.SqliteDatabase;

/**
 * Verifies the SQLite-vs-PostgreSQL dialect switch in
 * {@link PlayerCharacterManager#buildUpsertSql}.
 */
public class PlayerCharacterManagerUpsertSqlTest {

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
        String sql = PlayerCharacterManager.buildUpsertSql(
                Arrays.asList("id", "name", "hp"));
        assertTrue(sql.startsWith("INSERT OR REPLACE INTO player_characters"));
        assertFalse(sql.contains("ON CONFLICT"));
        assertFalse(sql.contains("EXCLUDED"));
        assertEquals(3, sql.chars().filter(c -> c == '?').count());
    }

    @Test
    public void postgresUsesOnConflict() {
        SqliteDatabase.setIsPostgresForTesting(true);
        String sql = PlayerCharacterManager.buildUpsertSql(
                Arrays.asList("id", "name", "hp", "level"));
        assertTrue(sql.startsWith("INSERT INTO player_characters"));
        assertFalse(sql.contains("INSERT OR REPLACE"));
        assertTrue(sql.contains("ON CONFLICT (id) DO UPDATE SET"));
        // The "id" column itself MUST NOT appear in the DO UPDATE
        // SET — that would be a self-assignment.
        int conflictPos = sql.indexOf("DO UPDATE SET");
        String setClause = sql.substring(conflictPos);
        assertFalse("id should not self-assign in SET: " + setClause,
                setClause.matches(".*\\bid\\s*=.*"));
    }

    @Test
    public void postgresAllNonIdColsAppearInSetClause() {
        SqliteDatabase.setIsPostgresForTesting(true);
        List<String> cols = Arrays.asList(
                "id", "name", "hp", "psi", "stamina",
                "x_coord", "y_coord", "z_coord");
        String sql = PlayerCharacterManager.buildUpsertSql(cols);
        // 8 placeholders total.
        assertEquals(cols.size(),
                sql.chars().filter(c -> c == '?').count());
        // 7 EXCLUDED references (all but id).
        long excludedCount = sql.split("EXCLUDED\\.", -1).length - 1;
        assertEquals(cols.size() - 1, excludedCount);
    }

    @Test
    public void postgresQuotesColumnsConsistently() {
        // Whatever quoting q() applies to a column, the LHS and RHS
        // of the SET clause must be identical.
        SqliteDatabase.setIsPostgresForTesting(true);
        String sql = PlayerCharacterManager.buildUpsertSql(
                Arrays.asList("id", "level"));
        int setIdx = sql.indexOf("DO UPDATE SET");
        assertTrue(setIdx > 0);
        String setClause = sql.substring(setIdx);
        // Whatever appears on either side of "= EXCLUDED.", they
        // must match.
        int eqIdx = setClause.indexOf("= EXCLUDED.");
        assertTrue(eqIdx > 0);
        String lhs = setClause.substring(
                "DO UPDATE SET ".length(), eqIdx).trim();
        String rhs = setClause.substring(eqIdx + "= EXCLUDED.".length()).trim();
        assertEquals(lhs, rhs);
    }
}
