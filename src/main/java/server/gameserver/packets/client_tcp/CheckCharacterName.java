package server.gameserver.packets.client_tcp;

import server.database.playerCharacters.PlayerCharacterManager;
import server.gameserver.GameServerTCPConnection;
import server.gameserver.packets.GamePacketDecoderTCP;
import server.gameserver.packets.server_tcp.RequestFailed;
import server.gameserver.packets.server_tcp.RequestSuccess;

public class CheckCharacterName extends GamePacketDecoderTCP {

	public CheckCharacterName(byte[] arg0) {
		super(arg0);
	}

	public void execute(GameServerTCPConnection tcp) {
		skip(7);
		int namelength = readShort();
		int unknownlength = readShort();
		skip(unknownlength);
		String name = readCString(namelength);

		if (PlayerCharacterManager.checkCharName(name)) {
			tcp.send(new RequestSuccess());
		} else {
			tcp.send(new RequestFailed("ERROR"));
		}
	}

}
