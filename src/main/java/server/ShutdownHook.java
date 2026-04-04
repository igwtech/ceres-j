package server;

import server.database.Database;
import server.gameserver.GameServer;
import server.infoserver.InfoServer;
import server.patchserver.PatchServer;
import server.tools.Config;
import server.tools.Debug;
import server.tools.Out;
import server.tools.Timer;
import server.webserver.WebServer;

public class ShutdownHook extends Thread {

	private static ShutdownHook instance = new ShutdownHook();

	public static void init() {
		Runtime.getRuntime().addShutdownHook(instance);
	}

	public void run() {
		WebServer.stopServer();
		PatchServer.stopServer();
		InfoServer.stopServer();
		GameServer.stopServer();
		
		Timer.stopServer();
		
		Database.stopServer();
		Debug.stopServer();
		Config.stopServer();
		Out.stopServer();
	}
}
