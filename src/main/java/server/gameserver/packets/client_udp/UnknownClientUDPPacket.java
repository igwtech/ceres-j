package server.gameserver.packets.client_udp;

import java.net.DatagramPacket;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.tools.Debug;

public class UnknownClientUDPPacket extends GamePacketDecoderUDP {

	private String report;

	public UnknownClientUDPPacket(DatagramPacket dp) {
		super(dp);
		report = "Unknown UDP Packet from user ";
	}

	public UnknownClientUDPPacket(byte[] subPacket) {
		super(subPacket);
		report = "Unknown UDP13 Packet from user ";
	}

	public void execute(Player pl) {
		report += pl.getAccount().getUsername() + " :";
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
