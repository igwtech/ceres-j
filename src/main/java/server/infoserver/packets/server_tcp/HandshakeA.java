package server.infoserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

public final class HandshakeA extends PacketBuilderTCP {

	public HandshakeA() {
		super();
		write(new byte[]{(byte) 0x80, 0x01, 0x66});
	}

}
