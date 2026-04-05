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

import server.database.playerCharacters.PlayerCharacter;
import server.exceptions.StartupException;
import server.tools.Out;

public final class SqliteDatabase {

    /**
     * Schema version owned by the client-data importer track.
     * NOTE: version 1 is reserved for the parallel feature/charinfo-fidelity
     * branch. This track uses version 2. When the two tracks merge, bump
     * the constant and coordinate the migrations in order.
     */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    private static Connection connection;
    private static final String DB_PATH = "database" + File.separator + "ceres.db";

    public static void init() throws StartupException {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);

            // Enable WAL mode for concurrent reads
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA foreign_keys=ON");
                stmt.execute("PRAGMA busy_timeout=5000");
            }

            createTables();
            migrateFromCsv();

            Out.writeln(Out.Info, "SQLite database initialized: " + DB_PATH);
        } catch (ClassNotFoundException e) {
            throw new StartupException("SQLite JDBC driver not found");
        } catch (SQLException e) {
            throw new StartupException("Failed to initialize SQLite database: " + e.getMessage());
        }
    }

    /**
     * Initialize with a custom connection (for testing).
     */
    public static void initWithConnection(Connection conn) throws SQLException {
        connection = conn;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA foreign_keys=ON");
        }
        createTables();
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

            // --- Schema version 2: client-data importer tables ---
            // Driven by PRAGMA user_version so future migrations can be conditional.
            int userVersion = 0;
            try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
                if (rs.next()) {
                    userVersion = rs.getInt(1);
                }
            }

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

            // PRAGMA table_info is available for verifying column presence
            // if a future migration needs to ALTER existing tables.
            if (userVersion < CURRENT_SCHEMA_VERSION) {
                stmt.execute("PRAGMA user_version = " + CURRENT_SCHEMA_VERSION);
            }
        }
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
