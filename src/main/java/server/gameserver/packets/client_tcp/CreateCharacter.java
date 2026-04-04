package server.gameserver.packets.client_tcp;

import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.PlayerCharacterManager;
import server.gameserver.GameServerTCPConnection;
import server.gameserver.packets.GamePacketDecoderTCP;
import server.gameserver.packets.server_tcp.RequestFailed;
import server.gameserver.packets.server_tcp.RequestSuccess;

public class CreateCharacter extends GamePacketDecoderTCP {

	public CreateCharacter(byte[] arg0) {
		super(arg0);
	}

	public void execute(GameServerTCPConnection tcp) {
		skip(7);
		PlayerCharacter pc = new PlayerCharacter();
		
		skip(2);// datasize
		int unknownsize = readShort();
		skip(unknownsize);
		int spot = readInt();
		int classid = read();
		pc.setMisc(PlayerCharacter.MISC_PROFESSION, readInt());
		pc.setMisc(PlayerCharacter.MISC_CLASS, read() + (classid * 2));
		pc.setModel(PlayerCharacter.MODEL_HEAD, readInt());
		pc.setModel(PlayerCharacter.MODEL_TORSO, readInt());
		pc.setModel(PlayerCharacter.MODEL_LEG, readInt());
		pc.setModel(PlayerCharacter.MODEL_HAIR, readInt());
		pc.setModel(PlayerCharacter.MODEL_BEARD, readInt());
		pc.setTexture(PlayerCharacter.TEXTURE_HEAD, readInt());
		pc.setTexture(PlayerCharacter.TEXTURE_TORSO, readInt());
		pc.setTexture(PlayerCharacter.TEXTURE_LEG, readInt());
		pc.setMisc(PlayerCharacter.MISC_FACTION, readInt());
		int namelength = read();
		int subskills = read();
		pc.setName(readCString(namelength));
		for (int i=0; i < subskills; i++) {
			int id = read();
			int pts = read();
			pc.setSubskillLVL(id, pts);
		}

		if (PlayerCharacterManager.createCharacter(pc, tcp.getAccount(), spot)) {
			tcp.send(new RequestSuccess());
		} else {
			tcp.send(new RequestFailed("ERROR"));
		}
	}

}
