package server.database.accounts;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.LinkedList;

import server.database.SqliteDatabase;
import server.exceptions.StartupException;
import server.tools.Config;
import server.tools.Out;

public class AccountManager {

	private static LinkedList<Account> accountList;
	private static int accountCounter;

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
			 ResultSet rs = stmt.executeQuery("SELECT id, username, password, char1, char2, char3, char4, status FROM accounts")) {

			while (rs.next()) {
				Account ua = new Account(rs.getInt("id"));
				ua.setUsername(rs.getString("username"));
				ua.setPassword(rs.getString("password"));
				ua.setChar(0, rs.getInt("char1"));
				ua.setChar(1, rs.getInt("char2"));
				ua.setChar(2, rs.getInt("char3"));
				ua.setChar(3, rs.getInt("char4"));
				ua.setStatus(rs.getString("status"));
				accountList.add(ua);
			}
		} catch (SQLException e) {
			Out.writeln(Out.Error, "Error loading accounts: " + e.getMessage());
		}
		Out.writeln(Out.Info, "Loaded " + accountList.size() + " Accounts");
	}

	public static void save() {
		Connection conn = SqliteDatabase.getConnection();
		if (conn == null) return;

		try {
			conn.setAutoCommit(false);

			String upsert = "INSERT OR REPLACE INTO accounts (id, username, password, char1, char2, char3, char4, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
			try (PreparedStatement ps = conn.prepareStatement(upsert)) {
				synchronized (accountList) {
					for (Iterator<Account> i = accountList.iterator(); i.hasNext(); ) {
						Account ua = i.next();
						ps.setInt(1, ua.getId());
						ps.setString(2, ua.getUsername());
						ps.setString(3, ua.getPassword());
						ps.setInt(4, ua.getChar(0));
						ps.setInt(5, ua.getChar(1));
						ps.setInt(6, ua.getChar(2));
						ps.setInt(7, ua.getChar(3));
						ps.setString(8, ua.getStatus());
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

		String upsert = "INSERT OR REPLACE INTO accounts (id, username, password, char1, char2, char3, char4, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
		try (PreparedStatement ps = conn.prepareStatement(upsert)) {
			ps.setInt(1, ua.getId());
			ps.setString(2, ua.getUsername());
			ps.setString(3, ua.getPassword());
			ps.setInt(4, ua.getChar(0));
			ps.setInt(5, ua.getChar(1));
			ps.setInt(6, ua.getChar(2));
			ps.setInt(7, ua.getChar(3));
			ps.setString(8, ua.getStatus());
			ps.executeUpdate();
		} catch (SQLException e) {
			Out.writeln(Out.Error, "Error saving account " + ua.getUsername() + ": " + e.getMessage());
		}
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

	public static Account getAccount(String username, String password) {
		Account ua = null;
		synchronized (accountList) {
			for (Iterator<Account> i = accountList.iterator(); i.hasNext(); ) {
				Account tua = i.next();
				if (tua.getUsername().equalsIgnoreCase(username)) {
					ua = tua;
					break;
				}
			}
			if ((ua == null) && (Config.getProperty("AutoCreateAccounts").equals("true"))) {
				ua = createAccount(username, password);
				accountList.add(ua);
				saveAccount(ua); // Immediately persist new account
			}
		}
		if (ua == null) return null;
		if (!ua.ckeckPassword(password)) return null;
		if (ua.isStatus(Account.STATUS_BANNED)) return null;
		return ua;
	}

	private static Account createAccount(String username, String password) {
		Account ua = new Account(accountCounter);
		ua.setUsername(username);
		ua.setPassword(password);
		accountCounter++;
		return ua;
	}
}
