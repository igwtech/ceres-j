package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.ShortPlayerInfo;

public class RequestShortPlayerInfo extends GamePacketDecoderUDP{
	
	public RequestShortPlayerInfo(byte[] subPacket){
		super(subPacket);
	}
	
	public void execute(Player pl){
		skip(4);
		int mapId = readShort();
		Player pl2 = pl.getZone().getPlayer(mapId);
		if(pl2 != null)
			pl.send(new ShortPlayerInfo(pl, pl2.getCharacter(), mapId));
	}
}