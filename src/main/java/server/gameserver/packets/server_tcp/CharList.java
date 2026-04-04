package server.gameserver.packets.server_tcp;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.PlayerCharacterManager;
import server.networktools.PacketBuilderTCP;

public class CharList extends PacketBuilderTCP {

	static final byte[] CHARDUMMY = {
			(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
			0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 
			0, 0, 0, 0, 0, 1, 1, 1,
			1, 1, 0x21, 0, 0, 0, 0, 1,
			0, 0, 0, 0, 0};
	
	public CharList(Account account) {
		write(0x83);// packet id
		write(0x85);
		write(0);// unknown
		write(0);
		writeShort(4); // number of chars
		writeShort(0x29); //size of charstructure??

		for (int i = 0; i < 4; i++) {
			PlayerCharacter pc = PlayerCharacterManager.getCharacter(account.getChar(i));
			if (pc == null) {
				write(CHARDUMMY);
			} else {
				byte[] name = pc.getName().getBytes();
				
				writeInt(pc.getMisc(PlayerCharacter.MISC_ID));
				writeShort(pc.getModel(PlayerCharacter.MODEL_HEAD));
				writeShort(pc.getModel(PlayerCharacter.MODEL_TORSO));
				writeShort(pc.getModel(PlayerCharacter.MODEL_LEG));
				writeShort(pc.getModel(PlayerCharacter.MODEL_HAIR));
				writeShort(pc.getModel(PlayerCharacter.MODEL_BEARD));
				writeShort(pc.getTexture(PlayerCharacter.TEXTURE_HEAD));
				writeShort(pc.getTexture(PlayerCharacter.TEXTURE_TORSO));
				writeShort(pc.getTexture(PlayerCharacter.TEXTURE_LEG));
				writeInt(pc.getMisc(PlayerCharacter.MISC_LOCATION));
				write(name.length+1);
				write(1); //unknown
				write(1);
				write(1);
				write(1);
				write(1);
				writeInt(pc.getMisc(PlayerCharacter.MISC_PROFESSION));
				write(0);//unknown
				write(pc.getMisc(PlayerCharacter.MISC_CLASS) / 2);
				write(pc.getMisc(PlayerCharacter.MISC_CLASS) % 2);
				writeInt(0);//unknown
				write(name);
				write(0);
			}
		}
	}
}
