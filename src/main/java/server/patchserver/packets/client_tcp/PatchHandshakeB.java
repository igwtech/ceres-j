package server.patchserver.packets.client_tcp;

import server.patchserver.PatchServerConnection;
import server.patchserver.packets.PatchPacketDecoderTCP;
import server.patchserver.packets.server_tcp.PatchHandshakeC;

public class PatchHandshakeB extends PatchPacketDecoderTCP {

	public PatchHandshakeB(byte[] arg0) {
		super(arg0);
	}

	public void execute(PatchServerConnection psc) {
		psc.send(new PatchHandshakeC());
	}
}
