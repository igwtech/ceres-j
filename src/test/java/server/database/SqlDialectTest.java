package server.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure-string verification of {@link SqlDialect}'s dialect-specific SQL
 * generation across SQLite, PostgreSQL and MySQL. No live database is
 * needed — this is the only place MySQL output is asserted byte-for-byte,
 * since the project ships no MySQL test server.
 */
public class SqlDialectTest {

    @Test
    public void detectsFromUrl() {
        assertEquals(SqlDialect.SQLITE,   SqlDialect.fromUrl("jdbc:sqlite::memory:"));
        assertEquals(SqlDialect.POSTGRES, SqlDialect.fromUrl("jdbc:postgresql://h/db"));
        assertEquals(SqlDialect.MYSQL,    SqlDialect.fromUrl("jdbc:mysql://h/db"));
        assertEquals(SqlDialect.MYSQL,    SqlDialect.fromUrl("jdbc:mariadb://h/db"));
        // Unknown / null falls back to SQLite (historical default).
        assertEquals(SqlDialect.SQLITE,   SqlDialect.fromUrl(null));
        assertEquals(SqlDialect.SQLITE,   SqlDialect.fromUrl("jdbc:h2:mem:x"));
    }

    @Test
    public void sqliteUpsertUsesInsertOrReplace() {
        String sql = SqlDialect.SQLITE.upsert(
            "world_defs", new String[]{"id", "path", "bsp_name"}, new String[]{"id"});
        assertEquals(
            "INSERT OR REPLACE INTO world_defs (id, path, bsp_name) VALUES (?, ?, ?)",
            sql);
    }

    @Test
    public void postgresUpsertUsesOnConflict() {
        String sql = SqlDialect.POSTGRES.upsert(
            "world_defs", new String[]{"id", "path", "bsp_name"}, new String[]{"id"});
        assertEquals(
            "INSERT INTO world_defs (id, path, bsp_name) VALUES (?, ?, ?) "
            + "ON CONFLICT (id) DO UPDATE SET path = EXCLUDED.path, bsp_name = EXCLUDED.bsp_name",
            sql);
    }

    @Test
    public void mysqlUpsertUsesOnDuplicateKey() {
        String sql = SqlDialect.MYSQL.upsert(
            "world_defs", new String[]{"id", "path", "bsp_name"}, new String[]{"id"});
        assertEquals(
            "INSERT INTO world_defs (id, path, bsp_name) VALUES (?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE path = VALUES(path), bsp_name = VALUES(bsp_name)",
            sql);
    }

    @Test
    public void compositeKeyExcludesAllKeyColumnsFromUpdate() {
        String[] cols = {"def_name", "entry_id", "fields"};
        String[] key  = {"def_name", "entry_id"};

        // Only the non-key column (fields) is updated on conflict.
        assertTrue(SqlDialect.POSTGRES.upsert("client_defs", cols, key)
            .endsWith("ON CONFLICT (def_name, entry_id) DO UPDATE SET fields = EXCLUDED.fields"));
        assertTrue(SqlDialect.MYSQL.upsert("client_defs", cols, key)
            .endsWith("ON DUPLICATE KEY UPDATE fields = VALUES(fields)"));
    }

    @Test
    public void jsonPlaceholderHonoursPostgresCast() {
        String[] cols = {"def_name", "entry_id", "fields"};
        String[] key  = {"def_name", "entry_id"};
        String[] ph   = {"?", "?", "?::jsonb"};

        String pg = SqlDialect.POSTGRES.upsert("client_defs", cols, ph, key);
        assertTrue("PG should bind JSON via ?::jsonb", pg.contains("VALUES (?, ?, ?::jsonb)"));

        // MySQL/SQLite use plain ? — the JSON string is accepted directly.
        assertEquals("?", SqlDialect.MYSQL.jsonPlaceholder());
        assertEquals("?", SqlDialect.SQLITE.jsonPlaceholder());
        assertEquals("?::jsonb", SqlDialect.POSTGRES.jsonPlaceholder());
    }

    @Test
    public void columnTypesPerDialect() {
        assertEquals("INTEGER PRIMARY KEY AUTOINCREMENT", SqlDialect.SQLITE.autoIncrementPrimaryKey());
        assertEquals("BIGSERIAL PRIMARY KEY",             SqlDialect.POSTGRES.autoIncrementPrimaryKey());
        assertEquals("BIGINT AUTO_INCREMENT PRIMARY KEY", SqlDialect.MYSQL.autoIncrementPrimaryKey());

        assertEquals("BLOB",     SqlDialect.SQLITE.blobType());
        assertEquals("BYTEA",    SqlDialect.POSTGRES.blobType());
        assertEquals("LONGBLOB", SqlDialect.MYSQL.blobType());

        assertEquals("TEXT",  SqlDialect.SQLITE.jsonType());
        assertEquals("JSONB", SqlDialect.POSTGRES.jsonType());
        assertEquals("JSON",  SqlDialect.MYSQL.jsonType());
    }
}
