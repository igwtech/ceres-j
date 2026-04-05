package server.database.playerCharacters;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;

import server.database.SqliteDatabase;
import server.database.accounts.Account;
import server.database.items.Item;
import server.database.items.ItemContainer;
import server.database.items.ItemManager;
import server.exceptions.StartupException;
import server.tools.Out;

public class PlayerCharacterManager {

	private static LinkedList<PlayerCharacter> pcList;
	private static int pcCounter;

	public static void init() throws StartupException {
		load();
		findpcCounter();
	}

	public static void stopServer() {
		save();
	}

	public static void load() {
		pcList = new LinkedList<PlayerCharacter>();
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) {
			Out.writeln(Out.Error, "No database connection for PlayerCharacterManager");
			return;
		}

		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT * FROM player_characters")) {

			while (rs.next()) {
				PlayerCharacter pc = new PlayerCharacter();
				pc.setName(rs.getString("name"));

				// Load MISCLIST fields
				for (int i = 0; i < PlayerCharacter.MISCLIST.length; i++) {
					if (PlayerCharacter.MISCLIST[i] != null) {
						pc.setMisc(i, rs.getInt(PlayerCharacter.MISCLIST[i]));
					} else {
						pc.setMisc(i, 0);
					}
				}

				// Load skills (lvl + pts)
				for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
					pc.setSkillLVL(i, rs.getInt(PlayerCharacter.SKILLS[i] + "_lvl"));
					pc.setSkillPTS(i, rs.getInt(PlayerCharacter.SKILLS[i] + "_pts"));
				}

