package server.database.accounts;

import server.database.playerCharacters.PlayerCharacterManager;
import server.gameserver.GameServerTCPConnection;

public class Account {

	private int id;
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
