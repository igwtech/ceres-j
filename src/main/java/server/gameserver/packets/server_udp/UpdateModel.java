package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

public class UpdateModel extends PacketBuilderUDP1303 {

	public UpdateModel(Player player) {
		super(player);
		PlayerCharacter pc = player.getCharacter();
		//packet start
		write(0x2f);
		writeShort(player.getMapID());

		//now follow the subpackets
		
		write(0x01); //unknown subpacket but always there except on dynamic updates
		write(0x00);
		write(0x20);

		write(0x02); //unknown
		write(0x01);
		write(0x07);
		
		write(0x02); //unknown
		write(0x05);
		write(0x8a); //8c/8a?
		
//		write(0x02); //unknown optional 
//		write(0x06);
//		write(0x0a);

		write(0x02); //unknown
		write(0x08);
		write(0x01); // 1/2

//		write(0x02); //weapon on right side optional
//		write(0x0a);
//		writeShort(0x0009); //weapon id

//		write(0x02); //weapon on back optional (if u want an update to remove it, send -1 as weapon id)
//		write(0x0c);
//		writeShort(0x0046); //weapon id

		write(0x02); //hair model
		write(0x0d);
		writeShort(pc.getModel(PlayerCharacter.MODEL_HAIR));

		write(0x02); //beard model
		write(0x0e);
		writeShort(pc.getModel(PlayerCharacter.MODEL_BEARD));

		write(0x03); //model data
		write(0x00);
		write(0x0f); //size?
		writeShort(pc.getModel(PlayerCharacter.MODEL_HEAD));
		//writeShort(620);
		write(pc.getTextureIndex(PlayerCharacter.TEXTURE_HEAD));
		write(pc.getTextureIndex(PlayerCharacter.TEXTURE_TORSO));
		write(pc.getTextureIndex(PlayerCharacter.TEXTURE_LEG));
		writeShort(0); //unknown 0xe6e6 in hacknet
		writeShort(0); //unknown 0xe6e6 in hacknet
		writeShort(0); //unknown 0xe6e6 in hacknet
		writeShort(pc.getModel(PlayerCharacter.MODEL_TORSO));
		writeShort(pc.getModel(PlayerCharacter.MODEL_LEG));

		write(0x03); //name
		write(0x01);
		byte[] name = pc.getName().getBytes();
		write(name.length +1);
		write(name);
		write(0x00); //c-style

		write(0x03); //unknown
		write(0x03);
		write(0x00);
	}
}
