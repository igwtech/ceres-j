package server.database.accounts;

import java.util.UUID;

import server.database.playerCharacters.PlayerCharacterManager;
import server.gameserver.GameServerTCPConnection;

public class Account {

	private int id;
	/**
	 * RFC 4122 UUID identifier separate from the integer {@link #id}.
	 *
	 * <p>The integer id stays load-bearing for the NC2 wire protocol — it is
	 * broadcast as the account UID in {@code 0x8305} UDPServerData and similar
	 * packets, and the SOAP API/client/server pair were designed around it.
	 * The UUID is the new identifier used by the SOAP launcher endpoints
	 * (session tokens, authentication keys, ServiceInstanceIdentifier, all
	 * defined as the {@code guid} simple type in the project's WSDL/XML
	 * schemas) so external services can address accounts without ever
	 * touching the wire-protocol integer space.
	 */
	private UUID uuid;
	private String username;
	private String password;
	int character[] = new int[4];
	int status;
	/**
	 * CMaNGOS-style numeric GM authority level. 0 = ordinary player;
	 * higher numbers unlock more powerful in-game commands. Persisted in
	 * the {@code accounts.gm_level} column (schema v8). The legacy
	 * {@code status="admin"} flag is mapped to {@link #GM_ADMIN} on load
	 * so existing admin accounts keep working without a DB edit.
	 */
	private int gmLevel;
	private GameServerTCPConnection currentTCP;

	public static final int STATUS_BANNED = 1;
	public static final int STATUS_ADMIN = 2;

	// ── CMaNGOS-style GM authority tiers ──────────────────────────────
	/** Ordinary player — no privileged commands. */
	public static final int GM_PLAYER     = 0;
	/** Trusted helper — read-only / self-only diagnostic commands. */
	public static final int GM_MODERATOR  = 1;
	/** Game Master — world-affecting commands on self. */
	public static final int GM_GAMEMASTER = 2;
	/** Administrator — commands that affect other players / accounts. */
	public static final int GM_ADMIN      = 3;


	public Account(int id) {
		this.id = id;
	}

	public void setUsername(String string) {
		username = string;
		
	}

	public void setPassword(String string) {
		password = string;
	}

	public void setChar(int slot, int id) {
		character[slot] = id;
	}

	public void setStatus(String status) {
		this.status = 0;
		
		if (status.equalsIgnoreCase("banned")) {
			this.status = STATUS_BANNED;
		}

		if (status.equalsIgnoreCase("admin")) {
			this.status = STATUS_ADMIN;
			// Legacy admin accounts get full GM authority unless an
			// explicit gm_level was already loaded from the DB.
			if (this.gmLevel < GM_ADMIN) {
				this.gmLevel = GM_ADMIN;
			}
		}
	}

	/**
	 * Returns this account's CMaNGOS-style GM authority level
	 * ({@link #GM_PLAYER} … {@link #GM_ADMIN}).
	 */
	public int getGmLevel() {
		return gmLevel;
	}

	/** Sets the GM authority level (clamped to {@code >= 0}). */
	public void setGmLevel(int level) {
		this.gmLevel = Math.max(0, level);
	}

	/** True when this account meets or exceeds the required GM level. */
	public boolean hasGmLevel(int required) {
		return gmLevel >= required;
	}

	public int getChar(int slot) {
		return character[slot];
	}

	public int getId() {
		return id;
	}

	/**
	 * Returns this account's UUID (may be {@code null} if not yet loaded
	 * from the database — callers writing freshly-created accounts should
	 * call {@link #setUuid(UUID)} or rely on AccountManager to mint one).
	 */
	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	public String getPassword() {
		return password;
	}

	public String getStatus() {
		if (status == STATUS_BANNED) {
			return "banned";
		}

		if (status == STATUS_ADMIN) {
			return "admin";
		}

		return "";
	}

	public String getUsername() {
		return username;
	}

	public boolean ckeckPassword(String password2) {
		return password.equals(password2);
	}

	boolean isStatus(int i) {
		return (status & i) != 0;
	}

	public boolean deleteChar(int spot) {
		if (character[spot] != 0) {
			PlayerCharacterManager.deleteCharacter(character[spot]);
			character[spot] = 0;
		}
		return true; //todo false if char in use
	}

	public void setCurrentTCPConnection(GameServerTCPConnection tcp) {
		currentTCP = tcp;
	}

	public GameServerTCPConnection getCurrentTCPConnection() {
		return currentTCP;
	}
}
