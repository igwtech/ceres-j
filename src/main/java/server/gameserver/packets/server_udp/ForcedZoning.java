package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

public class ForcedZoning extends PacketBuilderUDP1303{
	
	public ForcedZoning(Player pl, int loc) {
		super(pl);
		write(0x1f);
		writeShort(pl.getMapID());
		write(0x38);
		write(0x04); // map id?
		write(0x00);		// unknown vielleicht anzahl spieler oder npcs/items in der zone?
		writeInt(loc);
		write(0x01);		// spawnID
		write(0x00);
	}
	
	public ForcedZoning(Player pl, int loc, int spawnID) {
		super(pl);
		write(0x1f);
		writeShort(pl.getMapID());
		write(0x38);
		write(0x04);
		write(0x00);		// unknown vielleicht anzahl spieler oder npcs/items in der zone?
		writeInt(loc);
		write(spawnID);		// spawnID
		write(0x00);
	}

}
