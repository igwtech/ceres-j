package server.gameserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

public class RequestSuccess extends PacketBuilderTCP {

	public RequestSuccess() {
		super();
		write(0x83);
		write(0x86);
		writeShort(1); //success
		writeShort(0); //messagelength
		write(0);
	}

}
