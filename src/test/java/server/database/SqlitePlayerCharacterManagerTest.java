package server.database;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.PlayerCharacterManager;

/**
 * Tests for PlayerCharacterManager backed by SQLite.
 * Uses an in-memory SQLite database for isolation.
 */
public class SqlitePlayerCharacterManagerTest {

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        SqliteDatabase.initWithConnection(conn);
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    /** Quote a column name for SQL reserved word safety. */
    private static String q(String col) {
        return "\"" + col + "\"";
    }

    @Test
    public void testCreateCharacterAndSave() throws Exception {
        // Create a character directly in the database
        insertTestCharacter(conn, 1, "TestRunner", 2, 1, 1, 7,
                3, 3, 3, 3, 1,     // skill levels
                0, 0, 0, 0, 0,     // skill pts
                10, 20, 30);        // container IDs

        // Load and verify
        PlayerCharacterManager.load();
        PlayerCharacter pc = PlayerCharacterManager.getCharacter(1);

        assertNotNull("Character should be found", pc);
        assertEquals("TestRunner", pc.getName());
        assertEquals(2, pc.getMisc(PlayerCharacter.MISC_CLASS));
        assertEquals(1, pc.getMisc(PlayerCharacter.MISC_PROFESSION));
        assertEquals(1, pc.getMisc(PlayerCharacter.MISC_LOCATION));
        assertEquals(7, pc.getMisc(PlayerCharacter.MISC_FACTION));
    }

    @Test
    public void testSaveAndReloadCharacter() throws Exception {
        // Create a character, save, clear, reload
        insertTestCharacter(conn, 5, "SaveTest", 4, 2, 3, 1,
                5, 2, 4, 1, 1,
                10, 20, 30, 40, 50,
                100, 200, 300);

        PlayerCharacterManager.load();
        PlayerCharacter pc = PlayerCharacterManager.getCharacter(5);
        assertNotNull(pc);

        // Modify and save
        pc.setName("SaveTestModified");
        PlayerCharacterManager.saveCharacter(pc);

        // Reload from DB
        PlayerCharacterManager.load();
        PlayerCharacter pc2 = PlayerCharacterManager.getCharacter(5);
        assertNotNull(pc2);
        assertEquals("SaveTestModified", pc2.getName());
    }

