package server.database;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import server.tools.Out;

/**
 * One-shot data migration: copy every row from a legacy SQLite file at
 * {@code ./database/ceres.db} into the active PostgreSQL connection.
 *
 * <p>Triggered automatically by {@link SqliteDatabase#init()} when:
 * <ul>
 *   <li>the active backend is PostgreSQL,</li>
 *   <li>{@code accounts}/{@code player_characters} are empty in PG,</li>
 *   <li>{@code ./database/ceres.db} exists on disk.</li>
 * </ul>
 *
 * <p>Schema is assumed to already exist in PG (created by
 * {@link SqliteDatabase#createTables()}). The migrator only copies rows.
 *
 * <p>After a successful migration the SQLite file is renamed to
 * {@code ceres.db.migrated.<timestamp>} so subsequent startups don't re-run it.
 */
public final class SqliteToPostgresMigrator {

    private static final String[] TABLES = {
        "accounts",
        "player_characters",
        "items",
        "item_containers",
        "world_defs",
        "item_defs",
        "npc_spawns",
    };

    static void migrateIfNeeded(Connection pgConn) throws SQLException {
        File sqliteFile = new File("database" + File.separator + "ceres.db");
        if (!sqliteFile.exists()) return;

        if (!pgIsEmpty(pgConn)) {
            Out.writeln(Out.Info, "Postgres already populated, skipping SQLite migration");
            return;
        }

        Out.writeln(Out.Info, "Migrating data from SQLite (" + sqliteFile.getPath() + ") to PostgreSQL...");
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            Out.writeln(Out.Error, "SQLite JDBC driver missing — skipping migration");
            return;
        }
        try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.getPath())) {
            pgConn.setAutoCommit(false);
            try {
                for (String table : TABLES) {
                    int copied = copyTable(sqlite, pgConn, table);
                    Out.writeln(Out.Info, "  migrated " + copied + " rows from " + table);
                }
                pgConn.commit();
            } catch (SQLException e) {
                pgConn.rollback();
                throw e;
            } finally {
                pgConn.setAutoCommit(true);
            }
        }

        // Rename the SQLite file so we never re-migrate it.
        File renamed = new File(sqliteFile.getParent(),
            "ceres.db.migrated." + System.currentTimeMillis());
        if (sqliteFile.renameTo(renamed)) {
            Out.writeln(Out.Info, "SQLite file moved to " + renamed.getName());
        }
        // Also drop the stale -wal / -shm files if present so a future
        // SQLite reopen would have to start fresh.
        new File(sqliteFile.getParent(), "ceres.db-wal").delete();
        new File(sqliteFile.getParent(), "ceres.db-shm").delete();
    }

    private static boolean pgIsEmpty(Connection pg) throws SQLException {
        try (Statement stmt = pg.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT (SELECT COUNT(*) FROM accounts) + "
                 + "(SELECT COUNT(*) FROM player_characters) AS total")) {
            return rs.next() && rs.getInt(1) == 0;
        }
    }

    private static int copyTable(Connection src, Connection dst, String table) throws SQLException {
        // Discover columns that exist in BOTH source and destination so
        // legacy SQLite columns dropped from PG (or vice versa) don't crash.
        Set<String> dstCols = readColumns(dst, table, true);
        if (dstCols.isEmpty()) return 0;

        try (Statement srcStmt = src.createStatement();
             ResultSet rs = srcStmt.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData meta = rs.getMetaData();
            List<String> common = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String c = meta.getColumnName(i);
                if (dstCols.contains(c.toLowerCase())) common.add(c);
            }
            if (common.isEmpty()) return 0;

            StringBuilder cols = new StringBuilder();
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < common.size(); i++) {
                if (i > 0) { cols.append(", "); placeholders.append(", "); }
                cols.append("\"").append(common.get(i)).append("\"");
                placeholders.append("?");
            }
            // ON CONFLICT DO NOTHING so a partially-migrated DB can be retried
            // without colliding on already-copied rows (e.g. world_defs may
            // have been pre-populated by ClientDataImporter before this runs).
            String sql = "INSERT INTO " + table + " (" + cols + ") VALUES ("
                + placeholders + ") ON CONFLICT DO NOTHING";
            int count = 0;
            try (PreparedStatement ps = dst.prepareStatement(sql)) {
                while (rs.next()) {
                    for (int i = 0; i < common.size(); i++) {
                        String colName = common.get(i);
                        Object val = rs.getObject(colName);
                        // SQLite stores UUIDs as TEXT but PostgreSQL has a
                        // native UUID type. The PG JDBC driver won't auto-
                        // coerce a String into a UUID column via setObject,
                        // so convert explicitly here. Tolerate malformed
                        // input by leaving such rows with a freshly-minted
                        // UUID (consistent with the migrate path).
                        if ("uuid".equalsIgnoreCase(colName)) {
                            if (val instanceof String) {
                                try {
                                    ps.setObject(i + 1, java.util.UUID.fromString((String) val));
                                } catch (IllegalArgumentException badUuid) {
                                    ps.setObject(i + 1, java.util.UUID.randomUUID());
                                }
                            } else if (val == null) {
                                ps.setObject(i + 1, java.util.UUID.randomUUID());
                            } else {
                                ps.setObject(i + 1, val);
                            }
                        } else {
                            ps.setObject(i + 1, val);
                        }
                    }
                    ps.addBatch();
                    count++;
                    if (count % 500 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            return count;
        }
    }

    private static Set<String> readColumns(Connection conn, String table, boolean lower) throws SQLException {
        Set<String> cols = new HashSet<>();
        boolean isPg = conn.getMetaData().getURL().startsWith("jdbc:postgresql:");
        if (isPg) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema = current_schema() AND table_name = ?")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) cols.add(lower ? rs.getString(1).toLowerCase() : rs.getString(1));
                }
            }
        } else {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
                while (rs.next()) cols.add(lower ? rs.getString("name").toLowerCase() : rs.getString("name"));
            }
        }
        return cols;
    }
}
