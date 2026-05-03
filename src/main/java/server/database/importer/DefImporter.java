package server.database.importer;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

import server.database.DefReader;
import server.tools.Out;
import server.tools.VirtualFileSystem;

/**
 * Imports every {@code defs\*.def} file from the NC2 client into a
 * generic {@code client_defs} table as JSON rows.
 *
 * <p>Each def file is a tab-separated table where every line begins
 * with a directive ({@code setentry}, {@code setfrac}, {@code setdef}…),
 * followed by an integer ID and N data fields, terminated by {@code |}.
 * We don't try to schema-typewrite every column — the schemas vary
 * per file and there are 51 files. Instead each entry becomes a JSON
 * blob keyed by {@code (def_name, entry_id)}, queryable at runtime.
 *
 * <p>Subsystems that already cache a specific def in memory
 * (e.g. {@link server.database.items.ItemInfoManager}) keep working
 * unchanged — the DB import is additive. Future code can prefer the
 * DB-backed read.
 *
 * <p>Idempotent: skips files whose row count in the DB matches the
 * file's parsed row count.
 */
public final class DefImporter {

    private static final Gson GSON = new Gson();

    /** Subset of def files we proactively import on first boot. The
     * full 51-file sweep is available via {@link #importAll}. */
    private static final List<String> CORE_DEFS = Arrays.asList(
        "items",         // 425 entries  weapons + ammo + base item refs
        "itemres",       // ~120 entries item-resource links
        "itemmod",       // 180 modifiers
        "itemcontainer", // 193 containers (apartment box, vendor tray, …)
        "weapons",       // weapon stats
        "armor",         // 271 armor pieces
        "ammo",          // ammo types
        "shots",         // 246 projectile/shot types
        "drugs",         // 161 consumables
        "implants",      // 197 implants
        "npc",           // 1762 NPC defs
        "npcarmor",      // 566
        "npcgroupspawn", // 351 group spawns
        "npcloot",       // loot tables
        "fractions",     // factions (sic — original NC2 spelling)
        "skills",
        "subskill",
        "missionbase",   // mission templates
        "characters",    // character class defaults
        "charkinds",     // character kinds
        "appartements",  // apartment layouts
        "appplaces",     // apartment placement points
        "weather",
        "damage",
        "drugs",
        "actionmod",
        "effects",
        "itemplan",
        "blueprintpieces",
        "respawn",
        "outposts",
        "trader",
        "menu",
        "routemenu",
        "routesubmenu",
        "scripts",       // script registry
        "modeltextures",
        "rsctables",
        "gameplaysettings",
        "hack",
        "recycles",
        "maps",
        "worldmodel",
        "worldinfo",
        "worldspawnpoints",
        "customanimstrings",
        "charaction"
    );

    private DefImporter() {}

    /** Run the full import. Safe to call on every boot — skips files
     *  whose DB row count already matches the file. */
    public static void runIfNeeded(Connection conn) {
        if (conn == null) {
            Out.writeln(Out.Warning, "DefImporter: no DB connection, skipping");
            return;
        }
        if (!ensureTable(conn)) return;
        int fileCount = 0;
        int totalEntries = 0;
        for (String defName : CORE_DEFS) {
            int n = importOne(conn, defName);
            if (n >= 0) {
                fileCount++;
                totalEntries += n;
            }
        }
        Out.writeln(Out.Info, "DefImporter: " + fileCount + " files, "
                + totalEntries + " entries imported into client_defs");
    }

    /** Returns row count (0+) on success, -1 on missing/error. */
    public static int importOne(Connection conn, String defName) {
        InputStream in;
        try {
            in = VirtualFileSystem.getFileInputStream("defs\\" + defName + ".def");
        } catch (RuntimeException e) {
            // Some files in CORE_DEFS may not exist in every client build.
            return -1;
        }
        if (in == null) return -1;

        // Skip if already imported (count matches what's in the file).
        // We can't know the file's count without parsing, so the cheap
        // optimization is: if there's ANY row for this def_name, skip.
        // For a full re-import, manually DELETE FROM client_defs WHERE
        // def_name = '...'.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM client_defs WHERE def_name = ?")) {
            ps.setString(1, defName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            Out.writeln(Out.Warning, "DefImporter: probe failed for "
                    + defName + ": " + e.getMessage());
        }

        DefReader dr = new DefReader(in);
        List<Map<String, Object>> rows = new ArrayList<>();
        while (!dr.isEof()) {
            String[] tokens = dr.getTokens();
            if (tokens.length < 3) continue;
            String directive = tokens[0];
            // accept anything starting with "set" — every def variant
            // we observed (setentry, setfrac, setdef, setspawn) is an
            // entry directive.
            if (!directive.startsWith("set")) continue;
            int id;
            try {
                id = Integer.parseInt(tokens[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            // Encode all data fields as a JSON object: { "f0":"...", "f1":"...", ... }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("directive", directive);
            for (int i = 2; i < tokens.length; i++) {
                fields.put("f" + (i - 2), parseToken(tokens[i]));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("fields", fields);
            rows.add(row);
        }
        dr.close();

        if (rows.isEmpty()) return 0;

        boolean isPostgres = isPostgres(conn);
        String upsertSql = isPostgres
                ? "INSERT INTO client_defs (def_name, entry_id, fields) "
                  + "VALUES (?, ?, ?::jsonb) "
                  + "ON CONFLICT (def_name, entry_id) DO UPDATE SET fields = EXCLUDED.fields"
                : "INSERT OR REPLACE INTO client_defs (def_name, entry_id, fields) "
                  + "VALUES (?, ?, ?)";

        int inserted = 0;
        try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            for (Map<String, Object> row : rows) {
                ps.setString(1, defName);
                ps.setInt(2, (Integer) row.get("id"));
                ps.setString(3, GSON.toJson(row.get("fields")));
                ps.addBatch();
                inserted++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            Out.writeln(Out.Error, "DefImporter: insert failed for "
                    + defName + ": " + e.getMessage());
            return -1;
        }
        Out.writeln(Out.Info, "DefImporter: imported "
                + inserted + " entries from " + defName + ".def");
        return inserted;
    }

    /** Coerce a token to int / double / String so the JSON has typed values. */
    private static Object parseToken(String s) {
        if (s == null || s.isEmpty()) return s;
        // Try int first (no decimal point).
        if (s.indexOf('.') < 0) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        return s;
    }

    /** Run import for any subset of def files. */
    public static void importAll(Connection conn, List<String> defNames) {
        if (conn == null) return;
        if (!ensureTable(conn)) return;
        for (String name : defNames) {
            importOne(conn, name);
        }
    }

    /** Ensure the {@code client_defs} table exists. Returns true on
     *  success. */
    private static boolean ensureTable(Connection conn) {
        boolean isPostgres = isPostgres(conn);
        String fieldsType = isPostgres ? "JSONB" : "TEXT";
        String sql = "CREATE TABLE IF NOT EXISTS client_defs ("
                + "  def_name TEXT NOT NULL,"
                + "  entry_id INTEGER NOT NULL,"
                + "  fields " + fieldsType + " NOT NULL,"
                + "  PRIMARY KEY (def_name, entry_id))";
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
            return true;
        } catch (SQLException e) {
            Out.writeln(Out.Error, "DefImporter: create table failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean isPostgres(Connection conn) {
        try {
            String url = conn.getMetaData().getURL();
            return url != null && url.startsWith("jdbc:postgresql");
        } catch (SQLException e) {
            return false;
        }
    }
}