				// Load subskills
				for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
					if (PlayerCharacter.SUBSKILLS[i] != null) {
						pc.setSubskillLVL(i, rs.getInt(PlayerCharacter.SUBSKILLS[i]));
					} else {
						pc.setSubskillLVL(i, 0);
					}
				}

				// Load CharInfo fidelity fields (schema v1). If any column is
				// missing (e.g. a pre-migration DB that somehow skipped the
				// migration path) we catch per-character and keep the
				// PlayerCharacter default values.
				try {
					pc.setHealth(rs.getInt("health"));
					pc.setMaxHealth(rs.getInt("max_health"));
					pc.setPsi(rs.getInt("psi_pool"));
					pc.setMaxPsi(rs.getInt("max_psi_pool"));
					pc.setStamina(rs.getInt("stamina"));
					pc.setMaxStamina(rs.getInt("max_stamina"));
					pc.setSynaptic(rs.getInt("synaptic"));
					pc.setCash(rs.getInt("cash"));
					pc.setRank(rs.getInt("rank"));

					String symJson = rs.getString("faction_sympathies");
					float[] parsed = parseFactionSympathies(symJson);
					if (parsed != null) {
						pc.setFactionSympathies(parsed);
					}

					// Per-skill xp/rate/max overrides — leave the sentinel in
					// place so the getter falls back to class-based defaults.
					for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
						String base = PlayerCharacter.SKILLS[i];
						int xp = rs.getInt(base + "_xp");
						if (xp != Integer.MIN_VALUE) pc.setSkillXP(i, xp);
						int rate = rs.getInt(base + "_rate");
						if (rate != Integer.MIN_VALUE) pc.setSkillRate(i, rate);
						int max = rs.getInt(base + "_max");
						if (max != Integer.MIN_VALUE) pc.setSkillMax(i, max);
					}
				} catch (SQLException fidelityErr) {
					Out.writeln(Out.Warning,
						"CharInfo fidelity columns missing for character '" + pc.getName()
							+ "', using defaults: " + fidelityErr.getMessage());
				}

				int[] contId = new int[3];
				contId[0] = rs.getInt("f2_inventory_cont_id");
				contId[1] = rs.getInt("gogu_inventory_cont_id");
				contId[2] = rs.getInt("qb_inventory_cont_id");

				pc.initContainer(contId);
				pcList.add(pc);
			}
		} catch (SQLException e) {
			Out.writeln(Out.Error, "Error loading player characters: " + e.getMessage());
		}
		Out.writeln(Out.Info, "Loaded " + pcList.size() + " player characters");
	}

	public static void save() {
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) return;

		try {
			conn.setAutoCommit(false);

			synchronized (pcList) {
				for (Iterator<PlayerCharacter> j = pcList.iterator(); j.hasNext(); ) {
					PlayerCharacter pc = j.next();
					saveCharacterInternal(conn, pc);
				}
			}

			conn.commit();
		} catch (SQLException e) {
			try {
				conn.rollback();
			} catch (SQLException ex) {
				Out.writeln(Out.Error, "Error rolling back player characters save: " + ex.getMessage());
			}
			Out.writeln(Out.Error, "Error saving player characters: " + e.getMessage());
		} finally {
			try {
				conn.setAutoCommit(true);
			} catch (SQLException e) {
				Out.writeln(Out.Error, "Error resetting auto-commit: " + e.getMessage());
			}
		}
	}

	/**
	 * Immediately persist a single character to the database.
	 * This fixes the save bug by saving on every change instead of only at shutdown.
	 */
	public static void saveCharacter(PlayerCharacter pc) {
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) return;

		try {
			saveCharacterInternal(conn, pc);
		} catch (SQLException e) {
			Out.writeln(Out.Error, "Error saving character " + pc.getName() + ": " + e.getMessage());
		}
	}

	private static void saveCharacterInternal(Connection conn, PlayerCharacter pc) throws SQLException {
		StringBuilder sql = new StringBuilder();
		sql.append("INSERT OR REPLACE INTO player_characters (id, name");

		// MISCLIST columns (excluding id which is the PK)
		for (int i = 0; i < PlayerCharacter.MISCLIST.length; i++) {
			if (PlayerCharacter.MISCLIST[i] != null && i != PlayerCharacter.MISC_ID) {
				sql.append(", ").append(SqliteDatabase.q(PlayerCharacter.MISCLIST[i]));
			}
		}

		// Skills
		for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
			sql.append(", ").append(SqliteDatabase.q(PlayerCharacter.SKILLS[i] + "_lvl"));
			sql.append(", ").append(SqliteDatabase.q(PlayerCharacter.SKILLS[i] + "_pts"));
		}

		// Subskills
		for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
			if (PlayerCharacter.SUBSKILLS[i] != null) {
				sql.append(", ").append(SqliteDatabase.q(PlayerCharacter.SUBSKILLS[i]));
			}
		}

		// CharInfo fidelity columns (schema v1) — same order as SqliteDatabase.FIDELITY_COLUMNS.
		for (String[] col : SqliteDatabase.FIDELITY_COLUMNS) {
			sql.append(", ").append(SqliteDatabase.q(col[0]));
		}

		sql.append(", f2_inventory_cont_id, gogu_inventory_cont_id, qb_inventory_cont_id");
		sql.append(") VALUES (?");

		// name
		sql.append(", ?");

		// MISCLIST (excluding id)
		for (int i = 0; i < PlayerCharacter.MISCLIST.length; i++) {
			if (PlayerCharacter.MISCLIST[i] != null && i != PlayerCharacter.MISC_ID) {
				sql.append(", ?");
			}
		}

		// Skills
		for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
			sql.append(", ?, ?");
		}

		// Subskills
		for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
			if (PlayerCharacter.SUBSKILLS[i] != null) {
				sql.append(", ?");
			}
		}

		// Fidelity column placeholders
		for (int i = 0; i < SqliteDatabase.FIDELITY_COLUMNS.length; i++) {
			sql.append(", ?");
		}

		sql.append(", ?, ?, ?)");

		try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
			int paramIdx = 1;

			// id (PK)
			ps.setInt(paramIdx++, pc.getMisc(PlayerCharacter.MISC_ID));

			// name
			ps.setString(paramIdx++, pc.getName());

			// MISCLIST fields (excluding id)
			for (int i = 0; i < PlayerCharacter.MISCLIST.length; i++) {
				if (PlayerCharacter.MISCLIST[i] != null && i != PlayerCharacter.MISC_ID) {
					ps.setInt(paramIdx++, pc.getMisc(i));
				}
			}

			// Skills
			for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
				ps.setInt(paramIdx++, pc.getSkillLVL(i));
				ps.setInt(paramIdx++, pc.getSkillPts(i));
			}

			// Subskills
			for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
				if (PlayerCharacter.SUBSKILLS[i] != null) {
					ps.setInt(paramIdx++, pc.getSubskillLVL(i));
				}
			}

			// CharInfo fidelity fields — must stay in FIDELITY_COLUMNS order.
			// For skill xp/rate/max we call the getters (not the raw fields) so
			// sentinel-loaded characters get their class-based defaults
			// concretized on first save. This is a one-way lossy transition
			// that removes the "unset" marker from the DB going forward.
			ps.setInt(paramIdx++, pc.getHealth());
			ps.setInt(paramIdx++, pc.getMaxHealth());
			ps.setInt(paramIdx++, pc.getPsi());
			ps.setInt(paramIdx++, pc.getMaxPsi());
			ps.setInt(paramIdx++, pc.getStamina());
			ps.setInt(paramIdx++, pc.getMaxStamina());
			ps.setInt(paramIdx++, pc.getSynaptic());
			ps.setInt(paramIdx++, pc.getCash());
			ps.setInt(paramIdx++, pc.getRank());
			ps.setString(paramIdx++, formatFactionSympathies(pc.getFactionSympathies()));
			for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
				ps.setInt(paramIdx++, pc.getSkillXP(i));
				ps.setInt(paramIdx++, pc.getSkillRate(i));
				ps.setInt(paramIdx++, pc.getSkillMax(i));
			}

			// Container IDs
			ps.setInt(paramIdx++, pc.getContainer(PlayerCharacter.PLAYERCONTAINER_F2).getContainerID());
			ps.setInt(paramIdx++, pc.getContainer(PlayerCharacter.PLAYERCONTAINER_GOGU).getContainerID());
			ps.setInt(paramIdx++, pc.getContainer(PlayerCharacter.PLAYERCONTAINER_QB).getContainerID());

			ps.executeUpdate();
		}
	}

	/**
	 * Parse a faction sympathies JSON-like array string such as
	 * {@code "[10000.0,10000.0,...,0.0]"}. Returns {@code null} when the input
	 * is null, empty, malformed, or contains the wrong number of entries —
	 * the caller leaves the {@link PlayerCharacter} defaults in place in that
	 * case. Whitespace around values is tolerated.
	 */
	private static float[] parseFactionSympathies(String json) {
		if (json == null) return null;
		String trimmed = json.trim();
		if (trimmed.isEmpty()) return null;
		if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null;
		String inner = trimmed.substring(1, trimmed.length() - 1).trim();
		if (inner.isEmpty()) return null;
		String[] parts = inner.split(",");
		if (parts.length != PlayerCharacter.FACTION_SYMPATHY_COUNT) return null;
		float[] out = new float[parts.length];
		for (int i = 0; i < parts.length; i++) {
			try {
				out[i] = Float.parseFloat(parts[i].trim());
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return out;
	}

	/**
	 * Serialize a faction sympathies array to the JSON-like string format
	 * read by {@link #parseFactionSympathies(String)}. Never returns null —
	 * a null or short input is padded to {@link PlayerCharacter#FACTION_SYMPATHY_COUNT}
	 * with the legacy 10000.0f default so the round-trip stays stable.
	 */
	private static String formatFactionSympathies(float[] arr) {
		int n = PlayerCharacter.FACTION_SYMPATHY_COUNT;
		float[] padded = new float[n];
		for (int i = 0; i < n; i++) padded[i] = 10000.0f;
		if (arr != null) {
			int len = Math.min(arr.length, n);
			for (int i = 0; i < len; i++) padded[i] = arr[i];
		}
		StringBuilder sb = new StringBuilder();
		sb.append('[');
		for (int i = 0; i < n; i++) {
			if (i > 0) sb.append(',');
			sb.append(Float.toString(padded[i]));
		}
		sb.append(']');
		return sb.toString();
	}

	private static void findpcCounter() {
		int id = 0;
		for (Iterator<PlayerCharacter> i = pcList.iterator(); i.hasNext(); ) {
			PlayerCharacter pc = i.next();
			if (pc.getMisc(PlayerCharacter.MISC_ID) > id) {
				id = pc.getMisc(PlayerCharacter.MISC_ID);
			}
		}
		pcCounter = id + 1;
	}

	public static PlayerCharacter getCharacter(int charid) {
		synchronized (pcList) {
			ListIterator<PlayerCharacter> it = pcList.listIterator();
			while (it.hasNext()) {
				PlayerCharacter pc = it.next();
				if (pc.getMisc(PlayerCharacter.MISC_ID) == charid) return pc;
			}
			return null;
		}
	}

	public static boolean checkCharName(String name) {
		synchronized (pcList) {
			ListIterator<PlayerCharacter> it = pcList.listIterator();
			while (it.hasNext()) {
				PlayerCharacter pc = it.next();
				if (pc.getName().equalsIgnoreCase(name)) return false;
			}
			return true;
		}
	}

	public static boolean createCharacter(PlayerCharacter pc, Account account, int spot) {
		switch (pc.getMisc(PlayerCharacter.MISC_CLASS) / 2) { // TODO alot here
		case 0: { //pe
			pc.setSkillLVL(PlayerCharacter.STR, 3);
			pc.setSkillLVL(PlayerCharacter.DEX, 3);
			pc.setSkillLVL(PlayerCharacter.CON, 3);
			pc.setSkillLVL(PlayerCharacter.INT, 3);
			pc.setSkillLVL(PlayerCharacter.PSI, 1);
			break;
		}
		case 1: { //spy
			pc.setSkillLVL(PlayerCharacter.STR, 2);
			pc.setSkillLVL(PlayerCharacter.DEX, 3);
			pc.setSkillLVL(PlayerCharacter.CON, 2);
			pc.setSkillLVL(PlayerCharacter.INT, 5);
			pc.setSkillLVL(PlayerCharacter.PSI, 1);
			break;
		}
		case 2: { //tank
			pc.setSkillLVL(PlayerCharacter.STR, 5);
			pc.setSkillLVL(PlayerCharacter.DEX, 2);
			pc.setSkillLVL(PlayerCharacter.CON, 4);
			pc.setSkillLVL(PlayerCharacter.INT, 1);
			pc.setSkillLVL(PlayerCharacter.PSI, 1);
			break;
		}
		case 3: { //monk
			pc.setSkillLVL(PlayerCharacter.STR, 1);
			pc.setSkillLVL(PlayerCharacter.DEX, 2);
			pc.setSkillLVL(PlayerCharacter.CON, 1);
			pc.setSkillLVL(PlayerCharacter.INT, 4);
			pc.setSkillLVL(PlayerCharacter.PSI, 5);
			break;
		}
		}

		// we should create some items too
		pc.setMisc(PlayerCharacter.MISC_LOCATION, 1); // TODO something more usefull?
		int[] Contid = new int[3];

		Contid[0] = ItemManager.getFreeContId();
		Contid[1] = ItemManager.getFreeContId();
		Contid[2] = ItemManager.getFreeContId();

		pc.initContainer(Contid);
		ItemContainer Cont = pc.getContainer(PlayerCharacter.PLAYERCONTAINER_F2);

		short[] tokens = new short[17];
		for (int i = 0; i < 17; i++) {
			tokens[i] = 0;
		}

		tokens[Item.TOKENS_CURRCOND] = 255;
		tokens[Item.TOKENS_MAXCOND] = 255;
		tokens[Item.TOKENS_DMG] = 200;
		tokens[Item.TOKENS_FREQUENCY] = 200;
		tokens[Item.TOKENS_HANDLING] = 200;
		tokens[Item.TOKENS_RANGE] = 200;
		tokens[Item.TOKENS_AMMOUSES] = 3;
		tokens[Item.TOKENS_ITEMSONSTACK] = 5;

		ItemManager.createItem(Cont, -1, 19, tokens, Item.ITEMFLAG_WEAPON, 0);
		ItemManager.createItem(Cont, -1, 35, tokens, Item.ITEMFLAG_USES | Item.ITEMFLAG_STACK, 0);
		ItemManager.createItem(Cont, -1, 101, tokens, Item.ITEMFLAG_SPELL, 0);
		ItemManager.createItem(Cont, -1, 100, tokens, Item.ITEMFLAG_SPELL, 0);

		synchronized (pcList) {
			pc.setMisc(PlayerCharacter.MISC_ID, pcCounter);
			pcCounter++;

			pcList.add(pc);
			account.setChar(spot, pc.getMisc(PlayerCharacter.MISC_ID));
		}

		// Immediately persist the new character
		saveCharacter(pc);

		return true;
	}

	public static void deleteCharacter(int i) {
		synchronized (pcList) {
			ListIterator<PlayerCharacter> it = pcList.listIterator();
			while (it.hasNext()) {
				PlayerCharacter pc = it.next();
				if (pc.getMisc(PlayerCharacter.MISC_ID) == i) {
					pc.deleteAll();
					it.remove();
					deleteCharacterFromDb(i);
					return;
				}
			}
		}
	}

	private static void deleteCharacterFromDb(int id) {
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) return;

		try (PreparedStatement ps = conn.prepareStatement("DELETE FROM player_characters WHERE id = ?")) {
			ps.setInt(1, id);
			ps.executeUpdate();
		} catch (SQLException e) {
			Out.writeln(Out.Error, "Error deleting character id=" + id + ": " + e.getMessage());
		}
	}
}
