package server.interfaces;

import server.gameserver.GameServerTCPConnection;
import server.gameserver.Player;

public interface GameServerEvent extends Event {

	void execute(GameServerTCPConnection tcp);
	void execute(Player pl);
}
