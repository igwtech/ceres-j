package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacterManager;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

public class SendPlayerNamebyPlayerId extends PacketBuilderUDP1303{
	
	public SendPlayerNamebyPlayerId(Player pl, int PlayerId) {
		super(pl);
		write(0x23);		//subpacketType
		write(0x06);
		write(0x00);
		writeShort(0x00);
		writeInt(PlayerId);		// PlayerId
		write(PlayerCharacterManager.getCharacter(PlayerId).getName().getBytes());
		write(0x00);
		write(0x76);
	}
}
