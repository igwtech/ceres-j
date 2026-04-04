package server.infoserver.packets.client_tcp;

import server.infoserver.InfoServerConnection;
import server.infoserver.packets.InfoPacketDecoderTCP;
import server.infoserver.packets.server_tcp.ServerList;

public class GetServerList extends InfoPacketDecoderTCP {

	public GetServerList(byte[] arg0) {
		super(arg0);
	}

	public void execute(InfoServerConnection isc) {
		isc.send(new ServerList(isc));
	}
}
