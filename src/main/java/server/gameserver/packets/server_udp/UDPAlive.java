package server.gameserver.packets.server_udp;

import server.gameserver.GameServerUDPConnection;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP;

public class UDPAlive extends PacketBuilderUDP {

	public UDPAlive(Player pl) {
		GameServerUDPConnection con = pl.getUdpConnection();
		write(0x04);
		write((byte)pl.getMapID());
		write((byte)pl.getUdpConnection().getInterfaceId());
		writeShort(-con.getUdp13Sessionkey());
		writeShort(con.getPort());
	}
}
