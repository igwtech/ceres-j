package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.TimeSync;

public class GetTimeSync extends GamePacketDecoderUDP {

	private int clienttime;

	public GetTimeSync(byte[] subPacket) {
		super(subPacket);
		skip(1);
		clienttime = readInt();
	}

	public void execute(Player pl) {
		pl.send(new TimeSync(pl, clienttime));
//		new ZoneEntered().send(session);
		pl.getZone().sendPlayersinZone(pl);
		pl.getZone().sendnewPlayerinZone(pl);
	}
}
