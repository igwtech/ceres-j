package server.database.items;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import server.database.DefReader;
import server.database.SqliteDatabase;
import server.exceptions.StartupException;
import server.tools.Out;
import server.tools.VirtualFileSystem;

public class ItemInfoManager {

	// TODO: switch to SQLite-backed loading (schema v2 item_defs table)

	private static TreeMap<Integer, ItemInfo> ItemInfoList = new TreeMap<Integer, ItemInfo>();

	/**
	 * {@code type_id → modelid} lookup, sourced from the canonical
	 * {@code defs.items.modelid} column (NOT re-parsed from the def
	 * file, whose model token index is ambiguous). Lazily loaded on
	 * first {@link #getModelId(int)} call so unit tests that never
	 * touch the DB don't trip on a missing connection.
	 *
	 * <p>This is the authoritative source for the in-hand / on-back
	 * weapon MODEL id the client renders in response to the S→C
	 * {@code 0x03/0x2f} UpdateModel delta (the missing half of the
	 * weapon-draw flow — the {@code EquipStateAck} slot-state alone
	 * does not tell the client WHICH model to draw). Verified:
	 * Stiletto {@code type_id 19 → modelid 25}.
	 */
	private static Map<Integer, Integer> ModelIdByType = null;

	/**
	 * {@code item type_id → defs.weapons.id} lookup. This is the value
	 * the retail client expects in the {@code 0x03/0x2f} UpdateModel
	 * in-hand TLV ({@code 02 0a [weaponDefId LE2]}) — NOT
	 * {@code defs.items.modelid}.
	 *
	 * <p>Decisive pcap evidence (retail "Krafteo" draw, 2026-05-31):
	 * the in-hand tag carried {@code 0x0122 = 290}, which is
	 * {@code defs.weapons.id} for the Lazar Gun ({@code itemid 390}).
	 * It is NOT {@code items.modelid} (which would be a different value)
	 * and {@code weapons.id != itemid} in 724/781 rows, so the id space
	 * matters. Keyed by {@code weapons.itemid}; when an item maps to
	 * multiple weapon rows we keep the lowest {@code weapons.id}
	 * (deterministic).
	 */
	private static Map<Integer, Integer> WeaponDefIdByType = null;

	/** Returned when no model is known for a type — "render nothing"
	 *  in the UpdateModel weapon-slot TLV. */
	public static final int MODEL_NONE = 0xffff;

		public static void init() throws StartupException {
			InputStream data = VirtualFileSystem.getFileInputStream("defs\\items.def");
			if (data == null)
				throw new StartupException("Cannt find defs\\items.def in the Client Folder");
			DefReader dr = new DefReader(data);
			while (!dr.isEof()) {
				String[] tokens = dr.getTokens();
				if ((tokens.length > 2) && (tokens[0].equals("setentry"))) {
					ItemInfo itinf = new ItemInfo(tokens);
					ItemInfoList.put(itinf.getID(), itinf);
				} else {
					if (dr.isEof())
						break;
				}
			}
			dr.close();
			
			Out.writeln(Out.Info, "Loaded " + ItemInfoList.size() + " Item IDs");
		}
		
		public static ItemInfo getItemInfo(int type){
			if(ItemInfoList.containsKey(type))
				return ItemInfoList.get(type);
			else
				return null;
		}

		/**
		 * Authoritative weapon/item MODEL id for the given item
		 * {@code type_id}, read from {@code defs.items.modelid}.
		 *
		 * <p>Used by the weapon-draw flow: when a player equips a
		 * quickbelt weapon we must tell the client which model to
		 * render in-hand via the {@code 0x03/0x2f} UpdateModel delta.
		 * The slot-state ack ({@code EquipStateAck}) alone is
		 * insufficient — without the model id the weapon never
		 * visually appears.
		 *
		 * <p>The {@code type_id → modelid} map is loaded once on first
		 * call (lazy) so it has no startup-ordering dependency and so
		 * unit tests that don't open the DB are unaffected.
		 *
		 * @param typeId item type id ({@code items.type_id} ==
		 *               {@code defs.items.id})
		 * @return the model id, or {@link #MODEL_NONE} ({@code 0xffff})
		 *         if unknown / DB unavailable
		 */
		public static synchronized int getModelId(int typeId){
			if (ModelIdByType == null) {
				loadModelIds();
			}
			Integer m = ModelIdByType.get(typeId);
			return (m == null) ? MODEL_NONE : (m & 0xffff);
		}

