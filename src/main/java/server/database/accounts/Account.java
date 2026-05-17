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
	private GameServerTCPConnection currentTCP;

	public static final int STATUS_BANNED = 1;
	public static final int STATUS_ADMIN = 2;


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
		}
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

	/**
	 * Numeric account status: {@code 0} = normal, {@link #STATUS_BANNED},
	 * {@link #STATUS_ADMIN}. Mirrors what {@link #getStatus()} stringifies.
	 */
	public int getStatusCode() {
		return status;
	}

	/**
	 * Set the numeric account status directly. Used by the web admin API
	 * (set_admin / ban / unban) where the caller already has the numeric
	 * constant rather than a label.
	 */
	public void setStatusCode(int code) {
		this.status = code;
	}

	/** @return {@code true} if this account has admin (GM) status. */
	public boolean isAdmin() {
		return status == STATUS_ADMIN;
	}

	/** @return {@code true} if this account is banned. */
	public boolean isBanned() {
		return status == STATUS_BANNED;
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
