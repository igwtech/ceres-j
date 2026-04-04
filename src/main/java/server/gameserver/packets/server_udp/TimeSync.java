package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

public class TimeSync extends PacketBuilderUDP1303 {

	public TimeSync(Player pl, int clienttime) {
		super(pl);
		write(0x0d);
		writeInt(1); //ingame time? TODO
		writeInt(clienttime);
		write(new byte[]{(byte)0xfb, 0x0a, 0x00, 0x00}); //worldserver id?
	}
}
