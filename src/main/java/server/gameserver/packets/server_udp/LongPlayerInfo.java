package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

public class LongPlayerInfo extends PacketBuilderUDP1303{
	
	public LongPlayerInfo(Player pl, PlayerCharacter pc, int mapId) {
		super(pl);
		write(0x25);		//subpacketType
		writeShort(mapId);		// MapID
		writeInt(pc.getMisc(PlayerCharacter.MISC_ID));		// Player ID
		write(0x00); // hast to do something with the information beeing send
		write(0x08);
		write(0x09);
		write(0x6c);
		write(0x02);
		write(0x40);
		write(0x40);
		write(0xc4);
		write(0x3c); // baserank
		write(0x00); //probably cr
		write(pc.getMisc(PlayerCharacter.MISC_FACTION)); // fraction
		write(0x00);
		writeInt(0xffffffff);
		write(0xff); // weapon the player has on its back
		write(0xff); // - " - pc.getEquippedWeapon();
		writeShort(pc.getModel(PlayerCharacter.MODEL_HAIR));
		//write(0x00);
		writeShort(pc.getModel(PlayerCharacter.MODEL_BEARD)); // Model Beard
		writeInt(0xffffffff);
		write(0x0f);
		writeShort(pc.getModel(PlayerCharacter.MODEL_HEAD));
		write(pc.getTextureIndex(PlayerCharacter.TEXTURE_HEAD));
		write(pc.getTextureIndex(PlayerCharacter.TEXTURE_TORSO));
		write(pc.getTextureIndex(PlayerCharacter.TEXTURE_LEG));
		write(0x00);
		write(0x00);
		write(0x00);
		write(0x00);
		write(0x00);
		write(0x00);
		writeShort(pc.getModel(PlayerCharacter.MODEL_TORSO));
		writeShort(pc.getModel(PlayerCharacter.MODEL_LEG));
		write(pc.getName().length() + 1);
		write(pc.getName().getBytes());
		write(0x00);
		write(0x00);
		//write(0x00);
		write(0x04);
		write(0xd3);
		write(0x4b);
		write(0x00);
		write(0x00);
	}

}
/*
write(0x25);		//subpacketType
write(0x02);		// MapID
write(0x00);
writeInt(12774);		// Player ID
write(0x40); // hast to do something with the information beeing send
write(0x06);
write(0x08);
write(0x00);
write(0x00);
write(0x40);
write(0x40);
write(0xe4);
write(0x3c); // baserank
write(0x00); //probably cr
write(0x06); // fraction
write(0x00);
writeInt(0xffffffff);
write(0x00); // weapon the player has on its back
write(0xe0); // - " -
write(0x00);
write(0x00);
write(0x79); // Model Beard
write(0x04); // - " -
writeInt(0xffffffff);
write(0x0f);
write(0x23);
write(0x00);
write(0xff);
write(0xff);
write(0xff);
write(0x00);
write(0x00);
write(0x00);
write(0x00);
write(0x00);
write(0x00);
write(0x00);
write(0x00);
write(0x00);
write(0x00);
write(0x09);
write((name.getBytes());
write(0x00);
write(0x00);
write(0x04);
write(0xd3);
write(0x4b);
write(0x00);
write(0x00);*/