package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

public class OpenDoor extends PacketBuilderUDP1303 {

	public OpenDoor(int id, Player pl) {
		super(pl);
		write(0x1b);
		writeInt(id);
		write(0x20);
		writeInt(0); //or 5?
		write(3); // or 3?
		write(0x15);
	}
}