		/**
		 * Weapon DEF id ({@code defs.weapons.id}) for the item with the
		 * given {@code type_id}, i.e. the value the client expects in the
		 * {@code 0x03/0x2f} UpdateModel in-hand TLV when this item is
		 * drawn.
		 *
		 * <p>This is the CORRECT source for the in-hand model word — the
		 * retail client renders the weapon from its weapon DEF id, not
		 * from {@code items.modelid}. Verified against the retail Krafteo
		 * draw pcap: in-hand value {@code 290 = defs.weapons.id} (Lazar
		 * Gun, {@code itemid 390}).
		 *
		 * @param typeId item type id ({@code items.id})
		 * @return {@code defs.weapons.id} for that item, or
		 *         {@link #MODEL_NONE} ({@code 0xffff}) if the item is not a
		 *         weapon / unknown / DB unavailable
		 */
		public static synchronized int getWeaponDefId(int typeId){
			if (WeaponDefIdByType == null) {
				loadWeaponDefIds();
			}
			Integer w = WeaponDefIdByType.get(typeId);
			return (w == null) ? MODEL_NONE : (w & 0xffff);
		}

		/**
		 * Populate {@link #ModelIdByType} from {@code defs.items}. On
		 * any failure (no connection, missing table/column) the map is
		 * left empty so {@link #getModelId(int)} degrades gracefully to
		 * {@link #MODEL_NONE} rather than throwing on the hot path.
		 */
		private static void loadModelIds(){
			ModelIdByType = new HashMap<Integer, Integer>();
			Connection conn = SqliteDatabase.getConnection();
			if (conn == null) {
				Out.writeln(Out.Warning,
					"ItemInfoManager: no DB connection — model ids unavailable");
				return;
			}
			// defs.items on Postgres; the schema-qualified name also
			// resolves on SQLite when the importer mirrors the table
			// unqualified, so fall back if the qualified query fails.
			String[] candidates = {
				"SELECT id, modelid FROM defs.items",
				"SELECT id, modelid FROM items_defs",
				"SELECT id, modelid FROM \"items\""
			};
			for (String sql : candidates) {
				try (PreparedStatement ps = conn.prepareStatement(sql);
				     ResultSet rs = ps.executeQuery()) {
					int n = 0;
					while (rs.next()) {
						int id = rs.getInt(1);
						int model = rs.getInt(2);
						if (!rs.wasNull()) {
							ModelIdByType.put(id, model);
							n++;
						}
					}
					Out.writeln(Out.Info,
						"ItemInfoManager: loaded " + n + " item model ids");
					return;
				} catch (SQLException e) {
					// Try the next candidate query.
				}
			}
			Out.writeln(Out.Warning,
				"ItemInfoManager: could not load item model ids from defs.items");
		}

		/**
		 * Populate {@link #WeaponDefIdByType} from {@code defs.weapons}.
		 * Keyed by {@code itemid}; on duplicate {@code itemid} we keep the
		 * lowest {@code weapons.id} so the result is deterministic. On any
		 * failure the map is left empty so {@link #getWeaponDefId(int)}
		 * degrades to {@link #MODEL_NONE}.
		 */
		private static void loadWeaponDefIds(){
			WeaponDefIdByType = new HashMap<Integer, Integer>();
			Connection conn = SqliteDatabase.getConnection();
			if (conn == null) {
				Out.writeln(Out.Warning,
					"ItemInfoManager: no DB connection — weapon def ids unavailable");
				return;
			}
			String[] candidates = {
				"SELECT itemid, id FROM defs.weapons ORDER BY id",
				"SELECT itemid, id FROM weapons_defs ORDER BY id",
				"SELECT itemid, id FROM \"weapons\" ORDER BY id"
			};
			for (String sql : candidates) {
				try (PreparedStatement ps = conn.prepareStatement(sql);
				     ResultSet rs = ps.executeQuery()) {
					int n = 0;
					while (rs.next()) {
						int itemId = rs.getInt(1);
						int weaponId = rs.getInt(2);
						if (!rs.wasNull()
								&& !WeaponDefIdByType.containsKey(itemId)) {
							// ORDER BY id => first seen is the lowest id.
							WeaponDefIdByType.put(itemId, weaponId);
							n++;
						}
					}
					Out.writeln(Out.Info,
						"ItemInfoManager: loaded " + n + " weapon def ids");
					return;
				} catch (SQLException e) {
					// Try the next candidate query.
				}
			}
			Out.writeln(Out.Warning,
				"ItemInfoManager: could not load weapon def ids from defs.weapons");
		}

		/** Test seam — drop the cached model-id map so a test can force
		 *  a reload (or run against a fresh in-memory DB). */
		public static synchronized void resetModelIdsForTesting(){
			ModelIdByType = null;
			WeaponDefIdByType = null;
		}
	}
