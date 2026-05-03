package server.gameserver;

import java.io.IOException;
import server.exceptions.StartupException;
import server.gameserver.packets.SubtagRouter;
import server.gameserver.packets.client_udp.ChatBroadcast;
import server.gameserver.packets.client_udp.DroneControlPacket;
import server.tools.Out;

public final class GameServer {

	private static ListenerTCP instanceTCP;
	private static ListenerUDP instanceUDP;
	private static WorldMessageBus worldBus;
	private static WorldTickScheduler tickScheduler;
	static boolean keeprunning;

	/** Process-wide message bus used for cross-player intents (chat,
	 *  trade, group, mob aggro, etc). May be null in unit tests. */
	public static WorldMessageBus getBus() {
		return worldBus;
	}

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

			worldBus = new WorldMessageBus();
			ChatManager.installBusHandlers(worldBus);
			registerSubtagRoutes();
			tickScheduler = new WorldTickScheduler(worldBus);
			tickScheduler.start();

			instanceTCP.start();
			instanceUDP.start();
			Out.writeln(Out.Info, "Game Server started");
		}
	}

	/** Bind subtag families to their decoder factories. New families
	 *  (chat 0x3b, mission 0x2a, trade 0x37, etc.) register here. */
	private static void registerSubtagRoutes() {
		// Chat (whisper / team / clan / buddy).
		SubtagRouter.register(0x03, 0x1f, 0x3b, -1,
				(byte[] b) -> new ChatBroadcast(b));
		// Drone-control C->S (mob-state-shaped 0x03/0x2d carrier).
		SubtagRouter.register(0x03, 0x2d, -1, -1,
				(byte[] b) -> new DroneControlPacket(b));
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
			if (tickScheduler != null) {
				tickScheduler.stop();
				tickScheduler = null;
			}
			Out.writeln(Out.Info, "Game Server stopped");
		}
	}
}
