package server.database.worlds;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.TreeMap;

import server.database.IniReader;
import server.database.SqliteDatabase;
import server.exceptions.StartupException;
import server.tools.Out;
import server.tools.VirtualFileSystem;

public class WorldManager {

	private static TreeMap<Integer, World> weList = new TreeMap<Integer, World>();

	/**
	 * Test hook: when non-null, {@link #init()} will bypass the
	 * {@link VirtualFileSystem} fallback and read the ini content from
	 * this stream instead. Package-private for test use only.
	 */
	static InputStream testIniStreamOverride = null;

	public static void init() throws StartupException {
		// 1) Prefer SQLite-backed world defs — these are populated by
		//    ClientDataImporter at startup and survive without a client mount.
		if (loadFromSqlite()) {
			return;
		}

		// 2) Fall back to the legacy runtime read from the client VFS (or
		//    from a test-injected stream). VirtualFileSystem reads Config
		//    eagerly, so guard against the not-initialised case (tests,
		//    fresh installs without a client config).
		InputStream data = testIniStreamOverride;
		if (data == null) {
			try {
				data = VirtualFileSystem.getFileInputStream("worlds\\worlds.ini");
			} catch (RuntimeException e) {
				Out.writeln(Out.Warning,
					"WorldManager: VirtualFileSystem unavailable (" + e.getClass().getSimpleName()
					+ "); treating as no client mounted");
				data = null;
			}
		}
		if (data == null) {
			// 3) Fresh install with no client mounted and no SQLite rows —
			//    log and return an empty map so the server can still boot.
			//    Callers of getWorldname already tolerate null returns.
			Out.writeln(Out.Error,
				"WorldManager: no world defs in SQLite and worlds\\worlds.ini "
				+ "not found in client VFS; world list will be empty");
			return;
		}
		loadFromIniStream(data);
	}

	/**
	 * Attempt to populate {@code weList} from the {@code world_defs} table.
	 * Returns true if any rows were loaded.
	 */
	private static boolean loadFromSqlite() {
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) {
			return false;
		}
		int loaded = 0;
		try (Statement stmt = conn.createStatement();
		     ResultSet rs = stmt.executeQuery("SELECT id, path FROM world_defs")) {
			while (rs.next()) {
				int id = rs.getInt("id");
				String path = rs.getString("path");
				if (path == null || path.isEmpty()) {
					continue;
				}
				weList.put(Integer.valueOf(id), new World(id, path));
				loaded++;
			}
		} catch (SQLException e) {
			// Table might not exist on a pre-schema-v2 DB — treat as "no rows".
			Out.writeln(Out.Warning,
				"WorldManager: could not read world_defs (" + e.getMessage() + "); falling back");
			return false;
		}
		if (loaded > 0) {
			Out.writeln(Out.Info, "Loaded " + loaded + " World IDs from SQLite");
			return true;
		}
		return false;
	}

	private static void loadFromIniStream(InputStream data) {
		IniReader ir = new IniReader(data);
		try {
			while (true) {
				String[] tokens = ir.getTokens();
				if ((tokens.length > 2) && (tokens[0].equals("set"))) {
					World we = new World(tokens);
					weList.put(Integer.valueOf(we.getID()), we);
				} else {
					if (ir.isEof())
						break;
				}
			}
		} finally {
			ir.close();
		}
		Out.writeln(Out.Info, "Loaded " + weList.size() + " World IDs");
	}

	public static String getWorldname(int location) {
		if(weList.get(location) == null)
			return null;
		else
			return weList.get(Integer.valueOf(location)).getName();
	}

	/**
	 * Test hook: clear in-memory state so tests can rerun {@link #init()}
	 * in isolation. Package-private — not part of the runtime API.
	 */
	static void reset() {
		weList.clear();
		testIniStreamOverride = null;
	}
}
