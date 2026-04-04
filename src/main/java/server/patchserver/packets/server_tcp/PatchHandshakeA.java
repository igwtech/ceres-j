package server.patchserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

public class PatchHandshakeA extends PacketBuilderTCP {

	public PatchHandshakeA() {
		super();
		write(new byte[]{(byte) 0x80, 0x01, 0x73});
	}

}
