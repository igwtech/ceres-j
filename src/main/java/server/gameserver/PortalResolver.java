package server.gameserver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.google.gson.JsonElement;
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
     * Resolve a zone's {@code world_objects.world_path} respecting the
     * {@code defs.worldinfo} {@code alternatedatfile} override (field
     * {@code f2}).
     *
     * <h3>Why this exists</h3>
     *
     * <p>Most zones use the default naming convention encoded in
     * {@link #worldnameToObjectPath(String)}: the {@code World} path
     * {@code "dir/base"} maps to the .dat file
     * {@code "worlds/dir/pak_base.dat"}. But some dungeon zones have
     * an alternate .dat filename that does NOT follow the convention:
     *
     * <pre>
     *   worldinfo[1064] (ABANDONED CELLAR 2 EASY):
     *     world_defs.path  = "sewer/sewer_p4"
     *     defs.worldinfo.f2 = ".\worlds\sewer\sewer_p4_x1.dat"
     *     world_objects.world_path = "worlds/sewer/pak_sewer_p4_x1.dat"
     *                                                ^^^^ NOT pak_sewer_p4.dat
     *
     *   worldinfo[1573] (Reactor Room):
     *     world_defs.path  = "startmissions/reaktor"
     *     defs.worldinfo.f2 = ".\worlds\startmissions\reaktor_NC.dat"
     *     world_objects.world_path =
     *       "worlds/startmissions/pak_reaktor_nc.dat"
     *
     *   worldinfo[1] (PLAZA SEC-1):
     *     world_defs.path  = "plaza/plaza_p1"
     *     defs.worldinfo.f2 = " "   (whitespace = no override)
     *     world_objects.world_path = "worlds/plaza/pak_plaza_p1.dat"
     * </pre>
     *
     * <p>Without honouring {@code worldinfo.f2}, dungeon zones'
     * objects (exit doors, NPCs, scripted entities) can't be looked
     * up in {@code world_objects} — every interaction logs
     * {@code "UnknownItem ID: …"} and the player is stuck.
     * Live-confirmed 2026-05-23 via pcap of an entry→Reactor-Room
     * cross: server emitted the zone-change correctly, client
     * transitioned, exit-door click ({@code objectId=8} in
     * {@code worlds/startmissions/pak_reaktor.dat}) returned NULL
     * because the file doesn't exist — the real file is
     * {@code pak_reaktor_nc.dat}.
     *
     * <h3>Normalisation</h3>
     *
     * <p>Converts the worldinfo.f2 string to a world_objects-shaped
     * key:
     *
     * <pre>
     *   ".\worlds\startmissions\reaktor_NC.dat"
     *     →  worlds/startmissions/pak_reaktor_nc.dat
     * </pre>
     *
     * <ol>
     *   <li>Strip leading {@code "./"} or {@code ".\"} if present</li>
     *   <li>Replace all {@code "\"} with {@code "/"}</li>
     *   <li>Lowercase (world_objects rows are lowercase)</li>
     *   <li>Add {@code "pak_"} prefix to the final filename</li>
     * </ol>
     *
     * @param zoneId  the target zone id (e.g. 1573 for Reactor Room)
     * @param fallbackWorldname  worldname to use if neither
     *        {@code worldinfo[zoneId].f2} nor
     *        {@link server.database.worlds.WorldManager#getWorldname}
     *        returns anything. Pass {@code pl.getZone().getWorldname()}
     *        from callers — production has the World registered there
     *        AND in WorldManager; test fixtures may only populate the
     *        Zone object.
     * @return the world_objects.world_path, or {@code null} if no
     *         worldinfo[zoneId] row exists / f2 is blank AND no
     *         worldname is available from any source.
     */
    public static String worldIdToObjectPath(int zoneId,
                                              String fallbackWorldname) {
        // Prefer the alternate .dat path when worldinfo[zoneId].f2 is
        // populated.
        Connection conn = SqliteDatabase.getConnection();
        if (conn != null) {
            String f2 = lookupWorldinfoF2(conn, zoneId);
            String normalised = normaliseDatFile(f2);
            if (normalised != null) {
                return normalised;
            }
        }
        // Fallback: the legacy default convention (no override).
        String worldname = server.database.worlds.WorldManager
                .getWorldname(zoneId);
        if (worldname == null) {
            worldname = fallbackWorldname;
        }
        return worldnameToObjectPath(worldname);
    }

    /**
     * @deprecated Use the 2-arg form so test fixtures can supply a
     * fallback worldname. Internal use only — production callers should
     * pass {@code pl.getZone().getWorldname()}.
     */
    @Deprecated
    public static String worldIdToObjectPath(int zoneId) {
        return worldIdToObjectPath(zoneId, null);
    }

    /**
     * Look up {@code defs.worldinfo[zoneId].f2}.
     *
     * <p>Returns {@code null} if no row, no f2 field, or the field is
     * blank / single-space (the "no override" sentinel used by retail
     * for non-dungeon zones).
     */
    private static String lookupWorldinfoF2(Connection conn, int zoneId) {
        JsonObject row = lookupDefFields(conn, "worldinfo", zoneId);
        if (row == null) {
            return null;
        }
        JsonElement f2el = row.get("f2");
        if (f2el == null || f2el.isJsonNull()) {
            return null;
        }
        String f2 = f2el.getAsString();
        if (f2 == null) return null;
        String trimmed = f2.trim();
        if (trimmed.isEmpty()) return null;
        return f2;
    }

    /**
     * Normalise a worldinfo.f2 dat-file string into a
     * world_objects.world_path key. See {@link #worldIdToObjectPath}
     * for the format spec.
     *
     * <p>Package-private for unit testing.
     *
     * @param f2  raw value from {@code defs.worldinfo.f2}
     * @return normalised key, or {@code null} if {@code f2} is null,
     *         blank, or doesn't look like a {@code worlds\dir\file.dat}
     *         shape.
     */
    static String normaliseDatFile(String f2) {
        if (f2 == null) return null;
        String s = f2.trim();
        if (s.isEmpty()) return null;
        // Strip leading "./" / ".\"
        if (s.startsWith("./") || s.startsWith(".\\")) {
            s = s.substring(2);
        }
        // Backslashes to forward slashes.
        s = s.replace('\\', '/');
        s = s.toLowerCase(java.util.Locale.ROOT);
        // Sanity check the shape — must end in .dat and have a slash.
        if (!s.endsWith(".dat")) return null;
        int lastSlash = s.lastIndexOf('/');
        if (lastSlash <= 0 || lastSlash >= s.length() - 1) {
            return null;
        }
        // Add pak_ prefix to the final filename if not already present.
        String dir = s.substring(0, lastSlash);
        String file = s.substring(lastSlash + 1);
        if (!file.startsWith("pak_")) {
            file = "pak_" + file;
        }
        return dir + "/" + file;
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

    /**
     * {@code worldmodel.def} UseFlags bit marking a seatable chair.
     * Matches NC1 TinNS {@code FurnitureTemplate.hxx:12 ufChair = 8}.
     * The UseFlags column is parsed by TinNS
     * {@code WorldModels.cxx:24} as field index 2 → stored by
     * {@link server.database.importer.DefImporter} as JSON key
     * {@code "f1"} (token order: directive, id, f0=name,
     * f1=UseFlags, f2=functionType, f3=functionValue).
     */
    public static final int UF_CHAIR = 8;

    /**
     * True if the static furniture object at {@code objectId} in the
     * zone whose {@code world_objects.world_path} is {@code worldPath}
     * is a seatable chair — i.e. its {@code worldmodel.def} entry's
     * UseFlags ({@code f1}) has the {@link #UF_CHAIR} bit set.
     *
     * <p>Verified against the live {@code worldmodel.def}: entry 10
     * {@code "CHAIR"} UseFlags 10 (10 &amp; 8 = 8), entry 11
     * {@code "WOODEN CHAIR"} 10, entry 12 {@code "BENCH"} 10, entry
     * 113 {@code "chair with bounding box"} 74 (74 &amp; 8 = 8);
     * entry 1 {@code "DOOR"} UseFlags 2 (2 &amp; 8 = 0).
     *
     * @return {@code true} if the object resolves to a chair
     *         worldmodel; {@code false} if there is no such object,
     *         no worldmodel.def entry, or the UseFlags bit is clear.
     */
    public static boolean isChair(String worldPath, int objectId) {
        if (worldPath == null) {
            return false;
        }
        Connection conn = SqliteDatabase.getConnection();
        if (conn == null) {
            return false;
        }
        Integer worldmodelId =
                lookupWorldmodelId(conn, worldPath, objectId);
        if (worldmodelId == null) {
            return false;
        }
        JsonObject wm =
                lookupDefFields(conn, "worldmodel", worldmodelId);
        if (wm == null) {
            return false;
        }
        Integer useFlags = jsonInt(wm, "f1");
        return useFlags != null && (useFlags & UF_CHAIR) != 0;
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

    /**
     * Look up the default appplaces spawn-row index for a zone — the
     * {@code f3} field of {@code defs.worldinfo[zoneId]}. This is
     * the LE32 written as the 3rd field of TCP {@code 0x83/0x0c
     * Location} (the "spawnIdx"), telling the client which entry
     * point to position the player at on world-load.
     *
     * <p>Live-verified values from the DB (2026-05-30):
     * <ul>
     *   <li>Plaza Sec-1 (zone 1): {@code f3=16} — login spawn center.</li>
     *   <li>Plaza Sec-3 (zone 101): {@code f3=0} — preserves coords
     *       (right value for a walk-cross into P3).</li>
     *   <li>Plaza Sec-4 (zone 102): {@code f3=0}.</li>
     *   <li>Reactor Room (zone 1573): {@code f3=1} — dungeon entry.</li>
     *   <li>Abandoned Cellar 2 Easy (zone 1064): {@code f3=…}.</li>
     * </ul>
     *
     * <p>This replaces the bsp-prefix heuristic that
     * {@link server.gameserver.Zone#getDefaultSpawnIdx()} used to
     * carry — that approach returned 16 for ALL {@code plaza/} zones
     * and regressed the walk-cross spawn position (caused Asddf to
     * spawn in the centre of plaza_p3 instead of at the P1↔P3 seam).
     * The DB is the canonical source.
     *
     * @param zoneId  Ceres-J zone id (matches {@code defs.worldinfo}
     *                {@code entry_id}, which is the same as
     *                {@code PlayerCharacter.MISC_LOCATION})
     * @return        the spawn index from {@code worldinfo.f3}, or
     *                {@code 0} if no row, no f3 field, or the field
     *                is not parseable as an int (safe fallback)
     */
    public static int lookupSpawnIdx(int zoneId) {
        Connection conn = SqliteDatabase.getConnection();
        if (conn == null) {
            return 0;
        }
        JsonObject row = lookupDefFields(conn, "worldinfo", zoneId);
        if (row == null) {
            return 0;
        }
        Integer f3 = jsonInt(row, "f3");
        return f3 == null ? 0 : f3;
    }
}
