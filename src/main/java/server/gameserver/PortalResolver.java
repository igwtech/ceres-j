package server.gameserver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import server.database.SqliteDatabase;
import server.tools.Out;

/**
 * Resolves a Neocron 2 furniture/portal "world-change actor" to its
 * destination zone, mirroring the TinNS NC1 emulator's 2-table
 * indirection (the identical {@code .dat}/world-data format).
 *
 * <p>Mechanism (see {@code ceres-j/docs/zone_portal_params.md} and
 * {@code tinns/.../decoder/UdpUseObject.cxx:341-475}):
 *
 * <pre>
 *   world_objects(world_path, object_id).worldmodel_id
 *      ──► worldmodel.def[worldmodel_id] → (functionType f2, functionValue f3)
 *           if functionType ∈ {15,18,20,29} → zone-change actor
 *      ──► appplaces.def[functionValue]    → (ExitWorldID f1,
 *                                              ExitWorldEntity f2,
 *                                              SewerLevel f3)
 * </pre>
 *
 * <p>{@code worldmodel.def} and {@code appplaces.def} are imported by
 * {@link server.database.importer.DefImporter} into the generic
 * {@code client_defs} table (already in {@code CORE_DEFS}); the data
 * fields are a JSON blob keyed by {@code (def_name, entry_id)}. We
 * read the raw {@code fields} column as text and parse it with Gson so
 * the same code works on the Postgres ({@code JSONB}) and SQLite
 * ({@code TEXT}) backends.
 *
 * <p><strong>SewerLevel / entityType byte semantics.</strong> The
 * doc (§2e) names the byte after {@code 0x04} "entityType"; TinNS
 * {@code BuildChangeLocationMsg} names the parameter
 * {@code nEntityType} but the {@code case 18/20/29} call site passes
 * {@code SewerLevel} computed as
 * {@code (functionType==20 || functionType==29) ? 1 : 0} — it does
 * <em>NOT</em> pass the appplaces {@code SewerLevel} field (that
 * field is only used for TinNS debug logging). Per the task
 * constraint we follow the TinNS <em>source</em>: the wire byte is
 * the function-type-derived flag, not the appplaces SewerLevel.
 * {@code case 15} (HOLOMATCH EXIT) uses {@code 0}.
 */
public final class PortalResolver {

    private PortalResolver() {}

    /** Function types in worldmodel.def that mark a zone-change
     *  actor (TinNS UdpUseObject.cxx: 15 HOLOMATCH EXIT,
     *  18 WORLDCHANGEACTOR, 20 DATFILE WORLDCHANGE ACTOR,
     *  29 Underground Exit). */
    public static boolean isZoneChangeFunctionType(int ft) {
        return ft == 15 || ft == 18 || ft == 20 || ft == 29;
    }

    /** Immutable resolution result. */
    public static final class Portal {
        public final int worldmodelId;
        public final int functionType;
        public final int functionValue;
        public final int exitWorldId;     // appplaces f1 → MISC_LOCATION
        public final int exitWorldEntity; // appplaces f2 → wire Entity
        public final int sewerLevelField; // appplaces f3 (debug only)
        /** Wire byte after 0x04. TinNS:
         *  {@code (ft==20||ft==29)?1:0} (HOLOMATCH EXIT = 0). */
        public final int entityTypeByte;

        Portal(int worldmodelId, int functionType, int functionValue,
               int exitWorldId, int exitWorldEntity,
               int sewerLevelField) {
            this.worldmodelId = worldmodelId;
            this.functionType = functionType;
            this.functionValue = functionValue;
            this.exitWorldId = exitWorldId;
            this.exitWorldEntity = exitWorldEntity;
            this.sewerLevelField = sewerLevelField;
            this.entityTypeByte =
                    (functionType == 20 || functionType == 29) ? 1 : 0;
        }

        @Override
        public String toString() {
            return "Portal{wm=" + worldmodelId + " ft=" + functionType
                    + " fval=" + functionValue + " dest=" + exitWorldId
                    + " entity=" + exitWorldEntity
                    + " entityType=" + entityTypeByte
                    + " (appplaces.SewerLevel=" + sewerLevelField + ")}";
        }
    }

