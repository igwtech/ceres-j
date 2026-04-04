package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

public class InventoryMoveDenied extends PacketBuilderUDP13{
	
	public InventoryMoveDenied(Player pl, int isrccont, int srcpos, int idstcont, int dstpos) {
		super(pl);
		write(0x1f);
		writeShort(pl.getMapID());
		write(0x25); // UpdateType
		write(0x13);
		pl.incrementTransactionID();
		writeShort(pl.getTransactionID());
		write(0x14);
		write(isrccont);
		writeShort(srcpos);
		write(idstcont);
		writeShort(dstpos);
		write(0x00);
	}

}
