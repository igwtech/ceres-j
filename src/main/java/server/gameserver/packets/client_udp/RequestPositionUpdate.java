package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.CharInfo;
import server.gameserver.packets.server_udp.PositionUpdate;

public class RequestPositionUpdate extends GamePacketDecoderUDP  {

	public RequestPositionUpdate(byte[] subPacket) {
		super(subPacket);
		//content is still unknown
	}

	public void execute(Player pl) {
		pl.send(new PositionUpdate(pl));
		//TODO this is just a workaround
		pl.send(new CharInfo(pl));
	}
}
