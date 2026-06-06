package server.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * SQL dialect abstraction for the client-data importer track.
 *
 * <p>Historically the importers branched on a single boolean
 * ({@code SqliteDatabase.isPostgres()}), which can only express the
 * SQLite-vs-PostgreSQL split. To let the importers populate a MySQL/MariaDB
 * backend as well, the dialect is modelled as a three-way enum that knows
 * how to emit the handful of statements where the three backends actually
 * diverge:
 *
 * <ul>
 *   <li><b>Upserts</b> — SQLite {@code INSERT OR REPLACE}, PostgreSQL
 *       {@code ON CONFLICT (...) DO UPDATE}, MySQL
 *       {@code ON DUPLICATE KEY UPDATE}.</li>
 *   <li><b>Column types</b> — auto-increment primary keys, BLOB and JSON
 *       column types differ in spelling per backend.</li>
 * </ul>
 *
 * <p>Importers detect the dialect from the {@link Connection} they are
 * handed ({@link #of(Connection)}) rather than reading a global flag, so a
 * single importer instance is agnostic to how the connection was opened and
 * tests can drive any backend by passing the matching connection.
 */
public enum SqlDialect {

    SQLITE,
    POSTGRES,
    MYSQL;

    /**
     * Detect the dialect from a JDBC URL. Unknown / null URLs fall back to
     * {@link #SQLITE}, matching the importer's historical "assume SQLite
     * unless told otherwise" default. MariaDB shares MySQL's wire dialect
     * and JDBC URL scheme variants, so both map to {@link #MYSQL}.
     */
    public static SqlDialect fromUrl(String url) {
        if (url == null) return SQLITE;
        if (url.startsWith("jdbc:postgresql:")) return POSTGRES;
        if (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:")) return MYSQL;
        return SQLITE;
    }

    /**
     * Detect the dialect from a live connection's metadata URL. Any failure
     * to read the URL degrades gracefully to {@link #SQLITE}.
     */
    public static SqlDialect of(Connection conn) {
        if (conn == null) return SQLITE;
        try {
            return fromUrl(conn.getMetaData().getURL());
        } catch (SQLException e) {
            return SQLITE;
        }
    }

    public boolean isPostgres() { return this == POSTGRES; }
    public boolean isSqlite()   { return this == SQLITE; }
    public boolean isMysql()    { return this == MYSQL; }

    // ---- Column types -----------------------------------------------------

    /**
     * Column declaration for an auto-incrementing surrogate primary key
     * (used by the {@code world_*} import tables which have no natural key).
     */
    public String autoIncrementPrimaryKey() {
        switch (this) {
            case POSTGRES: return "BIGSERIAL PRIMARY KEY";
            case MYSQL:    return "BIGINT AUTO_INCREMENT PRIMARY KEY";
            case SQLITE:
            default:       return "INTEGER PRIMARY KEY AUTOINCREMENT";
        }
    }

    /** Column type for opaque binary blobs (raw element/trailer bytes). */
    public String blobType() {
        switch (this) {
            case POSTGRES: return "BYTEA";
            case MYSQL:    return "LONGBLOB";
            case SQLITE:
            default:       return "BLOB";
        }
    }

    /**
     * Column type for a JSON document. PostgreSQL and MySQL have dedicated
     * binary-JSON types; SQLite stores the serialized text.
     */
    public String jsonType() {
        switch (this) {
            case POSTGRES: return "JSONB";
            case MYSQL:    return "JSON";
            case SQLITE:
            default:       return "TEXT";
        }
    }

    /**
     * Value placeholder for binding a JSON string. PostgreSQL needs an
     * explicit {@code ?::jsonb} cast when inserting into a {@code JSONB}
     * column from a String parameter; MySQL and SQLite accept a plain
     * {@code ?} (MySQL casts the string to JSON implicitly).
     */
    public String jsonPlaceholder() {
        return this == POSTGRES ? "?::jsonb" : "?";
    }

    // ---- Upserts ----------------------------------------------------------

    /**
     * Build an INSERT-or-update statement keyed on {@code conflictColumns}.
     * Every column not in {@code conflictColumns} is overwritten with the
     * incoming value on conflict. Convenience overload that uses a plain
     * {@code ?} placeholder for every column.
     *
     * @param table           target table
     * @param columns         all inserted columns, in order
     * @param conflictColumns the unique/primary key columns to match on
     */
    public String upsert(String table, String[] columns, String[] conflictColumns) {
        String[] placeholders = new String[columns.length];
        for (int i = 0; i < placeholders.length; i++) placeholders[i] = "?";
        return upsert(table, columns, placeholders, conflictColumns);
    }

    /**
     * Build an INSERT-or-update statement with explicit per-column value
     * placeholders (e.g. {@code ?::jsonb} for a PostgreSQL JSON column).
     *
     * @param table           target table
     * @param columns         all inserted columns, in order
     * @param placeholders    value placeholder for each column (same length
     *                        and order as {@code columns})
     * @param conflictColumns the unique/primary key columns to match on
     */
    public String upsert(String table, String[] columns, String[] placeholders,
                         String[] conflictColumns) {
        if (columns.length != placeholders.length) {
            throw new IllegalArgumentException(
                "columns and placeholders length mismatch");
        }
        String colList = String.join(", ", columns);
        String valList = String.join(", ", placeholders);

        switch (this) {
            case SQLITE:
                // SQLite REPLACE semantics overwrite the whole row on a
                // primary-key/unique conflict — equivalent to a full upsert
                // for these id-keyed import tables.
                return "INSERT OR REPLACE INTO " + table + " (" + colList
                    + ") VALUES (" + valList + ")";

            case POSTGRES: {
                StringBuilder sb = new StringBuilder();
                sb.append("INSERT INTO ").append(table).append(" (").append(colList)
                  .append(") VALUES (").append(valList).append(") ON CONFLICT (")
                  .append(String.join(", ", conflictColumns)).append(") DO UPDATE SET ");
                appendAssignments(sb, columns, conflictColumns, "EXCLUDED.");
                return sb.toString();
            }

            case MYSQL:
            default: {
                StringBuilder sb = new StringBuilder();
                sb.append("INSERT INTO ").append(table).append(" (").append(colList)
                  .append(") VALUES (").append(valList).append(") ON DUPLICATE KEY UPDATE ");
                appendAssignments(sb, columns, conflictColumns, null);
                return sb.toString();
            }
        }
    }

    /**
     * Append the {@code col = <ref>col} assignment list for every column not
     * in {@code conflictColumns}. PostgreSQL references the incoming row via
     * {@code EXCLUDED.}; MySQL via {@code VALUES(col)} (passing a null prefix).
     */
    private static void appendAssignments(StringBuilder sb, String[] columns,
                                          String[] conflictColumns, String excludedPrefix) {
        boolean first = true;
        for (String col : columns) {
            if (contains(conflictColumns, col)) continue;
            if (!first) sb.append(", ");
            first = false;
            if (excludedPrefix != null) {
                sb.append(col).append(" = ").append(excludedPrefix).append(col);
            } else {
                sb.append(col).append(" = VALUES(").append(col).append(")");
            }
        }
    }

    private static boolean contains(String[] arr, String s) {
        for (String a : arr) {
            if (a.equals(s)) return true;
        }
        return false;
    }
}
