package server.infoserver.packets.client_tcp;

import server.infoserver.InfoServerConnection;
import server.infoserver.packets.InfoPacketDecoderTCP;
import server.interfaces.InfoEvent;
import server.tools.Debug;

public final class UnknownClientTCPPacket extends InfoPacketDecoderTCP implements InfoEvent {

	public UnknownClientTCPPacket(byte[] arg0) {
		super(arg0);
	}

	public void execute(InfoServerConnection isc) {
		String report = "Unknown TCP Packet at InfoServer:";
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
