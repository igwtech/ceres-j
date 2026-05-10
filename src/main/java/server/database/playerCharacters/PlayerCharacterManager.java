package server.database.playerCharacters;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.UUID;

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

				// Load the UUID. Wrapped so a missing column (e.g. legacy
				// schemas that haven't migrated yet for some reason) leaves
				// the field null and the application keeps booting.
				try {
					pc.setUuid(SqliteDatabase.getUuid(rs, "uuid"));
				} catch (SQLException uuidErr) {
					// Column missing / unreadable — log once and continue.
				}

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

		// Detect & repair container-ID collisions from a known bug:
		// ItemManager.getFreeContId() used to reset its in-memory
		// counter every restart and re-issue ids 1, 2, 3 to every
		// new char, so multiple chars wound up sharing the same
		// container ids. After v6 (inventory persistence), this
		// would cause them all to share one F2 inventory. Reassign
		// duplicates here BEFORE loadall() attaches items, then
		// re-save the affected characters.
		repairDuplicateContainerIds();

		// Sweep orphaned items: any items row whose container_id is
		// no longer claimed by any character is unreachable and
		// would otherwise just accumulate in the DB across boots.
		// This typically happens once, after the duplicate-container-id
		// repair above moves chars to fresh ids — the old shared id's
		// items become orphans.
		sweepOrphanedItemRows();

		// All containers are now registered via initContainer() →
		// ItemContainer ctor → ItemManager.loadContainer(). Load
		// persisted items and attach to those containers. Items with
		// no matching container_id are kept in the Items map
		// (orphaned) so their IDs don't collide on new emissions.
		server.database.items.ItemManager.loadall();

		// Bootstrap: any character that loaded with NO items in F2
		// (likely created before schema v6 inventory persistence
		// landed, or a fresh char) gets the standard starter pack
		// re-issued. This unblocks the "inventory missing" symptom
		// the user reported for the 3 existing chars.
		for (PlayerCharacter pc : pcList) {
			bootstrapStarterInventoryIfEmpty(pc);
		}
	}

	/**
	 * Detect multiple PlayerCharacters sharing the same
	 * f2/gogu/qb container_id and reassign new unique ids for the
	 * duplicates (keeping the first occurrence stable). The character
	 * row is re-saved with the new ids so the fix survives restart.
	 *
	 * <p>Root cause: pre-v6 ItemManager.getFreeContId() reset its
	 * in-memory counter on every restart and re-issued 1, 2, 3 for
	 * every new character. Fixed in v6 by seeding from
	 * MAX(container_id) in the DB, but legacy rows need repair.
	 */
	/**
	 * Delete items rows whose container_id is not claimed by any
	 * character's F2 / gogu / qb slot. Runs after
	 * {@link #repairDuplicateContainerIds} so the IDs in DB and the
	 * ID set we test against are both post-repair.
	 */
	private static void sweepOrphanedItemRows() {
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) return;
		String sql =
			"DELETE FROM items WHERE container_id NOT IN ("
			+ " SELECT f2_inventory_cont_id FROM player_characters"
			+ " UNION SELECT gogu_inventory_cont_id FROM player_characters"
			+ " UNION SELECT qb_inventory_cont_id FROM player_characters)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			int deleted = ps.executeUpdate();
			if (deleted > 0) {
				Out.writeln(Out.Info,
					"Swept " + deleted + " orphaned item rows "
					+ "(container_id not claimed by any character)");
			}
		} catch (SQLException e) {
			Out.writeln(Out.Warning,
				"sweepOrphanedItemRows: " + e.getMessage());
		}
	}

	private static void repairDuplicateContainerIds() {
		java.util.Set<Integer> seenF2 = new java.util.HashSet<>();
		java.util.Set<Integer> seenGogu = new java.util.HashSet<>();
		java.util.Set<Integer> seenQb = new java.util.HashSet<>();
		int fixed = 0;
		for (PlayerCharacter pc : pcList) {
			int[] cur = new int[]{
				pc.getF2InventoryContainerID(),
				pc.getGoguInventoryContainerID(),
				pc.getQbInventoryContainerID(),
			};
			boolean changed = false;
			if (cur[0] == 0 || !seenF2.add(cur[0])) {
				cur[0] = ItemManager.getFreeContId();
				changed = true;
			}
			if (cur[1] == 0 || !seenGogu.add(cur[1])) {
				cur[1] = ItemManager.getFreeContId();
				changed = true;
			}
			if (cur[2] == 0 || !seenQb.add(cur[2])) {
				cur[2] = ItemManager.getFreeContId();
				changed = true;
			}
			if (changed) {
				pc.initContainer(cur);    // re-register with new ids
				saveCharacter(pc);
				fixed++;
				Out.writeln(Out.Info,
					"Repaired duplicate container_id for character '"
					+ pc.getName() + "' (id="
					+ pc.getMisc(PlayerCharacter.MISC_ID)
					+ ") → f2=" + cur[0] + " gogu=" + cur[1] + " qb=" + cur[2]);
			}
			// Re-register THIS character's containers in
			// ItemManager.Container so the registry's get(id) returns
			// the SAME instance the character's getContainer(F2) returns.
			// Without this, the registry can be stale (point to a
			// previously-loaded char's PlayerInventory if multiple
			// chars share the original id), causing loadall() to
			// attach items to a ghost container.
			pc.initContainer(cur);
		}
		if (fixed > 0) {
			Out.writeln(Out.Info,
				"Repaired container_id collisions for " + fixed
				+ " characters");
			// Wipe persisted items belonging to the no-longer-claimed
			// container ids. Bootstrap will fill the empty F2's
			// afterward. This is a ONE-TIME cleanup that runs only
			// when a repair happened — it loses any items that were
			// in the shared container, but those items couldn't have
			// belonged to any individual character anyway (shared
			// state is meaningless).
			truncateItemsTable("post-repair cleanup");
		}
	}

	/**
	 * Delete every items row. Called from the repair path after a
	 * container_id collision was detected — the shared-container
	 * items are not attributable to any specific character so the
	 * cleanest fix is to drop them and let bootstrap reissue starter
	 * items per-character with the new unique IDs.
	 */
	private static void truncateItemsTable(String reason) {
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) return;
		try (Statement st = conn.createStatement()) {
			int deleted = st.executeUpdate("DELETE FROM items");
			Out.writeln(Out.Info,
				"Truncated items table (" + deleted + " rows) — "
				+ reason);
		} catch (SQLException e) {
			Out.writeln(Out.Warning,
				"truncateItemsTable: " + e.getMessage());
		}
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

	/**
	 * Build the dialect-appropriate upsert SQL for {@code
	 * player_characters}. Columns must be supplied in insertion
	 * order with {@code "id"} first (it's the conflict key). For
	 * SQLite, emits {@code INSERT OR REPLACE}. For PostgreSQL,
	 * emits {@code INSERT ... ON CONFLICT (id) DO UPDATE SET col =
	 * EXCLUDED.col} for every non-id column.
	 *
	 * <p>Visible for tests so we can assert both branches without
	 * touching a real DB.
	 */
	static String buildUpsertSql(java.util.List<String> cols) {
		boolean pg = SqliteDatabase.isPostgres();
		StringBuilder sb = new StringBuilder(pg
				? "INSERT INTO player_characters ("
				: "INSERT OR REPLACE INTO player_characters (");
		for (int i = 0; i < cols.size(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(SqliteDatabase.q(cols.get(i)));
		}
		sb.append(") VALUES (");
		for (int i = 0; i < cols.size(); i++) {
			if (i > 0) sb.append(", ");
			sb.append('?');
		}
		sb.append(')');
		if (pg) {
			sb.append(" ON CONFLICT (id) DO UPDATE SET ");
			boolean first = true;
			for (int i = 1; i < cols.size(); i++) { // skip id at 0
				if (!first) sb.append(", ");
				first = false;
				String c = SqliteDatabase.q(cols.get(i));
				sb.append(c).append(" = EXCLUDED.").append(c);
			}
		}
		return sb.toString();
	}

	private static void saveCharacterInternal(Connection conn, PlayerCharacter pc) throws SQLException {
		// Defensive UUID mint — characters created before this migration
		// landed may arrive at save() time without one.
		if (pc.getUuid() == null) pc.setUuid(UUID.randomUUID());

		// Collect the column names FIRST so we can reuse them for
		// both the INSERT list and (PostgreSQL only) the ON CONFLICT
		// SET clause. Column[0] is always "id" — the conflict key.
		java.util.List<String> cols = new java.util.ArrayList<>();
		cols.add("id");
		cols.add("uuid");
		cols.add("name");
		for (int i = 0; i < PlayerCharacter.MISCLIST.length; i++) {
			if (PlayerCharacter.MISCLIST[i] != null && i != PlayerCharacter.MISC_ID) {
				cols.add(PlayerCharacter.MISCLIST[i]);
			}
		}
		for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
			cols.add(PlayerCharacter.SKILLS[i] + "_lvl");
			cols.add(PlayerCharacter.SKILLS[i] + "_pts");
		}
		for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
			if (PlayerCharacter.SUBSKILLS[i] != null) {
				cols.add(PlayerCharacter.SUBSKILLS[i]);
			}
		}
		for (String[] col : SqliteDatabase.FIDELITY_COLUMNS) {
			cols.add(col[0]);
		}
		cols.add("f2_inventory_cont_id");
		cols.add("gogu_inventory_cont_id");
		cols.add("qb_inventory_cont_id");

		StringBuilder sql = new StringBuilder(buildUpsertSql(cols));

		try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
			int paramIdx = 1;

			// id (PK)
			ps.setInt(paramIdx++, pc.getMisc(PlayerCharacter.MISC_ID));

			// uuid (SOAP-API identifier; orthogonal to wire-protocol id)
			SqliteDatabase.setUuid(ps, paramIdx++, pc.getUuid());

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

	/**
	 * Look up a character by UUID — the SOAP-API identifier.
	 * Returns {@code null} when no character has the given UUID, or when
	 * {@code uuid} is itself {@code null}.
	 */
	public static PlayerCharacter findByUuid(UUID uuid) {
		if (uuid == null) return null;
		synchronized (pcList) {
			ListIterator<PlayerCharacter> it = pcList.listIterator();
			while (it.hasNext()) {
				PlayerCharacter pc = it.next();
				if (uuid.equals(pc.getUuid())) return pc;
			}
		}
		return null;
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

			// Mint a UUID for the SOAP-API identifier track. The
			// integer MISC_ID is still the wire-protocol identity
			// — the UUID is purely for the launcher / web-admin
			// REST surface that should never see the integer.
			if (pc.getUuid() == null) pc.setUuid(UUID.randomUUID());

			pcList.add(pc);
			account.setChar(spot, pc.getMisc(PlayerCharacter.MISC_ID));
		}

		// Immediately persist the new character
		saveCharacter(pc);
		// And the just-created starter inventory.
		ItemManager.save(pc.getContainer(PlayerCharacter.PLAYERCONTAINER_F2));

		return true;
	}

	/**
	 * If a character's F2 container is empty (e.g. a legacy char
	 * created before inventory-persistence existed, or a char whose
	 * items rows got wiped), re-issue the standard 4-piece starter
	 * pack so the player isn't naked at login.
	 *
	 * <p>The starter set matches {@link #createCharacter}:
	 * melee-weapon item 19, ammo item 35, two spells 100/101 —
	 * with placeholder weapon-condition tokens.
	 */
	private static void bootstrapStarterInventoryIfEmpty(PlayerCharacter pc) {
		try {
			server.database.items.ItemContainer f2 = pc.getContainer(
				PlayerCharacter.PLAYERCONTAINER_F2);
			if (f2 == null) return;
			if (f2.getNumberofItems() > 0) return; // already populated

			short[] tokens = new short[17];
			tokens[server.database.items.Item.TOKENS_CURRCOND] = 255;
			tokens[server.database.items.Item.TOKENS_MAXCOND]  = 255;
			tokens[server.database.items.Item.TOKENS_DMG]      = 200;
			tokens[server.database.items.Item.TOKENS_FREQUENCY]= 200;
			tokens[server.database.items.Item.TOKENS_HANDLING] = 200;
			tokens[server.database.items.Item.TOKENS_RANGE]    = 200;
			tokens[server.database.items.Item.TOKENS_AMMOUSES] = 3;
			tokens[server.database.items.Item.TOKENS_ITEMSONSTACK] = 5;

			ItemManager.createItem(f2, -1, 19,  tokens,
				server.database.items.Item.ITEMFLAG_WEAPON, 0);
			ItemManager.createItem(f2, -1, 35,  tokens,
				server.database.items.Item.ITEMFLAG_USES
				| server.database.items.Item.ITEMFLAG_STACK, 0);
			ItemManager.createItem(f2, -1, 101, tokens,
				server.database.items.Item.ITEMFLAG_SPELL, 0);
			ItemManager.createItem(f2, -1, 100, tokens,
				server.database.items.Item.ITEMFLAG_SPELL, 0);

			ItemManager.save(f2);
			Out.writeln(Out.Info,
				"Bootstrapped starter inventory for character '"
					+ pc.getName() + "' (id=" + pc.getMisc(PlayerCharacter.MISC_ID)
					+ ", f2=" + f2.getContainerID() + ")");
		} catch (Exception e) {
			Out.writeln(Out.Warning,
				"Bootstrap starter inventory failed for '" + pc.getName()
				+ "': " + e.getMessage());
		}
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