    @Test
    public void testCharacterDataIntegrity() throws Exception {
        // Test that all fields round-trip correctly through the database.
        // Create character with known values in every field.
        int charId = 42;

        // Build insert with all columns populated
        insertFullTestCharacter(conn, charId, "IntegrityTest");

        PlayerCharacterManager.load();
        PlayerCharacter pc = PlayerCharacterManager.getCharacter(charId);
        assertNotNull("Character should be loaded", pc);

        assertEquals("IntegrityTest", pc.getName());

        // Check MISCLIST fields (non-null ones, excluding id)
        assertEquals(6, pc.getMisc(PlayerCharacter.MISC_CLASS));         // class
        assertEquals(3, pc.getMisc(PlayerCharacter.MISC_PROFESSION));    // profession
        assertEquals(5, pc.getMisc(PlayerCharacter.MISC_LOCATION));      // location
        assertEquals(2, pc.getMisc(PlayerCharacter.MISC_FACTION));       // faction
        assertEquals(10, pc.getMisc(PlayerCharacter.MODEL_HEAD));        // model_head
        assertEquals(11, pc.getMisc(PlayerCharacter.MODEL_TORSO));       // model_torso
        assertEquals(12, pc.getMisc(PlayerCharacter.MODEL_LEG));         // model_leg
        assertEquals(13, pc.getMisc(PlayerCharacter.MODEL_HAIR));        // model_hair
        assertEquals(14, pc.getMisc(PlayerCharacter.MODEL_BEARD));       // model_beard
        assertEquals(20, pc.getMisc(PlayerCharacter.TEXTURE_HEAD));      // texture_head
        assertEquals(21, pc.getMisc(PlayerCharacter.TEXTURE_TORSO));     // texture_torso
        assertEquals(22, pc.getMisc(PlayerCharacter.TEXTURE_LEG));       // texture_leg
        assertEquals(1000, pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
        assertEquals(2000, pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
        assertEquals(3000, pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
        assertEquals(180, pc.getMisc(PlayerCharacter.MISC_ORIENTATION));
        assertEquals(45, pc.getMisc(PlayerCharacter.MISC_TILT));
        assertEquals(0, pc.getMisc(PlayerCharacter.MISC_STATUS));

        // Check skill levels
        assertEquals(5, pc.getSkillLVL(PlayerCharacter.STR));
        assertEquals(4, pc.getSkillLVL(PlayerCharacter.DEX));
        assertEquals(3, pc.getSkillLVL(PlayerCharacter.CON));
        assertEquals(2, pc.getSkillLVL(PlayerCharacter.INT));
        assertEquals(1, pc.getSkillLVL(PlayerCharacter.PSI));

        // Check subskills (non-null entries)
        for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
            if (PlayerCharacter.SUBSKILLS[i] != null) {
                // We set each subskill to its index value
                int expected = i;
                // Note: getSubskillLVL(3) has a hardcoded override returning 5
                if (i == 3) expected = 5;
                assertEquals("Subskill " + PlayerCharacter.SUBSKILLS[i] + " at index " + i,
                        expected, pc.getSubskillLVL(i));
            }
        }

        // Now save the character, reload, and verify it's unchanged
        PlayerCharacterManager.saveCharacter(pc);
        PlayerCharacterManager.load();
        PlayerCharacter pc2 = PlayerCharacterManager.getCharacter(charId);
        assertNotNull(pc2);
        assertEquals(pc.getName(), pc2.getName());
        assertEquals(pc.getMisc(PlayerCharacter.MISC_CLASS), pc2.getMisc(PlayerCharacter.MISC_CLASS));
        assertEquals(pc.getMisc(PlayerCharacter.MISC_X_COORDINATE), pc2.getMisc(PlayerCharacter.MISC_X_COORDINATE));
        assertEquals(pc.getSkillLVL(PlayerCharacter.STR), pc2.getSkillLVL(PlayerCharacter.STR));
    }

    @Test
    public void testConcurrentAccess() throws Exception {
        // Test that concurrent saves don't corrupt data
        insertTestCharacter(conn, 1, "ConcurrentTest", 0, 0, 1, 0,
                3, 3, 3, 3, 1,
                0, 0, 0, 0, 0,
                10, 20, 30);

        PlayerCharacterManager.load();

        final int numThreads = 4;
        final int iterations = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(numThreads);
        final AtomicBoolean error = new AtomicBoolean(false);

        for (int t = 0; t < numThreads; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterations; i++) {
                        PlayerCharacter pc = PlayerCharacterManager.getCharacter(1);
                        if (pc != null) {
                            PlayerCharacterManager.saveCharacter(pc);
                        }
                    }
                } catch (Exception e) {
                    error.set(true);
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        assertFalse("No errors should occur during concurrent access", error.get());

        // Verify character still intact
        PlayerCharacterManager.load();
        PlayerCharacter pc = PlayerCharacterManager.getCharacter(1);
        assertNotNull(pc);
        assertEquals("ConcurrentTest", pc.getName());
    }

    @Test
    public void testDeleteCharacter() throws Exception {
        insertTestCharacter(conn, 10, "ToDelete", 0, 0, 1, 0,
                1, 1, 1, 1, 1,
                0, 0, 0, 0, 0,
                40, 50, 60);

        PlayerCharacterManager.load();
        assertNotNull(PlayerCharacterManager.getCharacter(10));

        PlayerCharacterManager.deleteCharacter(10);
        assertNull(PlayerCharacterManager.getCharacter(10));

        // Verify deleted from DB
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM player_characters WHERE id = 10")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    public void testCheckCharName() throws Exception {
        insertTestCharacter(conn, 1, "UniqueChar", 0, 0, 1, 0,
                1, 1, 1, 1, 1,
                0, 0, 0, 0, 0,
                10, 20, 30);

        PlayerCharacterManager.load();

        // Name already exists (case insensitive)
        assertFalse(PlayerCharacterManager.checkCharName("UniqueChar"));
        assertFalse(PlayerCharacterManager.checkCharName("uniquechar"));

        // Name does not exist
        assertTrue(PlayerCharacterManager.checkCharName("SomeOtherName"));
    }

    @Test
    public void testMultipleCharacters() throws Exception {
        for (int i = 1; i <= 5; i++) {
            insertTestCharacter(conn, i, "Char" + i, i * 2, i, 1, i,
                    i, i, i, i, i,
                    0, 0, 0, 0, 0,
                    i * 10, i * 10 + 1, i * 10 + 2);
        }

        PlayerCharacterManager.load();

        for (int i = 1; i <= 5; i++) {
            PlayerCharacter pc = PlayerCharacterManager.getCharacter(i);
            assertNotNull("Character " + i + " should exist", pc);
            assertEquals("Char" + i, pc.getName());
            assertEquals(i * 2, pc.getMisc(PlayerCharacter.MISC_CLASS));
        }
    }

    // Helper methods

    private void insertTestCharacter(Connection conn, int id, String name,
            int clazz, int profession, int location, int faction,
            int strLvl, int dexLvl, int conLvl, int intLvl, int psiLvl,
            int strPts, int dexPts, int conPts, int intPts, int psiPts,
            int f2ContId, int goguContId, int qbContId) throws Exception {

        String sql = "INSERT INTO player_characters (id, name, " + q("class") + ", " + q("profession") + ", " +
                q("location") + ", " + q("faction") + ", " +
                q("model_head") + ", " + q("model_torso") + ", " + q("model_leg") + ", " + q("model_hair") + ", " + q("model_beard") + ", " +
                q("texture_head") + ", " + q("texture_torso") + ", " + q("texture_leg") + ", " +
                q("x_coordinate") + ", " + q("y_coordinate") + ", " + q("z_coordinate") + ", " + q("orientation") + ", " + q("tilt") + ", " + q("status") + ", " +
                q("str_lvl") + ", " + q("str_pts") + ", " + q("dex_lvl") + ", " + q("dex_pts") + ", " +
                q("con_lvl") + ", " + q("con_pts") + ", " + q("int_lvl") + ", " + q("int_pts") + ", " +
                q("psi_lvl") + ", " + q("psi_pts") + ", " +
                buildSubskillColumns() + ", " +
                "f2_inventory_cont_id, gogu_inventory_cont_id, qb_inventory_cont_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, " +
                "0, 0, 0, 0, 0, " +
                "0, 0, 0, " +
                "0, 0, 0, 0, 0, 0, " +
                "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
                buildSubskillPlaceholders() + ", " +
                "?, ?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setInt(idx++, id);
            ps.setString(idx++, name);
            ps.setInt(idx++, clazz);
            ps.setInt(idx++, profession);
            ps.setInt(idx++, location);
            ps.setInt(idx++, faction);

            ps.setInt(idx++, strLvl);
            ps.setInt(idx++, strPts);
            ps.setInt(idx++, dexLvl);
            ps.setInt(idx++, dexPts);
            ps.setInt(idx++, conLvl);
            ps.setInt(idx++, conPts);
            ps.setInt(idx++, intLvl);
            ps.setInt(idx++, intPts);
            ps.setInt(idx++, psiLvl);
            ps.setInt(idx++, psiPts);

            // Subskills all zero
            for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
                if (PlayerCharacter.SUBSKILLS[i] != null) {
                    ps.setInt(idx++, 0);
                }
            }

            ps.setInt(idx++, f2ContId);
            ps.setInt(idx++, goguContId);
            ps.setInt(idx++, qbContId);

            ps.executeUpdate();
        }
    }

