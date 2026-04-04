package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

public class LocalChatMessage extends PacketBuilderUDP1303{
	
	public LocalChatMessage(Player pl, String message) {
		super(pl);
		write(0x1f);
		write(0x00);	// (short) id of Player on map?
		write(0x00);
		write(0x1b);
		write(message.getBytes());	
	}
	
	public LocalChatMessage(Player pl, String message, int mapId) {
		super(pl);
		write(0x1f);
		writeShort(mapId);
		write(0x1b);
		write(message.getBytes());	
	}
}
