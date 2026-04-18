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
 * Loads NPC spawn definitions from the {@code npc_spawns} SQLite table
 * and provides them to {@link server.gameserver.Zone} instances on
 * startup.
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

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, zone_id, type, name, x, y, z, angle, hp, armor " +
                "FROM npc_spawns WHERE zone_id = ?")) {
            ps.setInt(1, zoneId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    npcs.add(new NPC(
                        rs.getInt("id"),
                        rs.getInt("zone_id"),
                        rs.getInt("type"),
                        rs.getString("name"),
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

        Out.writeln(Out.Info, "NpcSpawnManager: loaded " + npcs.size()
                + " NPCs for zone " + zoneId);
        return npcs;
    }
}
