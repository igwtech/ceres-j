package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.SendPlayerNamebyPlayerId;

public class RequestPlayerNamebyPlayerId extends GamePacketDecoderUDP{
	
	public RequestPlayerNamebyPlayerId(byte[] subPacket){
		super(subPacket);
	}
	
	public void execute(Player pl){
		skip(8);
		int PlayerId = readInt();
		pl.send(new SendPlayerNamebyPlayerId(pl, PlayerId));
	}
}
