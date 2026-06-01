package server.database.items;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.TreeMap;

import server.database.SqliteDatabase;
import server.database.items.Item;
import server.tools.Out;

public class ItemManager {

	private static TreeMap<Long, Item> Items 					= new TreeMap<Long, Item>();
	private static TreeMap<Integer, ItemContainer> Container 	= new TreeMap<Integer, ItemContainer>();
	//private static TreeMap<Integer, Long[]> ContainerItems 		= new TreeMap<Integer, Long[]>();
	private static LinkedList<Integer> ContainerIds 			= new LinkedList<Integer>();
	private static LinkedList<Long> ItemIds						= new LinkedList<Long>();
	// getItemContainerID?

	/**
	 * Load all persisted items from the {@code items} table and
	 * attach them to their owning containers (which must already be
	 * registered via {@link #loadContainer}). This is called once at
	 * server startup AFTER all player characters and their
	 * containers have been initialised.
	 *
	 * <p>Items are restored into their containers at the EXACT grid
	 * position persisted in the {@code items.slot} column (via
	 * {@link ItemContainer#restoreItemAtPos}). If a slot collides or is
	 * out of range (e.g. a pre-slot schema-v6 row with slot=0) the item
	 * falls back to auto-placement so it still survives — only its
	 * grid position is then approximate.
	 */
	public static void loadall(){
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) return;