    private void insertFullTestCharacter(Connection conn, int id, String name) throws Exception {
        // Build a complete INSERT with all columns filled with distinctive values
        StringBuilder colsSb = new StringBuilder();
        StringBuilder valsSb = new StringBuilder();

        colsSb.append("id, name, " + q("class") + ", " + q("profession") + ", " + q("location") + ", " + q("faction") + ", ");
        colsSb.append(q("model_head") + ", " + q("model_torso") + ", " + q("model_leg") + ", " + q("model_hair") + ", " + q("model_beard") + ", ");
        colsSb.append(q("texture_head") + ", " + q("texture_torso") + ", " + q("texture_leg") + ", ");
        colsSb.append(q("x_coordinate") + ", " + q("y_coordinate") + ", " + q("z_coordinate") + ", " + q("orientation") + ", " + q("tilt") + ", " + q("status") + ", ");
        colsSb.append(q("str_lvl") + ", " + q("str_pts") + ", " + q("dex_lvl") + ", " + q("dex_pts") + ", " +
                       q("con_lvl") + ", " + q("con_pts") + ", " + q("int_lvl") + ", " + q("int_pts") + ", " +
                       q("psi_lvl") + ", " + q("psi_pts"));

        valsSb.append("?, ?, 6, 3, 5, 2, ");
        valsSb.append("10, 11, 12, 13, 14, ");
        valsSb.append("20, 21, 22, ");
        valsSb.append("1000, 2000, 3000, 180, 45, 0, ");
        valsSb.append("5, 100, 4, 200, 3, 300, 2, 400, 1, 500");

        // Add subskill columns
        for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
            if (PlayerCharacter.SUBSKILLS[i] != null) {
                colsSb.append(", ").append(q(PlayerCharacter.SUBSKILLS[i]));
                valsSb.append(", ").append(i); // set to index value for easy verification
            }
        }

        colsSb.append(", f2_inventory_cont_id, gogu_inventory_cont_id, qb_inventory_cont_id");
        valsSb.append(", 500, 501, 502");

