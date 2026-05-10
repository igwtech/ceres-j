package server.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import server.gameserver.NPC;
import server.tools.Out;

/**
 * Loads NPC spawn definitions for a given zone, preferring the
 * curated {@code npc_spawns} table; if that returns nothing for
 * the zone, falls back to {@code world_npcs} (the bulk PAK-import
 * table — 3,571 entries across all retail zones; cf.
 * {@code world_defs} for path mapping).
 *
 * <p>The fallback is read-only and joins on the path mapping
 * {@code worlds/<dir>/pak_<basename>.dat} derived from
 * {@code world_defs.path}. NPC type / position / name come from
 * {@code world_npcs}; {@code script_name} and {@code model_name}
 * are left blank since {@code world_npcs} does not carry them.
 *
 * <p>Without this fallback, only zone 1 (Plaza P1) had NPCs (8
 * stub rows in {@code npc_spawns}); the rest of the world was
 * empty, producing the user-visible "no NPCs anywhere" symptom.
 */
public class NpcSpawnManager {

    /**
     * Load all NPC spawns for a given zone.
     *
     * @param zoneId the zone to load spawns for
     * @return list of NPC instances; empty if none defined or on error
     */
    public static List<NPC> loadForZone(int zoneId) {
        List<NPC> npcs = new ArrayList<>();
        Connection conn = SqliteDatabase.getConnection();
        if (conn == null) return npcs;

        // ── Primary: curated npc_spawns table ─────────────────────────
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, zone_id, type, name, script_name, model_name, x, y, z, angle, hp, armor " +
                "FROM npc_spawns WHERE zone_id = ?")) {
            ps.setInt(1, zoneId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    npcs.add(new NPC(
                        rs.getInt("id"),
                        rs.getInt("zone_id"),
                        rs.getInt("type"),
                        rs.getString("name"),
                        rs.getString("script_name"),
                        rs.getString("model_name"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z"),
                        rs.getInt("angle"),
                        rs.getInt("hp"),
                        rs.getInt("armor")
                    ));
                }
            }
        } catch (SQLException e) {
            Out.writeln(Out.Error, "NpcSpawnManager: failed to load zone "
                    + zoneId + ": " + e.getMessage());
        }

        // ── Fallback: world_npcs (PAK-import bulk data) ───────────────
        // If npc_spawns had nothing for this zone, try the bulk table.
        // Joins on world_defs path → world_npcs.world_path.
        if (npcs.isEmpty()) {
            int loaded = loadFromWorldNpcs(conn, zoneId, npcs);
            if (loaded > 0) {
                Out.writeln(Out.Info, "NpcSpawnManager: bridged "
                        + loaded + " NPCs from world_npcs for zone "
                        + zoneId);
            }
        }

        Out.writeln(Out.Info, "NpcSpawnManager: loaded " + npcs.size()
                + " NPCs for zone " + zoneId);
        return npcs;
    }

    /**
     * Fall back to {@code world_npcs} for zones that have no entry in
     * {@code npc_spawns}. Returns the number of NPCs added to {@code out}.
     */
    private static int loadFromWorldNpcs(Connection conn, int zoneId,
                                          List<NPC> out) {
        // Path mapping: world_defs.path = 'plaza/plaza_p3' →
        // world_npcs.world_path = 'worlds/plaza/pak_plaza_p3.dat'.
        // The regexp_replace prepends `pak_` to the last path segment.
        // angle in world_npcs is TEXT (may be null/empty) — coerce
        // safely to int with fallback 0.
        final String sql =
            "SELECT wn.npc_id, wn.npc_type_id, wn.actor_name, " +
            "  wn.pos_x, wn.pos_y, wn.pos_z, wn.angle " +
            "FROM world_defs wd " +
            "JOIN world_npcs wn ON wn.world_path = " +
            "  'worlds/' || regexp_replace(wd.path, '([^/]+)$', 'pak_\\1') || '.dat' " +
            "WHERE wd.id = ?";
        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, zoneId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int mapId = rs.getInt("npc_id");
                    int type = rs.getInt("npc_type_id");
                    String name = rs.getString("actor_name");
                    int x = (int) rs.getFloat("pos_x");
                    int y = (int) rs.getFloat("pos_y");
                    int z = (int) rs.getFloat("pos_z");
                    int angle = parseAngle(rs.getString("angle"));
                    out.add(new NPC(
                        mapId,
                        zoneId,
                        type,
                        name != null ? name : "",
                        "",      // script_name not in world_npcs
                        "",      // model_name not in world_npcs
                        x, y, z, angle,
                        100,     // hp default
                        0        // armor default
                    ));
                    count++;
                }
            }
        } catch (SQLException e) {
            Out.writeln(Out.Error, "NpcSpawnManager: world_npcs fallback "
                    + "failed for zone " + zoneId + ": " + e.getMessage());
        }
        return count;
    }

    /**
     * Parse the angle TEXT field from world_npcs. Values seen include
     * "0", "180", "-90", "270", "" (empty), and null. Negative values
     * are normalised to [0, 360).
     */
    static int parseAngle(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.isEmpty()) return 0;
        try {
            int a = Integer.parseInt(s);
            // Normalise to [0, 360)
            a = ((a % 360) + 360) % 360;
            return a;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
