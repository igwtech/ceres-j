package server.infoserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

public class RequestFailed extends PacketBuilderTCP {

	public RequestFailed(String text) {
		super();
		byte[] textarray = text.getBytes();
		write(0x83);
		write(0x86);
		writeShort(0); //failed
		writeShort(textarray.length+1); //messagelength
		write(textarray);
		write(0);
		write(0);
	}
}