    /**
     * Convert a {@link server.database.worlds.World} dotted path
     * (e.g. {@code pepper/pepper_p3}, as returned by
     * {@code Zone.getWorldname()}) to the {@code world_objects}
     * {@code world_path} key (e.g.
     * {@code worlds/pepper/pak_pepper_p3.dat}).
     *
     * <p>Verified against the live DB for all city source zones
     * (plaza p1-p4 worldId 1/2/101/102, pepper p1-p3 worldId
     * 5/6/7) and citysewer. Returns {@code null} if the dotted
     * path is not {@code <dir>/<base>}.
     */
    public static String worldnameToObjectPath(String worldname) {
        if (worldname == null) {
            return null;
        }
        int slash = worldname.indexOf('/');
        if (slash <= 0 || slash >= worldname.length() - 1) {
            return null;
        }
        String dir = worldname.substring(0, slash);
        String base = worldname.substring(slash + 1);
        return "worlds/" + dir + "/pak_" + base + ".dat";
    }

    /**
     * Resolve the world-change actor at {@code objectId} in the zone
     * whose {@code world_objects.world_path} is {@code worldPath}.
     *
     * @return the resolved {@link Portal}, or {@code null} if there
     *         is no such object, no worldmodel.def entry, the
     *         function type is not a zone-change type, or the
     *         appplaces.def lookup fails.
     */
    public static Portal resolve(String worldPath, int objectId) {
        if (worldPath == null) {
            return null;
        }
        Connection conn = SqliteDatabase.getConnection();
        if (conn == null) {
            return null;
        }

        Integer worldmodelId =
                lookupWorldmodelId(conn, worldPath, objectId);
        if (worldmodelId == null) {
            return null;
        }

        JsonObject wm =
                lookupDefFields(conn, "worldmodel", worldmodelId);
        if (wm == null) {
            return null;
        }
        Integer ft = jsonInt(wm, "f2");   // functionType
        Integer fval = jsonInt(wm, "f3"); // functionValue
        if (ft == null || fval == null
                || !isZoneChangeFunctionType(ft)) {
            return null;
        }

        // functionType 29 (Underground Exit) is keyed by the CURRENT
        // world id, not the worldmodel functionValue (TinNS
        // UdpUseObject.cxx:347). We don't yet route ft==29 from the
        // UseItem path (no current-world id is threaded in here);
        // resolve the standard fval path and let callers that need
        // the ft==29 special case override the appplaces key.
        JsonObject ap =
                lookupDefFields(conn, "appplaces", fval);
        if (ap == null) {
            Out.writeln(Out.Warning,
                "PortalResolver: worldmodel " + worldmodelId
                + " (ft=" + ft + ") points at appplaces[" + fval
                + "] which does not exist");
            return null;
        }
        Integer exitWorld = jsonInt(ap, "f1");
        Integer entity = jsonInt(ap, "f2");
        Integer sewer = jsonInt(ap, "f3");
        if (exitWorld == null || entity == null) {
            return null;
        }
        return new Portal(worldmodelId, ft, fval, exitWorld, entity,
                sewer == null ? 0 : sewer);
    }

    /** {@code SELECT worldmodel_id FROM world_objects
     *  WHERE world_path=? AND object_id=?}. */
    private static Integer lookupWorldmodelId(Connection conn,
            String worldPath, int objectId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT worldmodel_id FROM world_objects "
                + "WHERE world_path = ? AND object_id = ?")) {
            ps.setString(1, worldPath);
            ps.setInt(2, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int v = rs.getInt(1);
                    return rs.wasNull() ? null : v;
                }
            }
        } catch (SQLException e) {
            Out.writeln(Out.Warning,
                "PortalResolver: world_objects lookup failed for "
                + worldPath + "/" + objectId + ": " + e.getMessage());
        }
        return null;
    }

    /** Read {@code client_defs.fields} for {@code (defName, id)} and
     *  parse it as a JSON object. Works on both Postgres (JSONB,
     *  serialised as text by the driver) and SQLite (TEXT). */
    private static JsonObject lookupDefFields(Connection conn,
            String defName, int id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT fields FROM client_defs "
                + "WHERE def_name = ? AND entry_id = ?")) {
            ps.setString(1, defName);
            ps.setInt(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString(1);
                    if (json == null || json.isEmpty()) {
                        return null;
                    }
                    return JsonParser.parseString(json)
                            .getAsJsonObject();
                }
            }
        } catch (SQLException | RuntimeException e) {
            Out.writeln(Out.Warning,
                "PortalResolver: client_defs lookup failed for "
                + defName + "[" + id + "]: " + e.getMessage());
        }
        return null;
    }

    private static Integer jsonInt(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
