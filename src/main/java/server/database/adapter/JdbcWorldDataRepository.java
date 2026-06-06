package server.database.adapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import server.database.SqlDialect;
import server.database.importer.WorldDatParser;
import server.database.port.RepositoryException;
import server.database.port.WorldDataRepository;

/**
 * JDBC adapter for {@link WorldDataRepository}. Owns all {@code world_*}
 * DDL and inserts. The two places the backends diverge — the
 * auto-increment surrogate key and the blob column type — are sourced from
 * {@link SqlDialect}, so the adapter drives SQLite, PostgreSQL and
 * MySQL/MariaDB unchanged.
 */
public final class JdbcWorldDataRepository implements WorldDataRepository {

    /** Tables probed by {@link #contains(String)} (a world may have only
     *  NPCs, or only raw blobs, so all are checked). */
    private static final String[] WORLD_PATH_TABLES = {
        "world_objects", "world_doors", "world_npcs", "world_passive_objects",
        "world_position_markers", "world_regions", "world_extras",
        "world_labeled_regions", "world_tagged_entities", "world_raw_elements"
    };

    private final Connection conn;
    private final SqlDialect dialect;

    public JdbcWorldDataRepository(Connection conn) {
        this.conn = conn;
        this.dialect = SqlDialect.of(conn);
    }