        String sql = "INSERT INTO player_characters (" + colsSb + ") VALUES (" + valsSb + ")";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private String buildSubskillColumns() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
            if (PlayerCharacter.SUBSKILLS[i] != null) {
                if (!first) sb.append(", ");
                sb.append(q(PlayerCharacter.SUBSKILLS[i]));
                first = false;
            }
        }
        return sb.toString();
    }

    private String buildSubskillPlaceholders() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
            if (PlayerCharacter.SUBSKILLS[i] != null) {
                if (!first) sb.append(", ");
                sb.append("?");
                first = false;
            }
        }
        return sb.toString();
    }

    // ---------- CharInfo fidelity / schema migration tests ----------

    @Test
    public void testSchemaMigrationDefaultsApplied() throws Exception {
        // Insert a "legacy-style" row that only specifies the pre-v1 columns;
        // SQLite will auto-fill the new columns with their DEFAULT values. On
        // load(), PlayerCharacter should reflect those defaults.
        insertTestCharacter(conn, 77, "LegacyChar", 2, 1, 1, 1,
                3, 3, 3, 3, 1,
                0, 0, 0, 0, 0,
                70, 71, 72);

        PlayerCharacterManager.load();
        PlayerCharacter pc = PlayerCharacterManager.getCharacter(77);
        assertNotNull(pc);

        assertEquals(100, pc.getHealth());
        assertEquals(100, pc.getMaxHealth());
        assertEquals(100, pc.getPsi());
        assertEquals(100, pc.getMaxPsi());
        assertEquals(100, pc.getStamina());
        assertEquals(100, pc.getMaxStamina());
        assertEquals(100, pc.getSynaptic());
        assertEquals(1001, pc.getCash());
        assertEquals(0, pc.getRank());
        // faction_sympathies column defaults to NULL; the loader parses null
        // as "no override" so the class defaults stay in place. Index 0 is
        // the legacy 10000.0f and index 20 is the 0.0f lowsl slot.
        assertEquals(10000.0f, pc.getFactionSympathy(0), 0.0f);
        assertEquals(0.0f, pc.getFactionSympathy(20), 0.0f);
    }

    @Test
    public void testPoolsCashRankRoundTrip() throws Exception {
        insertTestCharacter(conn, 78, "PoolsRoundTrip", 4, 2, 1, 1,
                3, 3, 3, 3, 3,
                0, 0, 0, 0, 0,
                80, 81, 82);

        PlayerCharacterManager.load();
        PlayerCharacter pc = PlayerCharacterManager.getCharacter(78);
        assertNotNull(pc);

        pc.setHealth(321);
        pc.setMaxHealth(456);
        pc.setPsi(55);
        pc.setMaxPsi(99);
        pc.setStamina(12);
        pc.setMaxStamina(34);
        pc.setSynaptic(77);
        pc.setCash(13579);
        pc.setRank(9);

        PlayerCharacterManager.saveCharacter(pc);
        PlayerCharacterManager.load();
        PlayerCharacter reloaded = PlayerCharacterManager.getCharacter(78);
        assertNotNull(reloaded);

        assertEquals(321, reloaded.getHealth());
        assertEquals(456, reloaded.getMaxHealth());
        assertEquals(55, reloaded.getPsi());
        assertEquals(99, reloaded.getMaxPsi());
        assertEquals(12, reloaded.getStamina());
        assertEquals(34, reloaded.getMaxStamina());
        assertEquals(77, reloaded.getSynaptic());
        assertEquals(13579, reloaded.getCash());
        assertEquals(9, reloaded.getRank());
    }

    @Test
    public void testFactionSympathiesRoundTrip() throws Exception {
        insertTestCharacter(conn, 79, "SympathiesRoundTrip", 2, 1, 1, 1,
                3, 3, 3, 3, 1,
                0, 0, 0, 0, 0,
                90, 91, 92);

        PlayerCharacterManager.load();
        PlayerCharacter pc = PlayerCharacterManager.getCharacter(79);
        assertNotNull(pc);

        pc.setFactionSympathy(3, 5000.0f);
        pc.setFactionSympathy(15, 250.5f);

        PlayerCharacterManager.saveCharacter(pc);

        // Inspect the raw column to confirm the hand-rolled serializer works.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT faction_sympathies FROM player_characters WHERE id = ?")) {
            ps.setInt(1, 79);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                String json = rs.getString(1);
                assertNotNull(json);
                assertTrue("json should contain 5000.0, got: " + json,
                        json.contains("5000.0"));
                assertTrue("json should contain 250.5, got: " + json,
                        json.contains("250.5"));
            }
        }

        PlayerCharacterManager.load();
        PlayerCharacter reloaded = PlayerCharacterManager.getCharacter(79);
        assertNotNull(reloaded);
        assertEquals(5000.0f, reloaded.getFactionSympathy(3), 0.0f);
        assertEquals(250.5f, reloaded.getFactionSympathy(15), 0.0f);
        // Unmodified slot still defaults to 10000.0f
        assertEquals(10000.0f, reloaded.getFactionSympathy(0), 0.0f);
    }
}
