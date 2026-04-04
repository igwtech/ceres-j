package server.gameserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

public class Gamedata extends PacketBuilderTCP {
	
	public Gamedata(){
		write(0x87); //packetid
		write(0x3a);
	}
}
