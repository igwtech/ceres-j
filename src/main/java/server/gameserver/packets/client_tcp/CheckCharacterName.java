package server.gameserver.packets.client_tcp;

import server.database.playerCharacters.PlayerCharacterManager;
import server.gameserver.GameServerTCPConnection;
import server.gameserver.packets.GamePacketDecoderTCP;
import server.gameserver.packets.server_tcp.CharOpAck;

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

		// Retail: name-check returns 0x8386 with status 0x3d (preview ok)
		// when the name is available, 0x06 + ASCII reason when taken.
		// See docs/protocol/flows/character_creation.md for byte-level.
		if (PlayerCharacterManager.checkCharName(name)) {
			tcp.send(CharOpAck.previewAck());
		} else {
			tcp.send(CharOpAck.error("Name already in use."));
		}
	}

}
