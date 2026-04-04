package server.gameserver.packets;

import java.io.IOException;
import java.io.InputStream;

import server.gameserver.GameServerTCPConnection;
import server.gameserver.packets.client_tcp.*;
import server.interfaces.GameServerEvent;

public final class GamePacketReaderTCP {
        
	public static void readPacket(InputStream is, GameServerTCPConnection tcp) throws IOException {
		if (is.read() != 0xfe) {
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
		tcp.addEvent(decode(readbuffer, tcp));
	}

	private static GameServerEvent decode(byte[] readbuffer, GameServerTCPConnection tcp) throws IOException {
		GamePacketDecoderTCP packet = new UnknownClientTCPPacket(readbuffer);
		switch (packet.read()) {
		case 0x80:
			switch (packet.read()) {
			case 0x00:
				switch (packet.read()) {
				case 0x78:
					return new HandshakeB(readbuffer);//no player
				default:
					return new UnknownClientTCPPacket(readbuffer);
				}
			default:
				return new UnknownClientTCPPacket(readbuffer);
			}
		case 0x83:
			switch (packet.read()) {
			case (byte)0x01:
				return new AuthB(readbuffer);
			default:
				return new UnknownClientTCPPacket(readbuffer);
			}
		case 0x84:
			switch (packet.read()) {
			case 0x80:
				return new Auth(readbuffer); // no player
			case 0x82:
				// typical 0x84 0x82 packet:
				// 00	short	main packet id
				// 02	long	playerid
				// 06	byte	sub packet id
				// 07	short	data size
				// 09	short	unknown size (maybe something like seession id)
				// 11	byte[]	unknown
				// 11+	byte[]	data
				
				if (tcp.getAccount() == null) throw new IOException();
				if (tcp.getAccount().getId() != packet.readInt()) throw new IOException();
				switch (packet.read()) {
				case 3:
					return new DeleteCharacter(readbuffer);
				case 5:
					return new CheckCharacterName(readbuffer);
				case 6:
					return new GetCharList(readbuffer); // no player
				case 7:
					return new CreateCharacter(readbuffer);
				default :
					return new UnknownClientTCPPacket(readbuffer);
				}
			default :
				return new UnknownClientTCPPacket(readbuffer);
			}
		case 0x87:
			switch (packet.read()) {
			case 0x37:
				return new GetGamedata(readbuffer); // no player
			case 0x3c:
				return new GetUDPConnection(readbuffer);
			default :
				return new UnknownClientTCPPacket(readbuffer);
			}
		default :
			return new UnknownClientTCPPacket(readbuffer);
		}
	}

}
