package server.gameserver.packets.client_tcp;

import server.gameserver.GameServerTCPConnection;
import server.gameserver.packets.GamePacketDecoderTCP;
import server.gameserver.packets.server_tcp.RequestFailed;
import server.gameserver.packets.server_tcp.RequestSuccess;

public class DeleteCharacter extends GamePacketDecoderTCP{

	public DeleteCharacter(byte[] arg0) {
		super(arg0);
	}

	public void execute(GameServerTCPConnection tcp) {
		skip(7); //packet header
		skip(2); // data size
		int unknownsize = readShort();
		skip(unknownsize);
		int spot = read();

		if (tcp.getAccount().deleteChar(spot)) {
			tcp.send(new RequestSuccess());
		} else {
			tcp.send(new RequestFailed("ERROR"));
		}
	}

}
