package server.gameserver.packets.client_tcp;

import server.gameserver.GameServerTCPConnection;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderTCP;
import server.interfaces.GameServerEvent;
import server.tools.Debug;

public final class UnknownClientTCPPacket extends GamePacketDecoderTCP implements GameServerEvent {

	public UnknownClientTCPPacket(byte[] arg0) {
		super(arg0);
	}

	public void execute(Player pl) {
		execute();
	}

	public void execute(GameServerTCPConnection tcp) {
		execute();
	}
	
	public void execute() {
		String report = "Unknown TCP Packet at GameServer:";
		String tmp;
		for (int i = 0; i < count; i+=16) {
			report += "\n    ";
			tmp = "0000" + Integer.toHexString(i);
			report += tmp.substring(tmp.length()-4, tmp.length()) + "  ";
			for (int j = i; j < i+16; j++) {
				if (j < count) {
					tmp = "00" + Integer.toHexString(buf[j]);
					report += tmp.substring(tmp.length()-2, tmp.length()) + " ";
				} else {
					report += "   ";
				}
			}
			report += " ";
			for (int j = i; j < i+16; j++) {
				if (j < count) {
					if (buf[j] < 127 && buf[j] > 31) {
						report += new String(buf, j, 1);
					} else {
						report += ".";
					}
				} else {
					report += " ";
				}
			}
		}
		Debug.unknownPacket(report);
	}
}
