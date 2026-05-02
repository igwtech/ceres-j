package server.database;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import server.database.importer.ClientDataImporter;
import server.database.playerCharacters.PlayerCharacter;
import server.exceptions.StartupException;
import server.tools.Out;

public final class SqliteDatabase {

    /**
     * Schema version owned by the client-data importer track.
     * NOTE: version 1 is reserved for the parallel feature/charinfo-fidelity
     * branch. This track uses version 2. When the two tracks merge, bump
     * the constant and coordinate the migrations in order.

     * Schema version for incremental migrations via {@code PRAGMA user_version}.
     * <ul>
     *   <li>0 — legacy pre-versioning schema (no extra columns beyond MISCLIST/SKILLS/SUBSKILLS).</li>
     *   <li>1 — adds CharInfo fidelity columns: pools (health/psi/stamina), synaptic, cash, rank,
     *           faction_sympathies (JSON) and per-skill xp/rate/max.</li>
     * </ul>
     */         
    public static final int CURRENT_SCHEMA_VERSION = 4;

    /**
     * CharInfo fidelity columns added in schema v1. Each entry is
     * {@code {columnName, sqlType, defaultClause}} and is consumed by both
     * {@link #createTables()} (appended to the CREATE TABLE SQL) and
     * {@link #migrateSchema()} (used for {@code ALTER TABLE ADD COLUMN} on legacy DBs).
     * Keeping a single source of truth guarantees fresh DBs and migrated DBs agree.
     *
     * <p>The per-skill xp/rate/max columns use {@link Integer#MIN_VALUE} as the
     * "unset" sentinel so {@code PlayerCharacter.getSkillXP/Rate/Max} can fall
     * back to class-based defaults for legacy data.
     */
    public static final String[][] FIDELITY_COLUMNS = new String[][] {
        {"health",             "INTEGER", "DEFAULT 100"},
        {"max_health",         "INTEGER", "DEFAULT 100"},
        {"psi_pool",           "INTEGER", "DEFAULT 100"},
        {"max_psi_pool",       "INTEGER", "DEFAULT 100"},
        {"stamina",            "INTEGER", "DEFAULT 100"},
        {"max_stamina",        "INTEGER", "DEFAULT 100"},
        {"synaptic",           "INTEGER", "DEFAULT 100"},
        {"cash",               "INTEGER", "DEFAULT 1001"},
        {"rank",               "INTEGER", "DEFAULT 0"},
        {"faction_sympathies", "TEXT",    "DEFAULT NULL"},
        {"str_xp",             "INTEGER", "DEFAULT -2147483648"},
        {"str_rate",           "INTEGER", "DEFAULT -2147483648"},
        {"str_max",            "INTEGER", "DEFAULT -2147483648"},
        {"dex_xp",             "INTEGER", "DEFAULT -2147483648"},
        {"dex_rate",           "INTEGER", "DEFAULT -2147483648"},
        {"dex_max",            "INTEGER", "DEFAULT -2147483648"},
        {"con_xp",             "INTEGER", "DEFAULT -2147483648"},
        {"con_rate",           "INTEGER", "DEFAULT -2147483648"},
        {"con_max",            "INTEGER", "DEFAULT -2147483648"},
        {"int_xp",             "INTEGER", "DEFAULT -2147483648"},
        {"int_rate",           "INTEGER", "DEFAULT -2147483648"},
        {"int_max",            "INTEGER", "DEFAULT -2147483648"},
        {"psi_xp",             "INTEGER", "DEFAULT -2147483648"},
        {"psi_rate",           "INTEGER", "DEFAULT -2147483648"},
        {"psi_max",            "INTEGER", "DEFAULT -2147483648"},
    };

    private static Connection connection;
    private static final String DB_PATH = "database" + File.separator + "ceres.db";
    private static boolean isPostgres = false;

    /**
     * Whether the active backend is PostgreSQL. Influences DDL syntax for
     * schema migrations (e.g., {@code information_schema.columns} vs
     * {@code PRAGMA table_info}) and skips SQLite-specific PRAGMAs. Set
     * during {@link #init()} from the JDBC URL.
     */
    public static boolean isPostgres() { return isPostgres; }

