package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

public class ShortPlayerInfo extends PacketBuilderUDP1303{
	
	public ShortPlayerInfo(Player pl, PlayerCharacter pc, int mapId) {
		super(pl);
		write(0x30);		//subpacketType
		writeShort(mapId);		// MapID
		write(0x00);
		write(0x00);
		writeInt(pc.getMisc(PlayerCharacter.MISC_ID));		// Player ID
		write(pc.getName().getBytes());
		write(0x00);
	}
}
