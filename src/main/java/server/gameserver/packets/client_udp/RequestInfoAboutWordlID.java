package server.gameserver.packets.client_udp;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.WorldNPCInfo;

public class RequestInfoAboutWordlID extends GamePacketDecoderUDP{
	
	public RequestInfoAboutWordlID(byte[] subPacket){
		super(subPacket);
	}
	
	public void execute(Player pl){
		skip(4);
		NPC npc = pl.getZone().getNPC(readInt());
		pl.send(new WorldNPCInfo(pl, npc));
	}
}
