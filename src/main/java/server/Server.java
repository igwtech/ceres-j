package server;

import java.io.IOException;

import server.database.Database;
import server.exceptions.StartupException;
import server.gameserver.GameServer;
import server.infoserver.InfoServer;
import server.patchserver.PatchServer;
import server.tools.Config;
import server.tools.Debug;
import server.tools.Out;
import server.tools.Timer;
import server.gui.*;
import server.webserver.WebServer;
/**
 * 
 * This class holds the main method, starts and initializes everything.<br>
 * Initializes: <br>
 * 		- out<br>
 * 		- Config<br>
 * 		- Debug<br>
 * 		- Database<br>
 * 		- Timer<br>
 * 		- GameServer<br>
 * 		- InfoServer<br>
 * 		- PatchServer<br>
 * 		- WebServer<br>
 * 		- ShutdownHook<br>
 * 		- Gui<br>
 *
 */

public class Server {

	/**
	 * the holy main mehtod
	 * @param args
	 */
	public static void main(String[] args) {
		try {
			Out.init();
			Config.init();
			Debug.init();
			Database.init();
			Timer.init();

			GameServer.init();
			InfoServer.init();
			PatchServer.init();
			WebServer.init();
			
			ShutdownHook.init();
			
			Gui.init();
			
			try {
				System.in.read();
			} catch (IOException e) {
				// Stdin read failed; proceeding to exit
			}
			System.exit(0);
		} catch (StartupException e) {
			Out.writeln(Out.Error, e.getMessage());
			e.printStackTrace();
			System.exit(-1);
		}

	}

}
