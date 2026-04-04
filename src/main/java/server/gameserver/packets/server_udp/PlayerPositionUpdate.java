package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

public class PlayerPositionUpdate extends PacketBuilderUDP1303{
	
	public PlayerPositionUpdate(Player pl, PlayerCharacter pc, int mapID) { // Player rotation, orientation etc...
		super(pl);
		write(0x1b);		//subpacketType
		writeShort(mapID);		// MapID
		write(0x00);
		write(0x00);
		write(0x03);
		writeShort(pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE) + 32000); // pos y
		writeShort(pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE) + 32000); // pos z
		writeShort(pc.getMisc(PlayerCharacter.MISC_X_COORDINATE) + 32000); // pos x
		write((byte)pc.getMisc(PlayerCharacter.MISC_TILT)); //tilt
		write(0x7d);
		write((byte)pc.getMisc(PlayerCharacter.MISC_ORIENTATION)); // rotation
		write(0x7d);
		write(0x00); // status
		write(0x00);
	}
}