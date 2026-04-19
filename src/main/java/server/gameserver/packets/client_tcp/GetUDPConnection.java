package server.gameserver.packets.client_tcp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderTCP;
import server.gameserver.packets.server_tcp.UDPServerData;
import server.tools.Out;

public class GetUDPConnection extends GamePacketDecoderTCP {

	public GetUDPConnection(byte[] arg0) {
		super(arg0);
	}

	public void execute(Player pl) {
		// No-op. GetGamedata already sends UDPServerData + Location.
		// Sending a SECOND UDPServerData here causes the client to
		// create a second WinSockMGR socket (zone-handoff) — which
		// retail never does (retail's GetGamedata and GetUDPConnection
		// arrive together and the server responds with ONE combined
		// burst). Our delayed GetGamedataAnswer splits them, causing
		// the second UDPServerData to arrive after the session is
		// established, triggering socket recreation.
		Out.writeln(Out.Info, "GetUDPConnection: no-op (UDPServerData already sent by GetGamedata)");
	}
}