    @Override
    public void ensureSchema() {
        String idType   = dialect.autoIncrementPrimaryKey();
        String blobType = dialect.blobType();
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
            "CREATE TABLE IF NOT EXISTS world_regions ("
              + "id " + idType + ","
              + " world_path TEXT NOT NULL,"
              + " pos_x REAL, pos_y REAL, pos_z REAL,"
              + " dim1 REAL, dim2 REAL,"
              + " flag INTEGER, region_id INTEGER)",
            "CREATE INDEX IF NOT EXISTS world_regions_path_ix"
              + " ON world_regions (world_path)",
            "CREATE TABLE IF NOT EXISTS world_extras ("
              + "id " + idType + ","
              + " world_path TEXT NOT NULL,"
              + " entry_id BIGINT,"
              + " pos_x REAL, pos_y REAL, pos_z REAL,"
              + " raw " + blobType + ")",
            "CREATE INDEX IF NOT EXISTS world_extras_path_ix"
              + " ON world_extras (world_path)",
            "CREATE TABLE IF NOT EXISTS world_labeled_regions ("
              + "id " + idType + ","
              + " world_path TEXT NOT NULL,"
              + " name TEXT,"
              + " pos_x REAL, pos_y REAL, pos_z REAL,"
              + " dim1 REAL, dim2 REAL)",
            "CREATE INDEX IF NOT EXISTS world_labeled_regions_path_ix"
              + " ON world_labeled_regions (world_path)",
            "CREATE INDEX IF NOT EXISTS world_labeled_regions_name_ix"
              + " ON world_labeled_regions (name)",
            "CREATE TABLE IF NOT EXISTS world_tagged_entities ("
              + "id " + idType + ","
              + " world_path TEXT NOT NULL,"
              + " entity_id BIGINT,"
              + " counter INTEGER,"
              + " subtype INTEGER,"
              + " sub2 INTEGER,"
              + " pos_x REAL, pos_y REAL, pos_z REAL,"
              + " tail " + blobType + ")",
            "CREATE INDEX IF NOT EXISTS world_tagged_entities_path_ix"
              + " ON world_tagged_entities (world_path)",
            "CREATE INDEX IF NOT EXISTS world_tagged_entities_subtype_ix"
              + " ON world_tagged_entities (subtype)",
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
        } catch (SQLException e) {
            throw new RepositoryException("create world_* tables failed", e);
        }
    }

    @Override
    public boolean contains(String worldPath) {
        for (String t : WORLD_PATH_TABLES) {
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

    @Override
    public void insertWorld(String worldPath, WorldDatParser.ParsedWorld pw) {
        boolean prevAuto = true;
        try {
            prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                insertObjects(worldPath, pw);
                insertDoors(worldPath, pw);
                insertNpcs(worldPath, pw);
                insertPassives(worldPath, pw);
                insertMarkers(worldPath, pw);
                insertRegions(worldPath, pw);
                insertExtras(worldPath, pw);
                insertLabeledRegions(worldPath, pw);
                insertTaggedEntities(worldPath, pw);
                insertRawBlobs(worldPath, pw);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new RepositoryException("insert world " + worldPath + " failed", e);
            } finally {
                conn.setAutoCommit(prevAuto);
            }
        } catch (SQLException e) {
            throw new RepositoryException("insert world " + worldPath + " transaction error", e);
        }
    }

    private void insertObjects(String worldPath, WorldDatParser.ParsedWorld pw)
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

    private void insertDoors(String worldPath, WorldDatParser.ParsedWorld pw)
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

    private void insertNpcs(String worldPath, WorldDatParser.ParsedWorld pw)
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

    private void insertPassives(String worldPath, WorldDatParser.ParsedWorld pw)
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

    private void insertMarkers(String worldPath, WorldDatParser.ParsedWorld pw)
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

    private void insertRegions(String worldPath, WorldDatParser.ParsedWorld pw)
            throws SQLException {
        if (pw.regions.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO world_regions (world_path, pos_x, pos_y, pos_z,"
              + " dim1, dim2, flag, region_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (WorldDatParser.RegionEntry r : pw.regions) {
                ps.setString(1, worldPath);
                ps.setFloat(2, r.posX);
                ps.setFloat(3, r.posY);
                ps.setFloat(4, r.posZ);
                ps.setFloat(5, r.dim1);
                ps.setFloat(6, r.dim2);
                ps.setInt(7, r.flag);
                ps.setInt(8, r.id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertExtras(String worldPath, WorldDatParser.ParsedWorld pw)
            throws SQLException {
        if (pw.extras.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO world_extras (world_path, entry_id,"
              + " pos_x, pos_y, pos_z, raw) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (WorldDatParser.ExtraEntry e : pw.extras) {
                ps.setString(1, worldPath);
                ps.setLong(2, e.entryId & 0xffffffffL);
                ps.setFloat(3, e.posX);
                ps.setFloat(4, e.posY);
                ps.setFloat(5, e.posZ);
                ps.setBytes(6, e.raw);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertLabeledRegions(String worldPath, WorldDatParser.ParsedWorld pw)
            throws SQLException {
        if (pw.labeledRegions.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO world_labeled_regions (world_path, name,"
              + " pos_x, pos_y, pos_z, dim1, dim2)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (WorldDatParser.LabeledRegionEntry r : pw.labeledRegions) {
                ps.setString(1, worldPath);
                ps.setString(2, r.name);
                ps.setFloat(3, r.posX);
                ps.setFloat(4, r.posY);
                ps.setFloat(5, r.posZ);
                ps.setFloat(6, r.dim1);
                ps.setFloat(7, r.dim2);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertTaggedEntities(String worldPath, WorldDatParser.ParsedWorld pw)
            throws SQLException {
        if (pw.taggedEntities.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO world_tagged_entities (world_path, entity_id,"
              + " counter, subtype, sub2, pos_x, pos_y, pos_z, tail)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (WorldDatParser.TaggedEntityEntry e : pw.taggedEntities) {
                ps.setString(1, worldPath);
                ps.setLong(2, e.entityId & 0xffffffffL);
                ps.setInt(3, e.counter);
                ps.setInt(4, e.subtype);
                ps.setInt(5, e.sub2);
                ps.setFloat(6, e.posX);
                ps.setFloat(7, e.posY);
                ps.setFloat(8, e.posZ);
                ps.setBytes(9, e.tail);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertRawBlobs(String worldPath, WorldDatParser.ParsedWorld pw)
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