		int loaded = 0;
		int orphaned = 0;
		int reflowed = 0;
		long maxItemId = 0;
		// Named-columns schema (2026-06-01): the opaque `tokens` bytea and
		// packed `slot` int were replaced by named INTEGER columns plus the
		// `item_mod_slot` side table. We reassemble the in-memory
		// `short[17] tokens` + packed `inventorypos` here so the Item ctor
		// (and therefore createNetworkInfoData / the wire output) is fed a
		// byte-identical short[] to the pre-refactor blob path.
		String sql =
				"SELECT i.id, i.container_id, i.type_id, i.flags,"
				+ " i.curr_cond, i.max_cond, i.damage, i.frequency, i.handling,"
				+ " i.range, i.clip_size, i.ammo_uses, i.stack_count,"
				+ " i.mod_slots, i.mod_slots_used, i.constructor_char_id,"
				+ " i.slot_index, i.slot_x, i.slot_y,"
				+ " m1.mod_value AS mod1, m2.mod_value AS mod2,"
				+ " m3.mod_value AS mod3, m4.mod_value AS mod4,"
				+ " m5.mod_value AS mod5"
				+ " FROM items i"
				+ " LEFT JOIN item_mod_slot m1 ON m1.item_id = i.id AND m1.slot_no = 1"
				+ " LEFT JOIN item_mod_slot m2 ON m2.item_id = i.id AND m2.slot_no = 2"
				+ " LEFT JOIN item_mod_slot m3 ON m3.item_id = i.id AND m3.slot_no = 3"
				+ " LEFT JOIN item_mod_slot m4 ON m4.item_id = i.id AND m4.slot_no = 4"
				+ " LEFT JOIN item_mod_slot m5 ON m5.item_id = i.id AND m5.slot_no = 5";
		try (PreparedStatement ps = conn.prepareStatement(sql);
		     ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				long id = rs.getLong("id");
				int contId = rs.getInt("container_id");
				int typeId = rs.getInt("type_id");
				int flags = rs.getInt("flags");

				// Reassemble the short[17] tokens array from named columns.
				short[] tokens = new short[17];
				tokens[Item.TOKENS_CURRCOND]     = (short) rs.getInt("curr_cond");
				tokens[Item.TOKENS_MAXCOND]      = (short) rs.getInt("max_cond");
				tokens[Item.TOKENS_DMG]          = (short) rs.getInt("damage");
				tokens[Item.TOKENS_FREQUENCY]    = (short) rs.getInt("frequency");
				tokens[Item.TOKENS_HANDLING]     = (short) rs.getInt("handling");
				tokens[Item.TOKENS_RANGE]        = (short) rs.getInt("range");
				tokens[Item.TOKENS_CLIPSIZE]     = (short) rs.getInt("clip_size");
				tokens[Item.TOKENS_AMMOUSES]     = (short) rs.getInt("ammo_uses");
				tokens[Item.TOKENS_ITEMSONSTACK] = (short) rs.getInt("stack_count");
				tokens[Item.TOKENS_SLOTS]        = (short) rs.getInt("mod_slots");
				tokens[Item.TOKENS_SLOTSINUSE]   = (short) rs.getInt("mod_slots_used");
				tokens[Item.TOKENS_MOD1]         = (short) rs.getInt("mod1");
				tokens[Item.TOKENS_MOD2]         = (short) rs.getInt("mod2");
				tokens[Item.TOKENS_MOD3]         = (short) rs.getInt("mod3");
				tokens[Item.TOKENS_MOD4]         = (short) rs.getInt("mod4");
				tokens[Item.TOKENS_MOD5]         = (short) rs.getInt("mod5");
				tokens[Item.TOKENS_CONSTER]      = (short) rs.getInt("constructor_char_id");

				// Re-pack the inventory position from the three decoded
				// dimensions. Round-trips the old `slot` int exactly:
				// slot = slot_x + slot_y*256 + slot_index*65536.
				int slotX     = rs.getInt("slot_x");
				int slotY     = rs.getInt("slot_y");
				int slotIndex = rs.getInt("slot_index");
				int slot = slotX + slotY * 256 + slotIndex * 65536;

				if (id > maxItemId) maxItemId = id;

				ItemContainer cont = Container.get(contId);
				if (cont == null) {
					// Orphaned item — container_id doesn't resolve to
					// any loaded container. Keep in Items map so the
					// id isn't reissued, but skip attaching.
					orphaned++;
					Items.put(id, new Item(typeId, id, null, flags, tokens));
					if (!ItemIds.contains(id)) ItemIds.add(id);
					continue;
				}

				Item it = new Item(typeId, id, cont, flags, tokens);
				// Restore at the EXACT persisted position; only re-flow
				// via auto-placement if that slot is unusable.
				if (!cont.restoreItemAtPos(slot, it)) {
					cont.addItem(-1, it, 0);
					reflowed++;
				}
				Items.put(id, it);
				if (!ItemIds.contains(id)) ItemIds.add(id);
				loaded++;
			}
		} catch (SQLException e) {
			Out.writeln(Out.Error, "ItemManager.loadall: " + e.getMessage());
			return;
		}

		Out.writeln(Out.Info, "ItemManager: loaded " + loaded
				+ " items (" + orphaned + " orphaned, "
				+ reflowed + " re-flowed)");
	}

	/**
	 * Test seam — drop all in-memory item/container state so a unit
	 * test starts from a clean slate (the static maps otherwise leak
	 * across tests in the same JVM). Mirrors
	 * {@code SqliteDatabase.setIsPostgresForTesting}. Not used by
	 * production code paths.
	 */
	public static void resetForTesting(){
		Items.clear();
		Container.clear();
		ContainerIds.clear();
		ItemIds.clear();
	}

	public static void init(){

	}

	/**
	 * Persist EVERY item currently in memory. Called from
	 * auto-save / shutdown paths.
	 */
	public static void saveall(){
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) return;
		int saved = 0;
		try {
			for (Item it : Items.values()) {
				if (it == null) continue;
				if (saveItem(conn, it)) saved++;
			}
		} catch (SQLException e) {
			Out.writeln(Out.Error, "ItemManager.saveall: " + e.getMessage());
			return;
		}
		Out.writeln(Out.Info, "ItemManager: saved " + saved + " items");
	}

	/**
	 * Persist all items belonging to one container. Called when the
	 * owning character is saved.
	 */
	public static void save(ItemContainer container){
		if (container == null) return;
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) return;

		// 1. Delete existing rows for this container — simplest
		//    correct-on-removal approach. Items the player threw
		//    away or moved out won't linger as ghost DB rows.
		// 2. Re-insert every item currently in the container.
		try (PreparedStatement delMods = conn.prepareStatement(
				"DELETE FROM item_mod_slot WHERE item_id IN"
				+ " (SELECT id FROM items WHERE container_id = ?)")) {
			delMods.setInt(1, container.getContainerID());
			delMods.executeUpdate();
		} catch (SQLException e) {
			Out.writeln(Out.Error, "ItemManager.save: mod-slot delete failed: "
					+ e.getMessage());
			return;
		}
		try (PreparedStatement del = conn.prepareStatement(
				"DELETE FROM items WHERE container_id = ?")) {
			del.setInt(1, container.getContainerID());
			del.executeUpdate();
		} catch (SQLException e) {
			Out.writeln(Out.Error, "ItemManager.save: delete failed: "
					+ e.getMessage());
			return;
		}

		LinkedList<Item> items = container.getallItems();
		if (items == null) return;
		int written = 0;
		try {
			for (Item it : items) {
				if (it == null) continue;
				if (saveItem(conn, it)) written++;
			}
		} catch (SQLException e) {
			Out.writeln(Out.Error, "ItemManager.save: insert failed: "
					+ e.getMessage());
		}
		if (written > 0) {
			Out.writeln(Out.Info, "ItemManager: persisted " + written
					+ " items for container " + container.getContainerID());
		}
	}

	/**
	 * UPSERT a single item row. Caller manages container-level
	 * deletes for removed items.
	 */
	private static boolean saveItem(Connection conn, Item it) throws SQLException {
		if (it == null) return false;
		int contId = it.getContainer() != null
				? it.getContainer().getContainerID() : 0;
		boolean pg = SqliteDatabase.isPostgres();

		// Decompose the in-memory short[17] tokens + packed inventorypos
		// into the named columns. The wire representation (tokens array)
		// is untouched — this is purely the persistence translation.
		short[] tokens = it.getTokens();
		int pos = it.getInventoryPos();
		int slotIndex = pos / 65536;
		int slotY     = (pos - slotIndex * 65536) / 256;
		int slotX     = (pos - slotIndex * 65536 - slotY * 256);

		final String[] cols = {
			"id", "container_id", "type_id", "flags",
			"curr_cond", "max_cond", "damage", "frequency", "handling",
			"range", "clip_size", "ammo_uses", "stack_count",
			"mod_slots", "mod_slots_used", "constructor_char_id",
			"slot_index", "slot_x", "slot_y"
		};
		String sql;
		if (pg) {
			StringBuilder b = new StringBuilder("INSERT INTO items (");
			for (int i = 0; i < cols.length; i++) {
				if (i > 0) b.append(", ");
				b.append(cols[i]);
			}
			b.append(") VALUES (");
			for (int i = 0; i < cols.length; i++) {
				if (i > 0) b.append(", ");
				b.append("?");
			}
			b.append(") ON CONFLICT (id) DO UPDATE SET");
			for (int i = 1; i < cols.length; i++) { // skip id
				if (i > 1) b.append(",");
				b.append(" ").append(cols[i]).append("=EXCLUDED.").append(cols[i]);
			}
			sql = b.toString();
		} else {
			StringBuilder b = new StringBuilder("INSERT OR REPLACE INTO items (");
			for (int i = 0; i < cols.length; i++) {
				if (i > 0) b.append(", ");
				b.append(cols[i]);
			}
			b.append(") VALUES (");
			for (int i = 0; i < cols.length; i++) {
				if (i > 0) b.append(", ");
				b.append("?");
			}
			b.append(")");
			sql = b.toString();
		}
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			int p = 1;
			ps.setLong(p++, it.getId());
			ps.setInt(p++, contId);
			ps.setInt(p++, it.getTypeId());
			ps.setInt(p++, it.getFlags());
			ps.setInt(p++, tokens[Item.TOKENS_CURRCOND]);
			ps.setInt(p++, tokens[Item.TOKENS_MAXCOND]);
			ps.setInt(p++, tokens[Item.TOKENS_DMG]);
			ps.setInt(p++, tokens[Item.TOKENS_FREQUENCY]);
			ps.setInt(p++, tokens[Item.TOKENS_HANDLING]);
			ps.setInt(p++, tokens[Item.TOKENS_RANGE]);
			ps.setInt(p++, tokens[Item.TOKENS_CLIPSIZE]);
			ps.setInt(p++, tokens[Item.TOKENS_AMMOUSES]);
			ps.setInt(p++, tokens[Item.TOKENS_ITEMSONSTACK]);
			ps.setInt(p++, tokens[Item.TOKENS_SLOTS]);
			ps.setInt(p++, tokens[Item.TOKENS_SLOTSINUSE]);
			ps.setInt(p++, tokens[Item.TOKENS_CONSTER]);
			ps.setInt(p++, slotIndex);
			ps.setInt(p++, slotX);
			ps.setInt(p++, slotY);
			ps.executeUpdate();
		}

		// Upsert the mod-slot side table for tokens[11..15]. Only
		// non-zero mods get rows; zero mods are deleted so the LEFT JOIN
		// reassembles them back to 0 on load.
		writeModSlots(conn, pg, it.getId(), tokens);
		return true;
	}

	/**
	 * Persist the five mod-slot values (tokens[11..15] → slot_no 1..5)
	 * into {@code item_mod_slot}. Non-zero values are upserted; zero
	 * values are deleted so absence == 0 on reload.
	 */
	private static void writeModSlots(Connection conn, boolean pg, long itemId,
			short[] tokens) throws SQLException {
		for (int slotNo = 1; slotNo <= 5; slotNo++) {
			int mod = tokens[Item.TOKENS_MOD1 + (slotNo - 1)];
			if (mod == 0) {
				try (PreparedStatement del = conn.prepareStatement(
						"DELETE FROM item_mod_slot WHERE item_id = ? AND slot_no = ?")) {
					del.setLong(1, itemId);
					del.setInt(2, slotNo);
					del.executeUpdate();
				}
				continue;
			}
			String sql = pg
					? "INSERT INTO item_mod_slot (item_id, slot_no, mod_value)"
						+ " VALUES (?, ?, ?) ON CONFLICT (item_id, slot_no)"
						+ " DO UPDATE SET mod_value=EXCLUDED.mod_value"
					: "INSERT OR REPLACE INTO item_mod_slot"
						+ " (item_id, slot_no, mod_value) VALUES (?, ?, ?)";
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setLong(1, itemId);
				ps.setInt(2, slotNo);
				ps.setInt(3, mod);
				ps.executeUpdate();
			}
		}
	}
	
	public static boolean loadContainer(ItemContainer cont){
		Container.put(cont.getContainerID(), cont);
		return true;
	}
	
	/**
	 * Pick the position-encoding flag a container needs to interpret a
	 * raw position value. F2 (PLINVENTORY) positions are XY-packed
	 * ({@code posX + posY*256 (+ slot*65536)}) and must be decoded with
	 * {@link ItemContainer#FLAG_DSTPOS_XY}; QB / GOGU / box positions are
	 * a flat slot index and use flag 0. The encoding depends on the
	 * CONTAINER, never on the other side of a cross-container move — see
	 * the bug write-up on F2→QB moves silently failing because a
	 * destination-derived flag was used to read the source.
	 */
	private static int posFlagFor(ItemContainer cont){
		return cont.getContainerType() == ItemContainer.CONTAINERTYPE_PLINVENTORY
				? ItemContainer.FLAG_DSTPOS_XY
				: 0;
	}

	/**
	 * takes care of itemmoves between containers
	 *
	 * <p>The source-read encoding and the destination-add encoding are
	 * INDEPENDENT: each is derived from its own container's type via
	 * {@link #posFlagFor}. The {@code flags} argument is retained for
	 * call-site compatibility but is no longer used to choose the
	 * encoding (it was the root cause of cross-container moves failing —
	 * a dst-derived flag was being used to read the src item, so a
	 * F2→QB move read the QB at an XY-packed index and got null).
	 *
	 * @param srccont 	source container
	 * @param srcpos 	the position in the sourcecontainer
	 * @param dstcont 	destination container
	 * @param dstpos	destination position in dstcont
	 * @param flags		legacy/unused — encoding is now per-container
	 */
	public static boolean moveItem(ItemContainer srccont, int srcpos, ItemContainer dstcont, int dstpos, int flags){
		//TODO: check first if item at that pos already exists!
		//      (P3 swap/occupied-slot semantics — not yet handled; a
		//       move onto an occupied dst slot just fails the add today.)
		int srcFlag = posFlagFor(srccont);
		int dstFlag = posFlagFor(dstcont);

		Item it = srccont.getItem(srcpos, srcFlag);
		if(it == null){
			return false;
		}

		// The item's CURRENT packed position drives the source removal —
		// this is the value the source container itself stored, which is
		// independent of how the wire srcpos was encoded.
		int pos = it.getPos(Item.CONTAINERPOS);

		if(dstcont.addItem(dstpos, it, dstFlag)){
			if(!srccont.removeItem(it, pos, srcFlag)){
				dstcont.removeItem(it, dstpos, dstFlag);
				return false;
			}
			return true;
		}

		Out.writeln(Out.Info, "could not add Item!");
		return false;
	}
	
	/**
	 * creates a new item and adds it to the specified container
	 * 
	 * @param dstcont	destination container
	 * @param dstpos	destination position in dstcont
	 * @param type		type of the item (may be looked up in items.def)
	 * @param currcond	current condition of the item
	 * @param maxcond	maximum condition
	 * @param tokens	special attributes of the items, e.g. slots, uses left, ...
	 * @param itemflags	tells us more about the special attributes
	 * @param flags		gives information about the positioning of the item
	 * @return			id of the item created
	 */
	public static Item createItem(ItemContainer dstcont, int dstpos, int type, short[] tokens, int itemflags, int flags){
		Item it = null;
		long id = -1;
		
		if(dstcont == null){
			return null;
		}
		else{
			if(dstcont.isContainerFull())
				return null;
			
			if(dstpos == -1){ // no matter where item is added
				
				if(ItemInfoManager.getItemInfo(type) == null)
					return null;
				
				id = getFreeItemId();
				if(id == 0)
					return null;
				
				if(tokens[Item.TOKENS_CURRCOND] >= 256 || tokens[Item.TOKENS_MAXCOND] >= 256)
					return null;
				
				it = new Item(type, id, dstcont, itemflags, tokens);
				if(!dstcont.addItem(dstpos, it, flags)){
					it = null;
					return null;
				}
				
				Items.put(id, it);
			}
			else{
				
			}			
		}
		return it;
	}
	
	public static boolean removeItem(int dstcont, int dstpos, long id, int flags){
		return false;
	}
	
	/**
	 *
	 * @return	next free containerID
	 *
	 * Issues IDs starting from MAX(used_id) + 1 across both the in-memory
	 * set AND the persisted player_characters table — without this the
	 * in-memory counter resets each restart and every new char gets
	 * container_id = 1, colliding with prior chars.
	 */
	public static int getFreeContId(){
		int dbMax = queryMaxContainerIdFromDb();
		int seed = Math.max(1, dbMax + 1);
		for(int i = seed; i < (int)2147483647; i++){
			if(!ContainerIds.contains(i)){
				ContainerIds.add(Integer.valueOf(i));
				return i;
			}
		}
		return 0;
	}

	/**
	 *
	 * @return	next freeitemID
	 */
	public static long getFreeItemId(){
		long dbMax = queryMaxItemIdFromDb();
		long seed = Math.max(1, dbMax + 1);
		for(long i = seed; i < 9223372036854775807L ; i++){
			if(!ItemIds.contains(i)){
				ItemIds.add(Long.valueOf(i));
				return i;
			}
		}
		return 0;
	}

	/**
	 * Query MAX(container_id) across all known container columns in
	 * player_characters. Returns 0 if the table is empty or the
	 * query fails (caller then uses seed=1).
	 */
	private static int queryMaxContainerIdFromDb() {
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) return 0;
		String sql = "SELECT GREATEST("
				+ "COALESCE(MAX(f2_inventory_cont_id), 0),"
				+ "COALESCE(MAX(gogu_inventory_cont_id), 0),"
				+ "COALESCE(MAX(qb_inventory_cont_id), 0)"
				+ ") AS mx FROM player_characters";
		if (!SqliteDatabase.isPostgres()) {
			// SQLite has no GREATEST; use MAX over a subquery union
			sql = "SELECT MAX(c) AS mx FROM ("
					+ "SELECT MAX(f2_inventory_cont_id) AS c FROM player_characters UNION ALL "
					+ "SELECT MAX(gogu_inventory_cont_id) FROM player_characters UNION ALL "
					+ "SELECT MAX(qb_inventory_cont_id) FROM player_characters)";
		}
		try (PreparedStatement ps = conn.prepareStatement(sql);
		     ResultSet rs = ps.executeQuery()) {
			if (rs.next()) return rs.getInt("mx");
		} catch (SQLException e) {
			Out.writeln(Out.Warning,
				"ItemManager: queryMaxContainerIdFromDb: " + e.getMessage());
		}
		return 0;
	}

	private static long queryMaxItemIdFromDb() {
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) return 0;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT COALESCE(MAX(id), 0) AS mx FROM items");
		     ResultSet rs = ps.executeQuery()) {
			if (rs.next()) return rs.getLong("mx");
		} catch (SQLException e) {
			Out.writeln(Out.Warning,
				"ItemManager: queryMaxItemIdFromDb: " + e.getMessage());
		}
		return 0;
	}
	
	/**
	 * 
	 * @param id	id of the item to be retrieved
	 * @return		Item belonging to the specified id
	 */
	public static Item getItem(long id){
		if(Items.containsKey(id))
			return Items.get(id);
		else
			return null;
	}
	
	/**
	 * 
	 * @param id	id of the item whose size should be determined
	 * @return		SizeX + 10*SizeY
	 */
	public static int getItemSize(long id){
		if(!Items.containsKey(id))
			return 0;
		else{
			int size = 0;
			Item it = Items.get(id);
			size = it.getInvSizeX() + 10*it.getInvSizeY();
			return size;
		}
	}
}
