package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;
import server.tools.Timer;

public class SPing extends PacketBuilderUDP13 {

	public SPing(int clienttime, Player pl) {
		super(pl);
		write(0x0b);
		writeInt(Timer.getIngametime() + 10);
		writeInt(clienttime);
	}
}
