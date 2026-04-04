package server.gameserver.packets.client_udp;

import java.net.DatagramPacket;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

public class AbortSession extends GamePacketDecoderUDP {

	public AbortSession(DatagramPacket dp) {
		super(dp);
	}

	public void execute(Player pl) {
		pl.closeTCP();
		pl.closeUDP();
	}

}
