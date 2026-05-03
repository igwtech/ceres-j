package server.database.importer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import server.database.SqliteDatabase;
import server.tools.Config;
import server.tools.Out;

/**
 * Walks the NC2 client's {@code worlds/} (and optionally
 * {@code terrain/}) tree, parses every {@code .dat} and {@code .bsp}
 * file via {@link WorldDatParser}, and writes the structured records
 * into the {@code world_objects}, {@code world_doors},
 * {@code world_npcs}, {@code world_npc_waypoints} and
 * {@code world_raw_elements} tables.
 *
 * <p>Idempotent on a per-{@code world_path} basis: each file's
 * {@code world_path} key (e.g. {@code worlds/citysewer/peppersewer_1a})
 * is checked for an existing row count before processing. Re-running
 * is therefore cheap.
 *
 * <p>Like {@link ClientDataImporter} and {@link DefImporter}, the
 * importer is safe to invoke on every server boot: missing client
 * roots, unreadable files, and parse errors are logged but never
 * thrown — the server must boot on a fresh install with no client
 * mounted.
 *
 * <p>Limitations (deliberately scoped):
 * <ul>
 *   <li>Operates on already-extracted {@code .dat}/{@code .bsp}
 *       files. Reading directly from {@code worlds.pak} is not
 *       implemented; the launcher extracts archives on first run.</li>
 *   <li>Element types other than {@code 1000003} (object),
 *       {@code 1000005} (door), and {@code 1000006} (NPC) are stored
 *       as raw blobs in {@code world_raw_elements} so future RE
 *       passes can decode them without re-parsing.</li>
 * </ul>
 */
public final class WorldDatImporter {

    /** Default directories under {@code NC2ClientPath} we walk. */
    public static final String[] DEFAULT_ROOTS = {"worlds", "terrain"};

    private WorldDatImporter() {}

    /**
     * Entry point: ensure tables exist, walk the configured client
     * root, import any new files. Cheap to call on every boot.
     */
    public static void runIfNeeded(Connection conn) {
        if (conn == null) {
            Out.writeln(Out.Warning, "WorldDatImporter: no DB connection, skipping");
            return;
        }
        String clientPath;
        try {
            clientPath = Config.getProperty("NC2ClientPath");
        } catch (RuntimeException e) {
            Out.writeln(Out.Warning,
                "WorldDatImporter: Config.NC2ClientPath unavailable; skipping");
            return;
        }
        if (clientPath == null || clientPath.isEmpty()) {
            Out.writeln(Out.Warning,
                "WorldDatImporter: NC2ClientPath empty; skipping");
            return;
        }
        File root = new File(clientPath);
        if (!root.isDirectory()) {
            Out.writeln(Out.Warning,
                "WorldDatImporter: " + clientPath
                + " is not a directory; skipping");
            return;
        }
        runForRoot(conn, root, DEFAULT_ROOTS);
    }

    /** Walk a specific client root directory. Public for tests / CLI. */
    public static void runForRoot(Connection conn, File clientRoot,
                                   String[] subdirs) {
        if (!ensureTables(conn)) return;

        int filesScanned = 0;
        int filesImported = 0;
        int totalObjects = 0, totalDoors = 0, totalNpcs = 0;

        for (String sub : subdirs) {
            File dir = new File(clientRoot, sub);
            if (!dir.isDirectory()) continue;
            List<File> files = listDatFiles(dir);
            for (File f : files) {
                filesScanned++;
                String worldPath = relativePath(clientRoot, f);
                if (alreadyImported(conn, worldPath)) continue;

                byte[] raw;
                try {
                    raw = Files.readAllBytes(f.toPath());
                } catch (IOException e) {
                    Out.writeln(Out.Warning,
                        "WorldDatImporter: read failed " + f
                        + ": " + e.getMessage());
                    continue;
                }
                WorldDatParser.ParsedWorld pw;
                try {
                    pw = WorldDatParser.parse(raw);
                } catch (WorldDatParser.ParseException e) {
                    Out.writeln(Out.Warning,
                        "WorldDatImporter: parse failed " + f
                        + ": " + e.getMessage());
                    continue;
                }
                try {
                    insertWorld(conn, worldPath, pw);
                    filesImported++;
                    totalObjects += pw.objects.size();
                    totalDoors   += pw.doors.size();
                    totalNpcs    += pw.npcs.size();
                } catch (SQLException e) {
                    Out.writeln(Out.Error,
                        "WorldDatImporter: insert failed " + worldPath
                        + ": " + e.getMessage());
                }
            }
        }
        Out.writeln(Out.Info,
            "WorldDatImporter: scanned " + filesScanned + " files, imported "
            + filesImported + " (" + totalObjects + " objects, "
            + totalDoors + " doors, " + totalNpcs + " NPCs)");
    }

