package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

public class PositionUpdate extends PacketBuilderUDP1303 {

	public PositionUpdate(Player pl) {
		super(pl);

		PlayerCharacter pc = pl.getCharacter();
		write(new byte[] { 0x2c, 0x01});
		writeShort(pl.getMapID());
		write(new byte[] { 0x00, (byte) 0x00, 0x00});
		writeFloat((float)pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
		writeFloat((float)pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
		writeFloat((float)pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
		write(new byte[] {
				  0x00, 0x00, 0x00, 0x00//float but unknown
				, 0x00, 0x00, 0x00, 0x00//float but unknown
				, 0x00, 0x00, 0x00, 0x00// unknown
		});
		writeShort(pc.getModel(PlayerCharacter.MODEL_HEAD)); //or id of complete model
		write(pc.getTextureIndex(PlayerCharacter.TEXTURE_HEAD));
		write(pc.getTextureIndex(PlayerCharacter.TEXTURE_TORSO));
		write(pc.getTextureIndex(PlayerCharacter.TEXTURE_LEG));
		write(new byte[] { //unknown
				  0x00, 0x00, 0x00, 0x00
				, 0x00, 0x00, 0x00, 0x00
				, 0x00, 0x00, 0x00, 0x00
				, 0x00, 0x00, 0x00, 0x00
				, 0x00, 0x00, 0x00, 0x00
				, 0x00
		});
		writeShort(pc.getModel(PlayerCharacter.MODEL_TORSO));
		writeShort(pc.getModel(PlayerCharacter.MODEL_LEG));
		writeShort(pc.getModel(PlayerCharacter.MODEL_HAIR));
		writeShort(pc.getModel(PlayerCharacter.MODEL_BEARD));
		write(pc.getMisc(PlayerCharacter.MISC_CLASS));
		writeShort(pc.getTexture(PlayerCharacter.TEXTURE_HEAD));
		writeShort(pc.getTexture(PlayerCharacter.TEXTURE_TORSO));
		writeShort(pc.getTexture(PlayerCharacter.TEXTURE_LEG));
	}

}
