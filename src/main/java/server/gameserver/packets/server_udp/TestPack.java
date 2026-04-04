package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

public class TestPack extends PacketBuilderUDP13{
	
	public TestPack(Player pl, String string) {
		super(pl);
		int pos = 0;
		int sizeleft = string.length();
		String stest = new String();
		stest += "test:";
		while(sizeleft > 1){
			byte test = (byte)Integer.parseInt(string.substring(pos, pos + 2), 16);
			write(test);
			pos += 3;
			sizeleft -= 3;
			stest += test + " ";
		}	
		pl.send(new server.gameserver.packets.server_udp.LocalChatMessage(pl,stest));
	}
}