    /** Lex-sorted .dat + .bsp files under {@code dir}, recursive. */
    static List<File> listDatFiles(File dir) {
        List<File> out = new ArrayList<>();
        try {
            Files.walk(dir.toPath())
                .filter(Files::isRegularFile)
                .forEach((Path p) -> {
                    String n = p.getFileName().toString().toLowerCase();
                    if (n.endsWith(".dat") || n.endsWith(".bsp")) {
                        out.add(p.toFile());
                    }
                });
        } catch (IOException e) {
            Out.writeln(Out.Warning,
                "WorldDatImporter: walk failed " + dir + ": " + e.getMessage());
        }
        out.sort((a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));
        return out;
    }

    /** Build a forward-slash relative path like
     *  {@code worlds/citysewer/peppersewer_1a.dat}. */
    static String relativePath(File root, File child) {
        String r = root.getAbsolutePath();
        String c = child.getAbsolutePath();
        if (c.startsWith(r)) {
            String rel = c.substring(r.length());
            if (rel.startsWith(File.separator)) rel = rel.substring(1);
            return rel.replace(File.separatorChar, '/');
        }
        return c.replace(File.separatorChar, '/');
    }

    /** Check {@code world_objects} (the most-populated table) for a
     *  world_path row count. Treats any positive count as "imported". */
    static boolean alreadyImported(Connection conn, String worldPath) {
        // Probe across all four tables: a world with zero objects
        // could legitimately have only NPCs or only raw blobs.
        String[] tables = {"world_objects", "world_doors",
                           "world_npcs", "world_passive_objects",
                           "world_position_markers",
                           "world_raw_elements"};
        for (String t : tables) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM " + t + " WHERE world_path = ? LIMIT 1")) {
                ps.setString(1, worldPath);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return true;
                }
            } catch (SQLException ignore) {
                // table may not exist yet on first run
            }
        }
        return false;
    }

    private static boolean ensureTables(Connection conn) {
        boolean isPostgres = SqliteDatabase.isPostgres();
        String idType  = isPostgres ? "BIGSERIAL PRIMARY KEY"
                                    : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String blobType = isPostgres ? "BYTEA" : "BLOB";
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS world_objects ("
              + "id " + idType + ","
              + " world_path TEXT NOT NULL,"
              + " object_id BIGINT,"
              + " worldmodel_id INTEGER,"
              + " model_id INTEGER,"
              + " pos_x REAL, pos_y REAL, pos_z REAL,"
              + " rot_x REAL, rot_y REAL, rot_z REAL,"
              + " scale REAL,"
              + " has_bbox SMALLINT,"
              + " bbox_lower_x REAL, bbox_lower_y REAL, bbox_lower_z REAL,"
              + " bbox_upper_x REAL, bbox_upper_y REAL, bbox_upper_z REAL)",
            "CREATE INDEX IF NOT EXISTS world_objects_path_ix"
              + " ON world_objects (world_path)",
            "CREATE TABLE IF NOT EXISTS world_doors ("
              + "id " + idType + ","
              + " world_path TEXT NOT NULL,"
              + " door_id INTEGER,"
              + " worldmodel_id INTEGER,"
              + " pos_x REAL, pos_y REAL, pos_z REAL,"
              + " actor_type TEXT,"
              + " params TEXT)",
            "CREATE INDEX IF NOT EXISTS world_doors_path_ix"
              + " ON world_doors (world_path)",
            "CREATE TABLE IF NOT EXISTS world_passive_objects ("
              + "id " + idType + ","
              + " world_path TEXT NOT NULL,"
              + " entry_id BIGINT,"
              + " worldmodel_id INTEGER,"
              + " pos_x REAL, pos_y REAL, pos_z REAL,"
              + " raw " + blobType + ")",
            "CREATE INDEX IF NOT EXISTS world_passive_objects_path_ix"
              + " ON world_passive_objects (world_path)",
            "CREATE TABLE IF NOT EXISTS world_position_markers ("
              + "id " + idType + ","
              + " world_path TEXT NOT NULL,"
              + " element_type INTEGER NOT NULL,"
              + " pos_x REAL, pos_y REAL, pos_z REAL,"
              + " trailer " + blobType + ")",
            "CREATE INDEX IF NOT EXISTS world_position_markers_path_ix"
              + " ON world_position_markers (world_path)",
            "CREATE INDEX IF NOT EXISTS world_position_markers_type_ix"
              + " ON world_position_markers (element_type)",
            "CREATE TABLE IF NOT EXISTS world_npcs ("
              + "id " + idType + ","
              + " world_path TEXT NOT NULL,"
              + " npc_id INTEGER,"
              + " npc_type_id INTEGER,"
              + " trade_id INTEGER,"
              + " pos_x REAL, pos_y REAL, pos_z REAL,"
              + " actor_name TEXT,"
              + " angle TEXT,"
              + " has_waypoints SMALLINT)",
            "CREATE INDEX IF NOT EXISTS world_npcs_path_ix"
              + " ON world_npcs (world_path)",
            "CREATE TABLE IF NOT EXISTS world_npc_waypoints ("
              + " npc_row_id BIGINT NOT NULL,"
              + " idx INTEGER NOT NULL,"
              + " pos_x REAL, pos_y REAL, pos_z REAL,"
              + " PRIMARY KEY (npc_row_id, idx))",
            "CREATE TABLE IF NOT EXISTS world_raw_elements ("
              + "id " + idType + ","
              + " world_path TEXT NOT NULL,"
              + " section_id INTEGER,"
              + " element_type INTEGER,"
              + " data_blob " + blobType + ")",
            "CREATE INDEX IF NOT EXISTS world_raw_elements_type_ix"
              + " ON world_raw_elements (element_type)"
        };
        try (Statement st = conn.createStatement()) {
            for (String sql : ddl) st.execute(sql);
            return true;
        } catch (SQLException e) {
            Out.writeln(Out.Error,
                "WorldDatImporter: create table failed: " + e.getMessage());
            return false;
        }
    }

    static void insertWorld(Connection conn, String worldPath,
                             WorldDatParser.ParsedWorld pw) throws SQLException {
        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            insertObjects(conn, worldPath, pw);
            insertDoors(conn, worldPath, pw);
            insertNpcs(conn, worldPath, pw);
            insertPassives(conn, worldPath, pw);
            insertMarkers(conn, worldPath, pw);
            insertRawBlobs(conn, worldPath, pw);
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prevAuto);
        }
    }

    private static void insertObjects(Connection conn, String worldPath,
                                       WorldDatParser.ParsedWorld pw)
            throws SQLException {
        if (pw.objects.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO world_objects (world_path, object_id,"
              + " worldmodel_id, model_id,"
              + " pos_x, pos_y, pos_z, rot_x, rot_y, rot_z, scale,"
              + " has_bbox,"
              + " bbox_lower_x, bbox_lower_y, bbox_lower_z,"
              + " bbox_upper_x, bbox_upper_y, bbox_upper_z)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
              + " ?, ?, ?, ?, ?, ?)")) {
            for (WorldDatParser.ObjectEntry o : pw.objects) {
                ps.setString(1, worldPath);
                ps.setLong(2, o.objectId & 0xffffffffL);
                ps.setInt(3, o.worldmodelId);
                ps.setInt(4, o.modelId);
                ps.setFloat(5, o.posX);
                ps.setFloat(6, o.posY);
                ps.setFloat(7, o.posZ);
                ps.setFloat(8, o.rotX);
                ps.setFloat(9, o.rotY);
                ps.setFloat(10, o.rotZ);
                ps.setFloat(11, o.scale);
                ps.setShort(12, (short)(o.hasBbox ? 1 : 0));
                ps.setFloat(13, o.bboxLowerX);
                ps.setFloat(14, o.bboxLowerY);
                ps.setFloat(15, o.bboxLowerZ);
                ps.setFloat(16, o.bboxUpperX);
                ps.setFloat(17, o.bboxUpperY);
                ps.setFloat(18, o.bboxUpperZ);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void insertDoors(Connection conn, String worldPath,
                                     WorldDatParser.ParsedWorld pw)
            throws SQLException {
        if (pw.doors.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO world_doors (world_path, door_id,"
              + " worldmodel_id, pos_x, pos_y, pos_z,"
              + " actor_type, params) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (WorldDatParser.DoorEntry d : pw.doors) {
                ps.setString(1, worldPath);
                ps.setInt(2, d.doorId);
                ps.setInt(3, d.worldmodelId);
                ps.setFloat(4, d.posX);
                ps.setFloat(5, d.posY);
                ps.setFloat(6, d.posZ);
                ps.setString(7, d.actorType);
                ps.setString(8, d.params);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void insertNpcs(Connection conn, String worldPath,
                                    WorldDatParser.ParsedWorld pw)
            throws SQLException {
        if (pw.npcs.isEmpty()) return;
        // Two-step: insert npc, then waypoints with the generated id.
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO world_npcs (world_path, npc_id,"
              + " npc_type_id, trade_id, pos_x, pos_y, pos_z,"
              + " actor_name, angle, has_waypoints)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            for (WorldDatParser.NpcEntry n : pw.npcs) {
                ps.setString(1, worldPath);
                ps.setInt(2, n.npcId);
                ps.setInt(3, n.npcTypeId);
                ps.setInt(4, n.tradeId);
                ps.setFloat(5, n.posX);
                ps.setFloat(6, n.posY);
                ps.setFloat(7, n.posZ);
                ps.setString(8, n.actorName);
                ps.setString(9, n.angle);
                ps.setShort(10, (short)(n.waypoints.isEmpty() ? 0 : 1));
                ps.executeUpdate();
                if (n.waypoints.isEmpty()) continue;
                long id;
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) continue;
                    id = keys.getLong(1);
                }
                try (PreparedStatement wp = conn.prepareStatement(
                        "INSERT INTO world_npc_waypoints (npc_row_id,"
                      + " idx, pos_x, pos_y, pos_z) VALUES (?, ?, ?, ?, ?)")) {
                    for (int i = 0; i < n.waypoints.size(); i++) {
                        WorldDatParser.NpcWaypoint w = n.waypoints.get(i);
                        wp.setLong(1, id);
                        wp.setInt(2, i);
                        wp.setFloat(3, w.posX);
                        wp.setFloat(4, w.posY);
                        wp.setFloat(5, w.posZ);
                        wp.addBatch();
                    }
                    wp.executeBatch();
                }
            }
        }
    }

    private static void insertPassives(Connection conn, String worldPath,
                                        WorldDatParser.ParsedWorld pw)
            throws SQLException {
        if (pw.passives.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO world_passive_objects (world_path, entry_id,"
              + " worldmodel_id, pos_x, pos_y, pos_z, raw)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (WorldDatParser.PassiveEntry p : pw.passives) {
                ps.setString(1, worldPath);
                ps.setLong(2, p.entryId & 0xffffffffL);
                ps.setInt(3, p.worldmodelId);
                ps.setFloat(4, p.posX);
                ps.setFloat(5, p.posY);
                ps.setFloat(6, p.posZ);
                ps.setBytes(7, p.raw);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void insertMarkers(Connection conn, String worldPath,
                                       WorldDatParser.ParsedWorld pw)
            throws SQLException {
        if (pw.markers.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO world_position_markers (world_path,"
              + " element_type, pos_x, pos_y, pos_z, trailer)"
              + " VALUES (?, ?, ?, ?, ?, ?)")) {
            for (WorldDatParser.PositionMarker m : pw.markers) {
                ps.setString(1, worldPath);
                ps.setInt(2, m.elementType);
                ps.setFloat(3, m.posX);
                ps.setFloat(4, m.posY);
                ps.setFloat(5, m.posZ);
                ps.setBytes(6, m.trailer);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void insertRawBlobs(Connection conn, String worldPath,
                                        WorldDatParser.ParsedWorld pw)
            throws SQLException {
        if (pw.rawBlobs.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO world_raw_elements (world_path, section_id,"
              + " element_type, data_blob) VALUES (?, ?, ?, ?)")) {
            for (WorldDatParser.RawBlob b : pw.rawBlobs) {
                ps.setString(1, worldPath);
                ps.setInt(2, b.sectionId);
                ps.setInt(3, b.elementType);
                ps.setBytes(4, b.data);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
