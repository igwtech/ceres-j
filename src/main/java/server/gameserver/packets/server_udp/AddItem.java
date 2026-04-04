package server.gameserver.packets.server_udp;

import server.database.items.Item;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

public class AddItem extends PacketBuilderUDP1303{
	
	public AddItem(Player pl,Item it) {
		super(pl);
		write(0x1f);
		writeShort(pl.getMapID());
		write(0x25);
		write(0x13);
		pl.incrementTransactionID();
		writeShort(pl.getTransactionID());
		write(0x18); // packettype
		write(it.getItemInfoPacketData(Item.PACKET_ADDITEM));
	}
}
