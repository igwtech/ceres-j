package server.gameserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

public class Sync extends PacketBuilderTCP {
	
	public Sync() {
		write(0x83);
		write(0x0d);
		write(0x00);
		write(0x00);
	}
}
