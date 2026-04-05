package server.database.importer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import server.tools.Out;
import server.tools.VirtualFileSystem;

/**
 * One-shot importer that populates the SQLite {@code world_defs} (and,
 * eventually, {@code item_defs}) tables from the NC2 client's
 * PAK-extracted resource files.
 *
 * <p>Safe to call unconditionally on every server startup: it detects
 * already-populated tables and skips. If the client is not mounted (no
 * {@link VirtualFileSystem} hit), it logs a warning and returns cleanly —
 * the server must boot on a fresh install without a client.
 */
public final class ClientDataImporter {

    /** Path in the NC2 client VFS where worlds.ini lives. */
    public static final String WORLDS_INI_VFS_PATH = "worlds\\worlds.ini";

    private ClientDataImporter() {
        // static utility
    }

    /**
     * Entry point wired into {@code SqliteDatabase.init()}.
     * Runs the import only when {@code world_defs} is empty.
     */
    public static void runIfNeeded(Connection conn) {
        if (conn == null) {
            Out.writeln(Out.Warning, "ClientDataImporter: no DB connection, skipping");
            return;
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM world_defs")) {
            if (rs.next() && rs.getInt(1) > 0) {
                Out.writeln(Out.Info, "World defs already populated, skipping import");
                return;
            }
        } catch (SQLException e) {
            Out.writeln(Out.Error, "ClientDataImporter: failed to probe world_defs: " + e.getMessage());
            return;
        }

        InputStream in;
        try {
            in = VirtualFileSystem.getFileInputStream(WORLDS_INI_VFS_PATH);
        } catch (RuntimeException e) {
            // VirtualFileSystem reads Config.getProperty eagerly; if
            // Config has not been initialised (test path, or fresh
            // install with no ceres.cfg) treat it as no client mounted.
            Out.writeln(Out.Warning,
                "ClientDataImporter: VirtualFileSystem unavailable ("
                + e.getClass().getSimpleName() + "); skipping world import");
            return;
        }
        if (in == null) {
            Out.writeln(Out.Warning,
                "ClientDataImporter: " + WORLDS_INI_VFS_PATH
                + " not found in client VFS; skipping world import (fresh install?)");
            return;
        }

        runIfNeeded(conn, in);
    }

    /**
     * Package-private overload that accepts a pre-opened InputStream.
     * Used by tests to inject fixture content without depending on the
     * {@link VirtualFileSystem} mount.
     */
    static void runIfNeeded(Connection conn, InputStream worldsIni) {
        // Re-check world_defs so this overload is also idempotent.
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM world_defs")) {
            if (rs.next() && rs.getInt(1) > 0) {
                Out.writeln(Out.Info, "World defs already populated, skipping import");
                try { worldsIni.close(); } catch (IOException ignore) { /* best effort */ }
                return;
            }
        } catch (SQLException e) {
            Out.writeln(Out.Error, "ClientDataImporter: failed to probe world_defs: " + e.getMessage());
            try { worldsIni.close(); } catch (IOException ignore) { /* best effort */ }
            return;
        }

        List<WorldsIniParser.Entry> entries;
        try {
            entries = WorldsIniParser.parse(worldsIni);
        } catch (RuntimeException e) {
            Out.writeln(Out.Error, "ClientDataImporter: failed to parse worlds.ini: " + e.getMessage());
            return;
        }

        if (entries.isEmpty()) {
            Out.writeln(Out.Warning, "ClientDataImporter: parsed 0 world entries; nothing to import");
            return;
        }

        String sql = "INSERT OR REPLACE INTO world_defs (id, path, bsp_name) VALUES (?, ?, ?)";
        boolean prevAutoCommit = true;
        try {
            prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (WorldsIniParser.Entry e : entries) {
                    ps.setInt(1, e.id);
                    ps.setString(2, e.path);
                    ps.setString(3, e.bspName);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                Out.writeln(Out.Error, "ClientDataImporter: world_defs insert failed: " + e.getMessage());
                return;
            } finally {
                conn.setAutoCommit(prevAutoCommit);
            }
        } catch (SQLException e) {
            Out.writeln(Out.Error, "ClientDataImporter: transaction error: " + e.getMessage());
            return;
        }

        Out.writeln(Out.Info, "ClientDataImporter: imported " + entries.size() + " world defs");
    }

    /**
     * Manual re-run entry point. Not wired into pom.xml; invoke via
     * {@code java -cp ... server.database.importer.ClientDataImporter <client-path>}.
     * This is intended for future maintenance (re-importing after a client
     * upgrade without a full server restart).
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ClientDataImporter <nc2-client-path>");
            System.exit(2);
        }

        File iniFile = new File(args[0]
            + File.separator + "worlds"
            + File.separator + "worlds.ini");
        if (!iniFile.canRead()) {
            System.err.println("Cannot read " + iniFile.getAbsolutePath());
            System.exit(3);
        }

        Class.forName("org.sqlite.JDBC");
        String dbPath = "database" + File.separator + "ceres.db";
        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             FileInputStream fis = new FileInputStream(iniFile)) {
            // Force re-import: clear the table first.
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM world_defs");
            }
            runIfNeeded(conn, fis);
        }
    }
}
