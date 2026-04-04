package server.patchserver.packets.client_tcp;

import server.patchserver.PatchServerConnection;
import server.patchserver.packets.PatchPacketDecoderTCP;
import server.patchserver.packets.server_tcp.PatchSendVersion;

public class PatchRequestVersion extends PatchPacketDecoderTCP {

	public PatchRequestVersion(byte[] arg0) {
		super(arg0);
	}

	public void execute(PatchServerConnection psc) {
		skip(4);
		psc.send(new PatchSendVersion(readShort()));
	}
}
