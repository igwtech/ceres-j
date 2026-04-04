package server.gameserver.packets.client_tcp;

import server.gameserver.GameServerTCPConnection;
import server.gameserver.packets.GamePacketDecoderTCP;
import server.gameserver.packets.server_tcp.CharList;

public class GetCharList extends GamePacketDecoderTCP {

	public GetCharList(byte[] arg0) {
		super(arg0);
	}

	public void execute(GameServerTCPConnection tcp) {
		tcp.send(new CharList(tcp.getAccount()));
	}
}
