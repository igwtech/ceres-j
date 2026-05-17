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
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT id, container_id, type_id, slot, flags, tokens FROM items");
		     ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				long id = rs.getLong("id");
				int contId = rs.getInt("container_id");
				int typeId = rs.getInt("type_id");
				int slot = rs.getInt("slot");
				int flags = rs.getInt("flags");
				byte[] tokenBytes = rs.getBytes("tokens");
				short[] tokens = Item.deserializeTokens(tokenBytes);

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
		String sql = pg
				? "INSERT INTO items (id, container_id, type_id, slot, flags, tokens)"
					+ " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET"
					+ " container_id=EXCLUDED.container_id,"
					+ " type_id=EXCLUDED.type_id,"
					+ " slot=EXCLUDED.slot,"
					+ " flags=EXCLUDED.flags,"
					+ " tokens=EXCLUDED.tokens"
				: "INSERT OR REPLACE INTO items"
					+ " (id, container_id, type_id, slot, flags, tokens)"
					+ " VALUES (?, ?, ?, ?, ?, ?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setLong(1, it.getId());
			ps.setInt(2, contId);
			ps.setInt(3, it.getTypeId());
			// slot = the packed inventory position so exact grid
			// layout (F2 slot + X/Y origin, or QB slot index) survives
			// a server restart.
			ps.setInt(4, it.getInventoryPos());
			ps.setInt(5, it.getFlags());
			ps.setBytes(6, it.serializeTokens());
			ps.executeUpdate();
			return true;
		}
	}
	
	public static boolean loadContainer(ItemContainer cont){
		Container.put(cont.getContainerID(), cont);
		return true;
	}
	
	/**
	 * takes care of itemmoves between containers
	 * 
	 * @param srccont 	source container 
	 * @param srcpos 	the position in the sourcecontainer
	 * @param dstcont 	destination container
	 * @param dstpos	destination position in dstcont
	 * @param flags		special flags describing the movement
	 */
	public static boolean moveItem(ItemContainer srccont, int srcpos, ItemContainer dstcont, int dstpos, int flags){
		//TODO: check first if item at that pos already exists!
		if(flags == 0){
			Item it = srccont.getItem(srcpos, 0);
		
			if(it == null){
				return false;
				}
			
			int pos = it.getPos(Item.CONTAINERPOS);
			
			if(dstcont.addItem(dstpos, it, 0)){ //TODO: doesnt work when moving from qb to f2
				if(!srccont.removeItem(it, pos, 0)){
					dstcont.removeItem(it, dstpos, 0);
					return false;
				}
				return true;
			}
			
			Out.writeln(Out.Info, "could not add Item!");
		}
		else if(flags == ItemContainer.FLAG_DSTPOS_XY){
			Item it = srccont.getItem(srcpos, 0);
			
			if(it == null){
				return false;
				}
			
			int pos = it.getPos(Item.CONTAINERPOS);
			
			if(dstcont.addItem(dstpos, it, ItemContainer.FLAG_DSTPOS_XY)){ //TODO: doesnt work when moving from qb to f2
				if(!srccont.removeItem(it, pos, 0)){
					dstcont.removeItem(it, dstpos, ItemContainer.FLAG_DSTPOS_XY);
					return false;
				}
				return true;
			}
		}
		
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
