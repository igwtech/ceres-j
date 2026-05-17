package server.database.accounts;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.UUID;

import server.database.SqliteDatabase;
import server.exceptions.StartupException;
import server.tools.Config;
import server.tools.Out;

public class AccountManager {

	private static LinkedList<Account> accountList;
	private static int accountCounter;

	public static LinkedList<Account> getAccounts() {
		synchronized (accountList) {
			return new LinkedList<>(accountList);
		}
	}

	/** Test seam: install an in-memory account list so
	 *  {@link #applyConfigAdmins()} can be exercised without a DB. */
	static void setAccountsForTesting(LinkedList<Account> l) {
		accountList = l;
	}

	public static void init() throws StartupException {
		load();
		findaccountCounter();
	}

	public static void stopServer() {
		save();
	}

	public static void load() {
		accountList = new LinkedList<Account>();
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) {
			Out.writeln(Out.Error, "No database connection for AccountManager");
			return;
		}

		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT id, uuid, username, password, char1, char2, char3, char4, status, gm_level FROM accounts")) {

			while (rs.next()) {
				Account ua = new Account(rs.getInt("id"));
				ua.setUuid(SqliteDatabase.getUuid(rs, "uuid"));
				ua.setUsername(rs.getString("username"));
				ua.setPassword(rs.getString("password"));
				ua.setChar(0, rs.getInt("char1"));
				ua.setChar(1, rs.getInt("char2"));
				ua.setChar(2, rs.getInt("char3"));
				ua.setChar(3, rs.getInt("char4"));
				ua.setStatus(rs.getString("status"));
				// An explicit gm_level column wins over the legacy
				// status='admin' → GM_ADMIN default applied by
				// setStatus(). A stored 0 keeps the status-derived
				// value so legacy admin accounts stay privileged.
				int storedGm = rs.getInt("gm_level");
				if (storedGm > 0) {
					ua.setGmLevel(storedGm);
				}
				accountList.add(ua);
			}
		} catch (SQLException e) {
			Out.writeln(Out.Error, "Error loading accounts: " + e.getMessage());
		}
		Out.writeln(Out.Info, "Loaded " + accountList.size() + " Accounts");
		applyConfigAdmins();
	}

	/**
	 * Force-promote every account named in the {@code AdminAccounts}
	 * config key to {@link Account#GM_ADMIN} in memory (never a
	 * downgrade). This runs after the DB load and is re-applied on
	 * every boot, so it survives the autosave upsert that would
	 * otherwise clobber a live {@code gm_level} change with the
	 * stale in-memory value. Visible for tests.
	 */
	static void applyConfigAdmins() {
		synchronized (accountList) {
			for (Account ua : accountList) {
				if (server.tools.Config.isAdminAccount(ua.getUsername())
						&& ua.getGmLevel() < Account.GM_ADMIN) {
					ua.setGmLevel(Account.GM_ADMIN);
					Out.writeln(Out.Info,
						"AdminAccounts: promoted '" + ua.getUsername()
						+ "' to GM_ADMIN");
				}
			}
		}
	}

	public static void save() {
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) return;

		try {
			conn.setAutoCommit(false);

			String upsert = upsertSql();
			try (PreparedStatement ps = conn.prepareStatement(upsert)) {
				synchronized (accountList) {
					for (Iterator<Account> i = accountList.iterator(); i.hasNext(); ) {
						Account ua = i.next();
						// Defensive UUID mint — accounts loaded from a
						// schema-migrated DB always have one, but in-memory
						// constructions (e.g. tests, dev-mode signup) may
						// arrive here without one set.
						if (ua.getUuid() == null) ua.setUuid(UUID.randomUUID());
						ps.setInt(1, ua.getId());
						SqliteDatabase.setUuid(ps, 2, ua.getUuid());
						ps.setString(3, ua.getUsername());
						ps.setString(4, ua.getPassword());
						ps.setInt(5, ua.getChar(0));
						ps.setInt(6, ua.getChar(1));
						ps.setInt(7, ua.getChar(2));
						ps.setInt(8, ua.getChar(3));
						ps.setString(9, ua.getStatus());
						ps.setInt(10, ua.getGmLevel());
						ps.addBatch();
					}
				}
				ps.executeBatch();
			}

			conn.commit();
		} catch (SQLException e) {
			try {
				conn.rollback();
			} catch (SQLException ex) {
				Out.writeln(Out.Error, "Error rolling back accounts save: " + ex.getMessage());
			}
			Out.writeln(Out.Error, "Error saving accounts: " + e.getMessage());
		} finally {
			try {
				conn.setAutoCommit(true);
			} catch (SQLException e) {
				Out.writeln(Out.Error, "Error resetting auto-commit: " + e.getMessage());
			}
		}
	}

	/**
	 * Immediately persist a single account to the database.
	 */
	public static void saveAccount(Account ua) {
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) return;

		// Defensive UUID mint — keeps the column NOT NULL invariant for
		// accounts that bypassed createAccount() (e.g. legacy code paths
		// constructing an Account by id directly).
		if (ua.getUuid() == null) ua.setUuid(UUID.randomUUID());

		String upsert = upsertSql();
		try (PreparedStatement ps = conn.prepareStatement(upsert)) {
			ps.setInt(1, ua.getId());
			SqliteDatabase.setUuid(ps, 2, ua.getUuid());
			ps.setString(3, ua.getUsername());
			ps.setString(4, ua.getPassword());
			ps.setInt(5, ua.getChar(0));
			ps.setInt(6, ua.getChar(1));
			ps.setInt(7, ua.getChar(2));
			ps.setInt(8, ua.getChar(3));
			ps.setString(9, ua.getStatus());
			ps.executeUpdate();
		} catch (SQLException e) {
			Out.writeln(Out.Error, "Error saving account " + ua.getUsername() + ": " + e.getMessage());
		}
	}

	/** Build the dialect-appropriate upsert SQL for the accounts
	 *  table. PostgreSQL needs {@code ON CONFLICT} since
	 *  {@code INSERT OR REPLACE} is SQLite-only syntax. Visible
	 *  for tests so we can assert both branches. */
	static String upsertSql() {
		if (SqliteDatabase.isPostgres()) {
			return "INSERT INTO accounts ("
				+ "id, uuid, username, password, char1, char2, char3, char4, status, gm_level) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
				+ "ON CONFLICT (id) DO UPDATE SET "
				+ "uuid = EXCLUDED.uuid, "
				+ "username = EXCLUDED.username, "
				+ "password = EXCLUDED.password, "
				+ "char1 = EXCLUDED.char1, "
				+ "char2 = EXCLUDED.char2, "
				+ "char3 = EXCLUDED.char3, "
				+ "char4 = EXCLUDED.char4, "
				+ "status = EXCLUDED.status, "
				+ "gm_level = EXCLUDED.gm_level";
		}
		return "INSERT OR REPLACE INTO accounts ("
			+ "id, uuid, username, password, char1, char2, char3, char4, status, gm_level) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
	}

	private static void findaccountCounter() {
		int id = 0;
		for (Iterator<Account> i = accountList.iterator(); i.hasNext(); ) {
			Account ua = i.next();
			if (ua.getId() > id) {
				id = ua.getId();
			}
		}
		accountCounter = id + 1;
	}

	/** Outcome of an authentication attempt — surfaces enough
	 *  detail that the caller (e.g. {@code AuthB}) can log a
	 *  meaningful diagnostic instead of "authentication failed". */
	public enum AuthOutcome { OK, NOT_FOUND, BAD_PASSWORD, BANNED }

	public static final class AuthResult {
		public final AuthOutcome outcome;
		/** The matched account on success, or null otherwise. Null
		 *  on BAD_PASSWORD too — callers shouldn't see the entity
		 *  for a failed credential check. */
		public final Account account;
		AuthResult(AuthOutcome o, Account a) { this.outcome = o; this.account = a; }
	}

	/** Detailed authentication that distinguishes user-not-found,
	 *  bad-password, banned, and success. Used by {@code AuthB}
	 *  for richer logs.
	 *
	 *  <p>If {@code AutoCreateAccounts=true} and the username is
	 *  unknown, the account is created with the supplied password
	 *  and the result is {@link AuthOutcome#OK}. (This matches the
	 *  legacy {@code getAccount} semantics — dev-mode signup.) */
	public static AuthResult authenticate(String username, String password) {
		Account ua = findByUsername(username);
		if (ua == null) {
			if ("true".equals(Config.getProperty("AutoCreateAccounts"))) {
				synchronized (accountList) {
					// Re-check inside the lock in case another thread
					// just created it.
					ua = findByUsername(username);
					if (ua == null) {
						ua = createAccount(username, password);
						accountList.add(ua);
						saveAccount(ua);
					}
				}
			} else {
				return new AuthResult(AuthOutcome.NOT_FOUND, null);
			}
		}
		if (!ua.ckeckPassword(password)) {
			return new AuthResult(AuthOutcome.BAD_PASSWORD, null);
		}
		if (ua.isStatus(Account.STATUS_BANNED)) {
			return new AuthResult(AuthOutcome.BANNED, null);
		}
		return new AuthResult(AuthOutcome.OK, ua);
	}

	/**
	 * Case-insensitive in-memory account lookup. Used by the auth path,
	 * tests, and the web admin API (ban/unban/set_admin can target
	 * accounts that are not currently online). Null-safe before the
	 * account store has been loaded.
	 */
	public static Account findByUsername(String username) {
		if (username == null || accountList == null) return null;
		synchronized (accountList) {
			for (Iterator<Account> i = accountList.iterator(); i.hasNext(); ) {
				Account tua = i.next();
				if (tua.getUsername().equalsIgnoreCase(username)) {
					return tua;
				}
			}
		}
		return null;
	}

	/** Backward-compatible thin wrapper. New callers should prefer
	 *  {@link #authenticate} which surfaces the failure reason. */
	public static Account getAccount(String username, String password) {
		AuthResult r = authenticate(username, password);
		return r.outcome == AuthOutcome.OK ? r.account : null;
	}

	private static Account createAccount(String username, String password) {
		Account ua = new Account(accountCounter);
		ua.setUuid(UUID.randomUUID());
		ua.setUsername(username);
		ua.setPassword(password);
		accountCounter++;
		return ua;
	}

	/**
	 * Look up an account by its UUID — the identifier the SOAP API uses
	 * for session tokens, authentication keys, and ServiceInstanceIdentifier.
	 *
	 * <p>Returns {@code null} when no match exists or when {@code uuid} is
	 * itself {@code null}. Comparison is reference-safe via
	 * {@link UUID#equals(Object)}.
	 */
	public static Account findByUuid(UUID uuid) {
		if (uuid == null) return null;
		synchronized (accountList) {
			for (Iterator<Account> i = accountList.iterator(); i.hasNext(); ) {
				Account ua = i.next();
				if (uuid.equals(ua.getUuid())) return ua;
			}
		}
		return null;
	}
}
