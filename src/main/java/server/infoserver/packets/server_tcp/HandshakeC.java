package server.infoserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

public class HandshakeC extends PacketBuilderTCP {

	public HandshakeC() {
		super();
		write(new byte[]{(byte) 0x80, 0x03, 0x68});
	}
}
