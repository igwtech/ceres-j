package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.SPing;

public class CPing extends GamePacketDecoderUDP {

	public CPing(byte[] subPacket) {
		super(subPacket);
	}

	public void execute(Player pl) {
		skip(1); //0x0b
		pl.send(new SPing(readInt(), pl));
		pl.setLastping();
	}

}
