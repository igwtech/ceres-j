package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

public class SMovement extends PacketBuilderUDP13{
	
	public SMovement(Player pl, PlayerCharacter pc, int mapId) {
		super(pl);
		write(0x20);
		writeShort(mapId);
		write(0x7f); // UpdateType
		writeShort(pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE) + 32000);
		writeShort(pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE) + 32000);
		writeShort(pc.getMisc(PlayerCharacter.MISC_X_COORDINATE) + 32000);
		write(pc.getMisc(PlayerCharacter.MISC_TILT));
		write(pc.getMisc(PlayerCharacter.MISC_ORIENTATION));
		write(pc.getMisc(PlayerCharacter.MISC_STATUS));
	}
}
