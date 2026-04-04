package server.gameserver.packets.client_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.inventory.PlayerInventory;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.SZoning1;

public class Zoning1 extends GamePacketDecoderUDP {

	public Zoning1(byte[] subPacket) {
		super(subPacket);
	}

	public void execute(Player pl) {
//		skip(10); // this has changed somewhere before 157
		skip(14);
		pl.getCharacter().setMisc(PlayerCharacter.MISC_LOCATION, readInt());
		pl.updateZone();
		((PlayerInventory)pl.getCharacter().getContainer(PlayerCharacter.PLAYERCONTAINER_F2)).doSort();
		pl.send(new SZoning1(readInt(), pl));
	}
}
