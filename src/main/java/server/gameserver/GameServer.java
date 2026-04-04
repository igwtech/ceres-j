package server.gameserver;

import java.io.IOException;
import server.exceptions.StartupException;
import server.tools.Out;

public final class GameServer {

	private static ListenerTCP instanceTCP;
	private static ListenerUDP instanceUDP;
	static boolean keeprunning;

	public static void init() throws StartupException {
		if (instanceTCP == null) {
			Out.writeln(Out.Info, "Starting Game Server");

			try {
				instanceTCP = new ListenerTCP(12000);
				instanceUDP = new ListenerUDP(5000);
			} catch (IOException e) {
				instanceTCP = null;
				instanceUDP = null;
				throw new StartupException("Exception while opening serversocket: " + e.getMessage());
			}
			
			keeprunning = true;
			ZoneManager.init();
			instanceTCP.start();
			instanceUDP.start();
			Out.writeln(Out.Info, "Game Server started");
		}
	}

	public static void stopServer() {
		if (instanceTCP != null) {
			Out.writeln(Out.Info, "Stopping Game Server");
			keeprunning = false;
			synchronized (instanceTCP) {
				while (instanceTCP.running) {
					try {
						instanceTCP.wait(100);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			}
			synchronized (instanceUDP) {
				while (instanceUDP.running) {
					try {
						instanceUDP.wait(100);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}				
			}
						
			// TODO send a shutdown message to all players
			// TODO stop all threads of tcpconnections, players and zones
			ZoneManager.stop();
			Out.writeln(Out.Info, "Game Server stopped");
		}
	}
}
