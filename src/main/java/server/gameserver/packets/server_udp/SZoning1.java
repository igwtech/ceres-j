package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

public class SZoning1 extends PacketBuilderUDP1303 {

	public SZoning1(int i, Player pl) {
		super(pl);
		write(0x1f);
		writeShort(pl.getMapID());
		write(new byte[] {0x25, 0x13});
		pl.incrementTransactionID();
		writeShort(pl.getTransactionID());
		write(new byte[] {0x0e, 0x02});
		newSubPacket();
	
		write(new byte[] {
				  0x23, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00
				, 0x00, 0x00, 0x00, 0x00, 0x00});
		writeInt(i);
		writeShort(pl.getTransactionID());
		write(new byte[] {0x00, 0x00});
	}
}
