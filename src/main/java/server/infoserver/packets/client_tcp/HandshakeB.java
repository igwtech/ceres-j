package server.infoserver.packets.client_tcp;

import server.infoserver.InfoServerConnection;
import server.infoserver.packets.InfoPacketDecoderTCP;
import server.infoserver.packets.server_tcp.HandshakeC;

public final class HandshakeB extends InfoPacketDecoderTCP {

	public HandshakeB(byte[] arg0) {
		super(arg0);
	}

	public void execute(InfoServerConnection isc) {
		isc.send(new HandshakeC());
	}
}
