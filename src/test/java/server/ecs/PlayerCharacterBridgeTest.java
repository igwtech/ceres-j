package server.ecs;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.SqliteDatabase;
import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.PlayerCharacterManager;
import server.ecs.Components.SkillBlock;

/**
 * End-to-end: load a PlayerCharacter from SQLite, materialize it into the ECS,
 * export it back, and verify all fields round-trip.
 */
public class PlayerCharacterBridgeTest {

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

	private static String q(String col) {
		return "\"" + col + "\"";
	}

	@Test
	public void materializeAndExportRoundTrip() throws Exception {
		// Insert a fully-populated character
		insertFullCharacter(conn, 77, "EcsRoundTrip");

		PlayerCharacterManager.load();
		PlayerCharacter pc = PlayerCharacterManager.getCharacter(77);
		assertNotNull(pc);

		World world = new World();
		Components c = new Components();
		long handle = world.createEntity();
		int e = World.index(handle);

		PlayerCharacterBridge.materialize(c, e, pc);

		// Spot-check component values match the source character
		assertEquals("EcsRoundTrip", c.name.get(e));
		assertEquals(77, c.characterId.get(e));
		assertEquals(6, c.clazz.get(e));
		assertEquals(3, c.profession.get(e));
		assertEquals(5, c.zoneId.get(e));
		assertEquals(2, c.faction.get(e));
		assertEquals(1000, c.posX.get(e));
		assertEquals(2000, c.posY.get(e));
		assertEquals(3000, c.posZ.get(e));
		assertEquals(180, c.orientation.get(e));
		assertEquals(45, c.tilt.get(e));
		assertEquals(10, c.modelHead.get(e));
		assertEquals(14, c.modelBeard.get(e));
		assertEquals(22, c.textureLeg.get(e));

		SkillBlock sb = c.skills.get(e);
		assertNotNull(sb);
		assertEquals(5, sb.skillLvl[PlayerCharacter.STR]);
		assertEquals(1, sb.skillLvl[PlayerCharacter.PSI]);
		// Raw skill pts (bypassing getSkillPts hardcoded override for STR)
		assertEquals(100, sb.skillPts[PlayerCharacter.STR]);
		assertEquals(500, sb.skillPts[PlayerCharacter.PSI]);
		// Raw subskill values (bypassing getSubskillLVL hardcoded override at index 3)
		assertEquals(3, sb.subskillLvl[3]);
		assertEquals(10, sb.subskillLvl[10]);

		// Export into a fresh PlayerCharacter and verify equality
		PlayerCharacter pc2 = new PlayerCharacter();
		PlayerCharacterBridge.exportTo(c, e, pc2);

		assertPlayerCharactersEqual(pc, pc2);
	}

	@Test
	public void positionComponentReflectsMovementUpdate() {
		// Small focused test: writing to the ECS position components and exporting
		// back mirrors what the ported Movement packet handler does.
		World world = new World();
		Components c = new Components();
		long handle = world.createEntity();
		int e = World.index(handle);

		PlayerCharacter pc = new PlayerCharacter();
		pc.setName("Walker");
		pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, 100);
		pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, 200);
		pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, 300);

		PlayerCharacterBridge.materialize(c, e, pc);

		// Simulate a Movement packet writing new coordinates
		c.posX.set(e, 150);
		c.posY.set(e, 250);
		c.posZ.set(e, 350);
		c.orientation.set(e, 90);

		PlayerCharacterBridge.exportTo(c, e, pc);

		assertEquals(150, pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
		assertEquals(250, pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
		assertEquals(350, pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
		assertEquals(90, pc.getMisc(PlayerCharacter.MISC_ORIENTATION));
	}

	@Test
	public void partialExportDoesNotClobberUnsetFields() {
		// If the ECS only has position components, exporting must not overwrite
		// name / skills on the target PlayerCharacter.
		World world = new World();
		Components c = new Components();
		long handle = world.createEntity();
		int e = World.index(handle);

		c.posX.set(e, 42);
		c.posY.set(e, 43);
		c.posZ.set(e, 44);

		PlayerCharacter pc = new PlayerCharacter();
		pc.setName("Original");
		pc.setSkillLVL(PlayerCharacter.STR, 9);

		PlayerCharacterBridge.exportTo(c, e, pc);

		assertEquals("Original", pc.getName());
		assertEquals(9, pc.getSkillLVL(PlayerCharacter.STR));
		assertEquals(42, pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
	}

	private static void assertPlayerCharactersEqual(PlayerCharacter a, PlayerCharacter b) {
		assertEquals(a.getName(), b.getName());
		for (int i = 0; i < PlayerCharacter.MISCLIST.length; i++) {
			if (PlayerCharacter.MISCLIST[i] != null) {
				assertEquals("misc[" + i + "]=" + PlayerCharacter.MISCLIST[i],
						a.getMisc(i), b.getMisc(i));
			}
		}
		for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
			assertEquals("skillLvl[" + i + "]", a.getSkillLVL(i), b.getSkillLVL(i));
			assertEquals("skillPts[" + i + "]", a.getSkillPtsRaw(i), b.getSkillPtsRaw(i));
		}
		for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
			if (PlayerCharacter.SUBSKILLS[i] != null) {
				assertEquals("subskill[" + i + "]",
						a.getSubskillLVLRaw(i), b.getSubskillLVLRaw(i));
			}
		}
	}

	/**
	 * Insert a character row with distinctive values in every column. Mirrors
	 * the insertFullTestCharacter helper in SqlitePlayerCharacterManagerTest so
	 * we exercise the full schema.
	 */
	private void insertFullCharacter(Connection conn, int id, String name) throws Exception {
		StringBuilder cols = new StringBuilder();
		StringBuilder vals = new StringBuilder();

		cols.append("id, name, " + q("class") + ", " + q("profession") + ", " + q("location") + ", " + q("faction") + ", ");
		cols.append(q("model_head") + ", " + q("model_torso") + ", " + q("model_leg") + ", " + q("model_hair") + ", " + q("model_beard") + ", ");
		cols.append(q("texture_head") + ", " + q("texture_torso") + ", " + q("texture_leg") + ", ");
		cols.append(q("x_coordinate") + ", " + q("y_coordinate") + ", " + q("z_coordinate") + ", " + q("orientation") + ", " + q("tilt") + ", " + q("status") + ", ");
		cols.append(q("str_lvl") + ", " + q("str_pts") + ", " + q("dex_lvl") + ", " + q("dex_pts") + ", " +
				q("con_lvl") + ", " + q("con_pts") + ", " + q("int_lvl") + ", " + q("int_pts") + ", " +
				q("psi_lvl") + ", " + q("psi_pts"));

		vals.append("?, ?, 6, 3, 5, 2, ");
		vals.append("10, 11, 12, 13, 14, ");
		vals.append("20, 21, 22, ");
		vals.append("1000, 2000, 3000, 180, 45, 0, ");
		vals.append("5, 100, 4, 200, 3, 300, 2, 400, 1, 500");

		for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
			if (PlayerCharacter.SUBSKILLS[i] != null) {
				cols.append(", ").append(q(PlayerCharacter.SUBSKILLS[i]));
				vals.append(", ").append(i);
			}
		}

		cols.append(", f2_inventory_cont_id, gogu_inventory_cont_id, qb_inventory_cont_id");
		vals.append(", 600, 601, 602");

		String sql = "INSERT INTO player_characters (" + cols + ") VALUES (" + vals + ")";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, id);
			ps.setString(2, name);
			ps.executeUpdate();
		}
	}
}
