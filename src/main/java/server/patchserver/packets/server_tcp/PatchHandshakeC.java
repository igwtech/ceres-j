package server.patchserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

public class PatchHandshakeC extends PacketBuilderTCP {

	public PatchHandshakeC() {
		super();
		write(new byte[] {(byte) 0x80, 0x03, 0x6b });
	}

}
