package server.gameserver.packets.client_tcp;

import server.gameserver.GameServerTCPConnection;
import server.gameserver.packets.GamePacketDecoderTCP;
import server.gameserver.packets.server_tcp.HandshakeC;

public final class HandshakeB extends GamePacketDecoderTCP {

	public HandshakeB(byte[] arg0) {
		super(arg0);
	}

	public void execute(GameServerTCPConnection tcp) {
		tcp.send(new HandshakeC());
	}
}
