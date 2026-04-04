package server.database;

import java.io.File;

import server.database.accounts.AccountManager;
import server.database.items.ItemInfoManager;
import server.database.modelTextures.ModelTextureManager;
import server.database.playerCharacters.PlayerCharacterManager;
import server.database.worlds.WorldManager;
import server.exceptions.StartupException;
import server.tools.Out;

public final class Database {

	public static void init() throws StartupException {

		File directorycheck = new File("database");
		if (!directorycheck.isDirectory()) {
			if (directorycheck.exists()) {
				throw new StartupException("database is not a directory!");
			} else {
				directorycheck.mkdir();
			}
		}
		Out.writeln(Out.Info, "Starting Database");

		ItemInfoManager.init();
		AccountManager.init();
		PlayerCharacterManager.init();
		ModelTextureManager.init();
		WorldManager.init();
	}

	public static void stopServer() {
		PlayerCharacterManager.stopServer();
		AccountManager.stopServer();
		Out.writeln(Out.Info, "Database saved");
	}
}