    public static void init() throws StartupException {
        try {
            // Resolve JDBC URL: production runtime defaults to PostgreSQL via
            // ceres.cfg. If no Database.url is configured, fall back to the
            // SQLite file at ./database/ceres.db. Tests bypass init() entirely
            // and call initWithConnection(...) with an in-memory SQLite.
            String url = server.tools.Config.getProperty("Database.url");
            String user = server.tools.Config.getProperty("Database.user");
            String pass = server.tools.Config.getProperty("Database.password");
            if (url == null || url.isBlank()) {
                url = "jdbc:sqlite:" + DB_PATH;
            }

            isPostgres = url.startsWith("jdbc:postgresql:");
            if (isPostgres) {
                Class.forName("org.postgresql.Driver");
                connection = DriverManager.getConnection(url,
                    user != null ? user : "ceres",
                    pass != null ? pass : "ceres");
            } else {
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection(url);
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL");
                    stmt.execute("PRAGMA foreign_keys=ON");
                    stmt.execute("PRAGMA busy_timeout=5000");
                }
            }

            createTables();

            // Import client-derived resource defs (worlds.ini, etc.).
            // Guarded so import failures never abort server startup — the
            // server must boot on a fresh install even with no client
            // mounted. ClientDataImporter already logs its own errors.
            try {
                ClientDataImporter.runIfNeeded(connection);
            } catch (RuntimeException e) {
                Out.writeln(Out.Error,
                    "ClientDataImporter crashed, continuing startup: " + e.getMessage());
            }

            migrateSchema();
            migrateFromCsv();

            // One-shot copy of legacy SQLite data into the active PG database
            // when the user has just switched backends. No-op in SQLite mode.
            if (isPostgres) {
                SqliteToPostgresMigrator.migrateIfNeeded(connection);
            }

            Out.writeln(Out.Info, "Database initialized: " + url
                + (isPostgres ? " (PostgreSQL)" : " (SQLite)"));
        } catch (ClassNotFoundException e) {
            throw new StartupException("JDBC driver not found: " + e.getMessage());
        } catch (SQLException e) {
            throw new StartupException("Failed to initialize database: " + e.getMessage());
        }
    }

    /**
     * Initialize with a custom connection (for testing).
     * Auto-detects backend from the connection's metadata URL.
     */
    public static void initWithConnection(Connection conn) throws SQLException {
        connection = conn;
        String url = conn.getMetaData().getURL();
        isPostgres = url != null && url.startsWith("jdbc:postgresql:");
        if (!isPostgres) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }
        }
        createTables();
        migrateSchema();
    }

    public static Connection getConnection() {
        return connection;
    }

    public static void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                // Silently handle close errors
            }
        }
    }

    /**
     * Quote a column name with double quotes to handle SQL reserved words
     * like "for", "end", "class", "int", etc.
     */
    public static String q(String columnName) {
        return "\"" + columnName + "\"";
    }

    private static void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Accounts table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS accounts (" +
                "  id INTEGER PRIMARY KEY," +
                "  username TEXT NOT NULL UNIQUE," +
                "  password TEXT NOT NULL," +
                "  char1 INTEGER DEFAULT 0," +
                "  char2 INTEGER DEFAULT 0," +
                "  char3 INTEGER DEFAULT 0," +
                "  char4 INTEGER DEFAULT 0," +
                "  status TEXT DEFAULT ''" +
                ")"
            );

            // Player characters table — all fields from MISCLIST, SKILLS, SUBSKILLS, container IDs
            StringBuilder pcSql = new StringBuilder();
            pcSql.append("CREATE TABLE IF NOT EXISTS player_characters (");
            pcSql.append("  id INTEGER PRIMARY KEY,");
            pcSql.append("  name TEXT NOT NULL,");

            // MISCLIST fields (indices 1..21 where non-null, excluding id at index 15)
            for (int i = 0; i < PlayerCharacter.MISCLIST.length; i++) {
                if (PlayerCharacter.MISCLIST[i] != null && i != PlayerCharacter.MISC_ID) {
                    pcSql.append("  ").append(q(PlayerCharacter.MISCLIST[i])).append(" INTEGER DEFAULT 0,");
                }
            }

            // 5 skills: lvl + pts each
            for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
                pcSql.append("  ").append(q(PlayerCharacter.SKILLS[i] + "_lvl")).append(" INTEGER DEFAULT 0,");
                pcSql.append("  ").append(q(PlayerCharacter.SKILLS[i] + "_pts")).append(" INTEGER DEFAULT 0,");
            }

            // Subskills (non-null entries) — includes reserved words like "for", "end"
            for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
                if (PlayerCharacter.SUBSKILLS[i] != null) {
                    pcSql.append("  ").append(q(PlayerCharacter.SUBSKILLS[i])).append(" INTEGER DEFAULT 0,");
                }
            }

            // CharInfo fidelity columns (schema v1) — pools, cash/rank, sympathies, per-skill xp/rate/max
            for (String[] col : FIDELITY_COLUMNS) {
                pcSql.append("  ").append(q(col[0])).append(" ").append(col[1]).append(" ").append(col[2]).append(",");
            }

            // Inventory container IDs
            pcSql.append("  f2_inventory_cont_id INTEGER DEFAULT 0,");
            pcSql.append("  gogu_inventory_cont_id INTEGER DEFAULT 0,");
            pcSql.append("  qb_inventory_cont_id INTEGER DEFAULT 0");
            pcSql.append(")");

            stmt.execute(pcSql.toString());

            // Items table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS items (" +
                "  id INTEGER PRIMARY KEY," +
                "  container_id INTEGER NOT NULL," +
                "  type_id INTEGER NOT NULL," +
                "  slot INTEGER DEFAULT 0," +
                "  quality INTEGER DEFAULT 0" +
                ")"
            );

            // Item containers table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS item_containers (" +
                "  id INTEGER PRIMARY KEY," +
                "  type INTEGER NOT NULL" +
                ")"
            );

            // Schema-v2 tables (client-data importer). Idempotent CREATE IF
            // NOT EXISTS — no version gate here. The authoritative version
            // bump lives in migrateSchema() so legacy DBs at version < 1 get
            // their CharInfo columns ALTER-added before the version advances.

            // World definitions imported from NC2 client's worlds/worlds.ini
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS world_defs (" +
                "  id INTEGER PRIMARY KEY," +
                "  path TEXT NOT NULL," +        // e.g. "plaza/plaza_p1"
                "  bsp_name TEXT NOT NULL" +     // e.g. "plaza_p1.bsp"
                ")"
            );

            // Item definitions imported from NC2 client's defs/pak_items.def
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS item_defs (" +
                "  id INTEGER PRIMARY KEY," +
                "  name TEXT," +
                "  type INTEGER," +
                "  tech_level INTEGER," +
                "  stats_json TEXT" +
                ")"
            );

            // NPC spawn definitions — where NPCs appear in each zone.
            // Schema mirrors TinNS npc_spawns. mapID must be in the
            // retail convention range 0x101–0x1FF (257–511) so clients
            // can reference them in RequestWorldInfo queries and the
            // raw 0x1b position broadcasts look legitimate.
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS npc_spawns (" +
                "  id INTEGER PRIMARY KEY," +
                "  zone_id INTEGER NOT NULL," +
                "  type INTEGER NOT NULL," +
                "  name TEXT DEFAULT ''," +
                "  script_name TEXT DEFAULT ''," +
                "  model_name TEXT DEFAULT ''," +
                "  x INTEGER DEFAULT 0," +
                "  y INTEGER DEFAULT 0," +
                "  z INTEGER DEFAULT 0," +
                "  angle INTEGER DEFAULT 0," +
                "  hp INTEGER DEFAULT 100," +
                "  armor INTEGER DEFAULT 0" +
                ")"
            );

            // Seed default plaza_p1 (zone 1) NPCs if the table is empty AND
            // there's no legacy SQLite file waiting to be migrated (which
            // would already contain these rows). Without this guard,
            // PG seeds 257-264 here and then SqliteToPostgresMigrator hits a
            // duplicate-key error trying to copy the same IDs from SQLite.
            // Type IDs and script/model names sourced from pak_npc.def.
            // Positions from retail ACC1_CHAR1 0x1b broadcasts.
            // IDs 257–264 (0x101–0x108) are in the retail 0x101–0x1FF range.
            java.sql.ResultSet rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM npc_spawns");
            rs.next();
            boolean pendingSqliteMigration = isPostgres
                && new File("database" + File.separator + "ceres.db").exists();
            if (rs.getInt(1) == 0 && !pendingSqliteMigration) {
                stmt.execute(
                    "INSERT INTO npc_spawns (id, zone_id, type, name, script_name, model_name, x, y, z, angle, hp, armor) VALUES " +
                    // type 191 = S_CITYMERCS_0, WLDK model (CityMercs faction security guard)
                    "(257, 1, 191, 'CityMercs Guard', 'S_CITYMERCS_0', 'WLDK', 32186, 31551, 32827, 64, 500, 50)," +
                    "(258, 1, 191, 'CityMercs Guard', 'S_CITYMERCS_0', 'WLDK', 32301, 31488, 32835, 64, 500, 50)," +
                    "(259, 1, 191, 'CityMercs Guard', 'S_CITYMERCS_0', 'WLDK', 31768, 31523, 32899, 64, 500, 50)," +
                    "(260, 1, 191, 'CityMercs Guard', 'S_CITYMERCS_0', 'WLDK', 32135, 31487, 32653, 64, 500, 50)," +
                    // type 20 = S_CITYADMIN, WSK model (city administrator)
                    "(261, 1, 20,  'City Admin',      'S_CITYADMIN',   'WSK',  32384, 31604, 32593, 64, 200, 0)," +
                    // type 29 = trader_pa, WSK model (personal assistant trader)
                    "(262, 1, 29,  'Trader',          'trader_pa',     'WSK',  31890, 31492, 32706, 64, 200, 0)," +
                    // type 2600 = TECHFREAK, WSK model (tech dealer)
                    "(263, 1, 2600,'Tech Dealer',     'TECHFREAK',     'WSK',  32242, 31399, 32809, 64, 200, 0)," +
                    // type 215 = DRUGDEALER, WSK model
                    "(264, 1, 215, 'Drug Dealer',     'DRUGDEALER',    'WSK',  31900, 31587, 32482, 64, 200, 0)"
                );
            }
            rs.close();
        }
    }

    /**
     * Apply incremental schema migrations based on {@code PRAGMA user_version}.
     *
     * <p>v0 → v1: add CharInfo fidelity columns (see {@link #FIDELITY_COLUMNS}) to
     * the {@code player_characters} table if missing. Uses
     * {@code PRAGMA table_info('player_characters')} to detect existing columns so
     * the migration is idempotent — freshly-created tables already contain the
     * columns via {@link #createTables()} and this method is a no-op for them.
     *
     * <p>v2 → v3: add {@code script_name} and {@code model_name} TEXT columns to
     * {@code npc_spawns}. Existing rows default to empty string.
     *
     * <p>The migration is one-way (no downgrade). On completion,
     * {@code PRAGMA user_version} is bumped to {@link #CURRENT_SCHEMA_VERSION}.
     */
    private static void migrateSchema() throws SQLException {
        int currentVersion = readSchemaVersion();

        if (currentVersion >= CURRENT_SCHEMA_VERSION) {
            return;
        }

        if (currentVersion < 1) {
            Set<String> existing = getExistingColumns("player_characters");
            try (Statement stmt = connection.createStatement()) {
                for (String[] col : FIDELITY_COLUMNS) {
                    if (!existing.contains(col[0])) {
                        String ddl = "ALTER TABLE player_characters ADD COLUMN "
                            + q(col[0]) + " " + col[1] + " " + col[2];
                        stmt.execute(ddl);
                    }
                }
            }
        }

        if (currentVersion < 3) {
            Set<String> existing = getExistingColumns("npc_spawns");
            try (Statement stmt = connection.createStatement()) {
                if (!existing.contains("script_name")) {
                    stmt.execute("ALTER TABLE npc_spawns ADD COLUMN script_name TEXT DEFAULT ''");
                }
                if (!existing.contains("model_name")) {
                    stmt.execute("ALTER TABLE npc_spawns ADD COLUMN model_name TEXT DEFAULT ''");
                }
                // Fix seed rows that were created with invalid type IDs (33900-33904).
                stmt.execute("UPDATE npc_spawns SET type=191, script_name='S_CITYMERCS_0', model_name='WLDK' WHERE type=33900");
                stmt.execute("UPDATE npc_spawns SET type=20,  script_name='S_CITYADMIN',   model_name='WSK'  WHERE type=33901");
                stmt.execute("UPDATE npc_spawns SET type=29,  script_name='trader_pa',     model_name='WSK'  WHERE type=33902");
                stmt.execute("UPDATE npc_spawns SET type=2600,script_name='TECHFREAK',     model_name='WSK'  WHERE type=33903");
                stmt.execute("UPDATE npc_spawns SET type=215, script_name='DRUGDEALER',    model_name='WSK'  WHERE type=33904");
            }
        }

        if (currentVersion < 4) {
            // v3 → v4: add `pcr` (Resist Piercing) subskill column. Earlier
            // SUBSKILLS array had null at slot 6; SUBSKILL_PCR=6 was added
            // 2026-05-01 after retail differential analysis confirmed PCR
            // lives at S4 position 6. Existing characters default to 0.
            Set<String> existing = getExistingColumns("player_characters");
            try (Statement stmt = connection.createStatement()) {
                if (!existing.contains("pcr")) {
                    stmt.execute("ALTER TABLE player_characters ADD COLUMN \"pcr\" INTEGER DEFAULT 0");
                }
            }
        }

        writeSchemaVersion(CURRENT_SCHEMA_VERSION);

        Out.writeln(Out.Info, "Schema migrated to version " + CURRENT_SCHEMA_VERSION);
    }

    /**
     * Read the current schema version. Uses a portable {@code schema_versions}
     * table (single row, single column) rather than SQLite's
     * {@code PRAGMA user_version} so the same code path works for PostgreSQL.
     * Returns 0 if the table doesn't exist (= legacy / fresh DB).
     */
    private static int readSchemaVersion() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_versions (version INTEGER NOT NULL)");
        }
        // SQLite legacy DBs may have PRAGMA user_version set even though the
        // table is empty; honour that so existing dev/prod DBs migrate cleanly.
        if (!isPostgres) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM schema_versions")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    try (ResultSet pragmaRs = stmt.executeQuery("PRAGMA user_version")) {
                        if (pragmaRs.next() && pragmaRs.getInt(1) > 0) {
                            return pragmaRs.getInt(1);
                        }
                    }
                }
            }
        }
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_versions")) {
            if (rs.next()) {
                int v = rs.getInt(1);
                return rs.wasNull() ? 0 : v;
            }
        }
        return 0;
    }

    private static void writeSchemaVersion(int v) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM schema_versions");
            stmt.execute("INSERT INTO schema_versions (version) VALUES (" + v + ")");
            if (!isPostgres) {
                stmt.execute("PRAGMA user_version = " + v);
            }
        }
    }

    /**
     * Read the set of existing column names on a table.
     * Backend-specific: PRAGMA table_info for SQLite, information_schema for PostgreSQL.
     */
    private static Set<String> getExistingColumns(String table) throws SQLException {
        Set<String> cols = new HashSet<>();
        if (isPostgres) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema = current_schema() AND table_name = ?")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) cols.add(rs.getString(1));
                }
            }
        } else {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
                while (rs.next()) cols.add(rs.getString("name"));
            }
        }
        return cols;
    }

    /**
     * If CSV files exist, import their data to SQLite on first run,
     * then rename the CSVs so we don't import again.
     */
    private static void migrateFromCsv() throws SQLException {
        migrateAccountsCsv();
        migratePlayerCharactersCsv();
    }

    private static void migrateAccountsCsv() throws SQLException {
        File csvFile = new File("database" + File.separator + "accounts.csv");
        if (!csvFile.exists()) return;

        // Check if accounts table already has data
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM accounts")) {
            if (rs.next() && rs.getInt(1) > 0) {
                Out.writeln(Out.Info, "Accounts already migrated, skipping CSV import");
                return;
            }
        }

        Out.writeln(Out.Info, "Migrating accounts.csv to SQLite...");

        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String headerLine = br.readLine();
            if (headerLine == null) return;

            String[] headers = parseCsvLine(headerLine);
            int idIdx = findIndex(headers, "id");
            int usernameIdx = findIndex(headers, "username");
            int passwordIdx = findIndex(headers, "password");
            int char1Idx = findIndex(headers, "char1");
            int char2Idx = findIndex(headers, "char2");
            int char3Idx = findIndex(headers, "char3");
            int char4Idx = findIndex(headers, "char4");
            int statusIdx = findIndex(headers, "status");

            String insertSql = "INSERT INTO accounts (id, username, password, char1, char2, char3, char4, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                String line;
                int count = 0;
                while ((line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    String[] fields = parseCsvLine(line);
                    if (fields.length < headers.length) continue;

                    ps.setInt(1, Integer.parseInt(fields[idIdx].trim()));
                    ps.setString(2, fields[usernameIdx].trim());
                    ps.setString(3, fields[passwordIdx].trim());
                    ps.setInt(4, Integer.parseInt(fields[char1Idx].trim()));
                    ps.setInt(5, Integer.parseInt(fields[char2Idx].trim()));
                    ps.setInt(6, Integer.parseInt(fields[char3Idx].trim()));
                    ps.setInt(7, Integer.parseInt(fields[char4Idx].trim()));
                    ps.setString(8, fields[statusIdx].trim());
                    ps.addBatch();
                    count++;
                }
                ps.executeBatch();
                connection.commit();
                Out.writeln(Out.Info, "Migrated " + count + " accounts from CSV");
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }

            // Rename CSV to indicate migration complete
            csvFile.renameTo(new File("database" + File.separator + "accounts.csv.migrated"));

        } catch (IOException e) {
            Out.writeln(Out.Error, "Error reading accounts.csv: " + e.getMessage());
        }
    }

    private static void migratePlayerCharactersCsv() throws SQLException {
        File csvFile = new File("database" + File.separator + "playerCharacters.csv");
        if (!csvFile.exists()) return;

        // Check if player_characters table already has data
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM player_characters")) {
            if (rs.next() && rs.getInt(1) > 0) {
                Out.writeln(Out.Info, "Player characters already migrated, skipping CSV import");
                return;
            }
        }

        Out.writeln(Out.Info, "Migrating playerCharacters.csv to SQLite...");

        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String headerLine = br.readLine();
            if (headerLine == null) return;

            String[] headers = parseCsvLine(headerLine);

            // Build a dynamic INSERT statement based on columns
            StringBuilder insertCols = new StringBuilder("name");
            StringBuilder insertPlaceholders = new StringBuilder("?");

            // Map CSV header names to SQLite column names
            for (int i = 0; i < PlayerCharacter.MISCLIST.length; i++) {
                if (PlayerCharacter.MISCLIST[i] != null) {
                    insertCols.append(", ").append(q(PlayerCharacter.MISCLIST[i]));
                    insertPlaceholders.append(", ?");
                }
            }
            for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
                insertCols.append(", ").append(q(PlayerCharacter.SKILLS[i] + "_lvl"));
                insertCols.append(", ").append(q(PlayerCharacter.SKILLS[i] + "_pts"));
                insertPlaceholders.append(", ?, ?");
            }
            for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
                if (PlayerCharacter.SUBSKILLS[i] != null) {
                    insertCols.append(", ").append(q(PlayerCharacter.SUBSKILLS[i]));
                    insertPlaceholders.append(", ?");
                }
            }
            insertCols.append(", f2_inventory_cont_id, gogu_inventory_cont_id, qb_inventory_cont_id");
            insertPlaceholders.append(", ?, ?, ?");

            String insertSql = "INSERT INTO player_characters (" + insertCols + ") VALUES (" + insertPlaceholders + ")";

            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                String line;
                int count = 0;
                while ((line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    String[] fields = parseCsvLine(line);
                    if (fields.length < headers.length) continue;

                    int paramIdx = 1;

                    // name
                    ps.setString(paramIdx++, fields[findIndex(headers, "name")].trim());

                    // MISCLIST fields (including id)
                    for (int i = 0; i < PlayerCharacter.MISCLIST.length; i++) {
                        if (PlayerCharacter.MISCLIST[i] != null) {
                            int csvIdx = findIndex(headers, PlayerCharacter.MISCLIST[i]);
                            if (csvIdx >= 0 && csvIdx < fields.length) {
                                ps.setInt(paramIdx++, Integer.parseInt(fields[csvIdx].trim()));
                            } else {
                                ps.setInt(paramIdx++, 0);
                            }
                        }
                    }

                    // Skills
                    for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
                        int lvlIdx = findIndex(headers, PlayerCharacter.SKILLS[i] + "_lvl");
                        int ptsIdx = findIndex(headers, PlayerCharacter.SKILLS[i] + "_pts");
                        ps.setInt(paramIdx++, (lvlIdx >= 0 && lvlIdx < fields.length) ? Integer.parseInt(fields[lvlIdx].trim()) : 0);
                        ps.setInt(paramIdx++, (ptsIdx >= 0 && ptsIdx < fields.length) ? Integer.parseInt(fields[ptsIdx].trim()) : 0);
                    }

                    // Subskills
                    for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
                        if (PlayerCharacter.SUBSKILLS[i] != null) {
                            int csvIdx = findIndex(headers, PlayerCharacter.SUBSKILLS[i]);
                            if (csvIdx >= 0 && csvIdx < fields.length) {
                                ps.setInt(paramIdx++, Integer.parseInt(fields[csvIdx].trim()));
                            } else {
                                ps.setInt(paramIdx++, 0);
                            }
                        }
                    }

                    // Container IDs
                    int f2Idx = findIndex(headers, "F2InventoryContID");
                    int goguIdx = findIndex(headers, "GoguInventoryContID");
                    int qbIdx = findIndex(headers, "QBInventoryContID");
                    ps.setInt(paramIdx++, (f2Idx >= 0 && f2Idx < fields.length) ? Integer.parseInt(fields[f2Idx].trim()) : 0);
                    ps.setInt(paramIdx++, (goguIdx >= 0 && goguIdx < fields.length) ? Integer.parseInt(fields[goguIdx].trim()) : 0);
                    ps.setInt(paramIdx++, (qbIdx >= 0 && qbIdx < fields.length) ? Integer.parseInt(fields[qbIdx].trim()) : 0);

                    ps.addBatch();
                    count++;
                }
                ps.executeBatch();
                connection.commit();
                Out.writeln(Out.Info, "Migrated " + count + " player characters from CSV");
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }

            csvFile.renameTo(new File("database" + File.separator + "playerCharacters.csv.migrated"));

        } catch (IOException e) {
            Out.writeln(Out.Error, "Error reading playerCharacters.csv: " + e.getMessage());
        }
    }

    private static String[] parseCsvLine(String line) {
        // Simple CSV parser that handles quoted fields
        java.util.List<String> fields = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private static int findIndex(String[] headers, String name) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].trim().equals(name)) return i;
        }
        return -1;
    }
}
