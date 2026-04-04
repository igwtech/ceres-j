package server.networktools;

import server.gameserver.Player;

public class PacketBuilderUDP1303 extends PacketBuilderUDP13 {

	public PacketBuilderUDP1303(Player pl) {
		super(pl);
		write(3);
		writeShort(pl.getUdpConnection().incandgetSessionCounter());
	}
	
	public void newSubPacket() {
		super.newSubPacket();
		write(3);
		writeShort(pl.getUdpConnection().incandgetSessionCounter());
	}
}
