package server.database;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import server.database.accounts.AccountManager;
import server.database.items.ItemInfoManager;
import server.database.modelTextures.ModelTextureManager;
import server.database.playerCharacters.PlayerCharacterManager;
import server.database.worlds.WorldManager;
import server.exceptions.StartupException;
import server.tools.Out;

public final class Database {

	private static ScheduledExecutorService autoSaveScheduler;

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

		// Initialize SQLite connection first
		SqliteDatabase.init();

		ItemInfoManager.init();
		AccountManager.init();
		PlayerCharacterManager.init();
		ModelTextureManager.init();
		WorldManager.init();

		// Start periodic auto-save every 60 seconds
		startAutoSave();
	}

	private static void startAutoSave() {
		autoSaveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "Database-AutoSave");
			t.setDaemon(true);
			return t;
		});

		autoSaveScheduler.scheduleAtFixedRate(() -> {
			try {
				AccountManager.save();
				PlayerCharacterManager.save();
				Out.writeln(Out.Info, "Auto-save completed");
			} catch (Exception e) {
				Out.writeln(Out.Error, "Auto-save failed: " + e.getMessage());
			}
		}, 60, 60, TimeUnit.SECONDS);

		Out.writeln(Out.Info, "Auto-save scheduled every 60 seconds");
	}

	public static void stopServer() {
		// Stop auto-save scheduler
		if (autoSaveScheduler != null) {
			autoSaveScheduler.shutdown();
			try {
				autoSaveScheduler.awaitTermination(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				autoSaveScheduler.shutdownNow();
			}
		}

		// Final save
		PlayerCharacterManager.stopServer();
		AccountManager.stopServer();

		// Close SQLite connection
		SqliteDatabase.close();

		Out.writeln(Out.Info, "Database saved");
	}
}
