package server.infoserver.packets;

import java.io.IOException;
import java.io.InputStream;

import server.infoserver.InfoServerConnection;
import server.infoserver.packets.client_tcp.Auth;
import server.infoserver.packets.client_tcp.GetServerList;
import server.infoserver.packets.client_tcp.HandshakeB;
import server.infoserver.packets.client_tcp.UnknownClientTCPPacket;
import server.interfaces.InfoEvent;


public final class InfoPacketReaderTCP {

	public static final int PACKET_TYPE_INFO = 0xfe;

	public static void readPacket(InputStream is, InfoServerConnection isc) throws IOException {
		if (is.read() != InfoPacketReaderTCP.PACKET_TYPE_INFO) {
			throw new IOException();
		}
		int size = is.read() | (is.read() << 8);
		if (size < 1) {
			throw new IOException();
		}
		byte[] readbuffer = new byte[size];
		if (size != is.read(readbuffer, 0, size)) {
			throw new IOException();
		}
		isc.addEvent(decode(readbuffer, isc));
	}

	private static InfoEvent decode(byte[] readbuffer, InfoServerConnection isc) throws IOException {
		InfoPacketDecoderTCP packet = new InfoPacketDecoderTCP(readbuffer);
		switch (packet.read()) {
		case 0x80:
			switch (packet.read()) {
			case 0x00:
				switch (packet.read()) {
				case 0x78:
					return new HandshakeB(readbuffer);
				default:
					return new UnknownClientTCPPacket(readbuffer);
				}
			default:
				return new UnknownClientTCPPacket(readbuffer);
			}
		case 0x84:
			switch (packet.read()) {
			case 0x80:
				return new Auth(readbuffer);
			case 0x82:
				// typical 0x84 0x82 packet:
				// 00	short	main packet id
				// 02	long	playerid
				// 06	byte	sub packet id
				// 07	short	data size
				// 09	short	unknown size (maybe something like seession id)
				// 11	byte[]	unknown
				// 11+	byte[]	data
				
				if (!isc.checkAccountID(packet.readInt())) throw new IOException();
				switch (packet.read()) {
				case 1:
					return new GetServerList(readbuffer);
				default :
					return new UnknownClientTCPPacket(readbuffer);
				}
			default :
				return new UnknownClientTCPPacket(readbuffer);
			}
		default :
			return new UnknownClientTCPPacket(readbuffer);
		}
	}
}
