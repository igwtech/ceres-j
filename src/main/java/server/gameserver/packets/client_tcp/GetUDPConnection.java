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
		try {
			byte[] sid = pl.getSessionID();
			StringBuilder sidHex = new StringBuilder();
			for (int j = 0; j < sid.length; j++) sidHex.append(String.format("%02x ", sid[j] & 0xFF));
			Out.writeln(Out.Info, "GetUDPConnection: user=" + pl.getAccount().getUsername()
				+ " serverIP=" + pl.getServerIP()
				+ " sessionID=" + sidHex.toString().trim()
				+ " (transformed: " + String.format("%02x %02x %02x %02x %02x %02x %02x %02x",
					127-sid[0], 127-sid[1], 127-sid[2], 127-sid[3],
					127-sid[4], 127-sid[5], 127-sid[6], 127-sid[7]) + ")");
			pl.send(new UDPServerData(pl));
		} catch (Exception e) {
			Out.writeln(Out.Error, "GetUDPConnection failed: " + e.getMessage());
			e.printStackTrace();
		}
	}
}
