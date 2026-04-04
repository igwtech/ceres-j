package server.gameserver.packets;

import java.net.DatagramPacket;

import server.gameserver.Player;
import server.gameserver.packets.client_udp.*;
import server.interfaces.GameServerEvent;

public final class GamePacketReaderUDP {

	public static void readPacket(DatagramPacket dp, Player pl) {
		GamePacketDecoderUDP pd = new UnknownClientUDPPacket(dp);
		if (pd.read() == 0x13) {
			pd.skip(4); //packet counter // big TODO check this !!!
			int datasize;
			while ((datasize = pd.read()) > 0) {
				byte[] subPacket = new byte[datasize];
				pd.read(subPacket);
				pl.addEvent(decodesub13(subPacket));
			}
		} else {
			pl.addEvent(decode(dp, pl));
		}
	}

	private static GameServerEvent decode(DatagramPacket dp, Player pl) {
		GamePacketDecoderUDP pd = new UnknownClientUDPPacket(dp);
		switch (pd.read()) {
		case 0x01:
			return new HandshakeUDP(dp);
		case 0x03:
			return new SyncUDP(dp);
		case 0x08:
			return new AbortSession(dp);
		default:
			pd.reset();
			return pd;
		}
	}

	private static GameServerEvent decodesub13(byte[] subPacket) {
		UnknownClientUDPPacket pd = new UnknownClientUDPPacket(subPacket);
		switch (pd.read()) {
		case 0x03:
			pd.skip(2); // TODO we should check this counter
			switch (pd.read()) {
			case 0x01:
				//resend packets
				// TODO implement this
				return null;
			case 0x1f:
				pd.skip(2);
				switch (pd.read()) {
				case 0x17:
					return new UseItem(subPacket);
				case 0x1b:
					return new LocalChat(subPacket);
				case 0x1e:
					return new InventoryMove(subPacket);
				case 0x25:{
					switch(pd.read()){
					case 0x14:
						return new InsideF2InvMove(subPacket);
					case 0x17:
						return new InventoryCombineItems(subPacket);
					default:
						pd.reset();
						return pd;
					}
				}
				case 0x3b:
					return new GlobalChat(subPacket);
				case 0x4c:
					return new ChangedChannels(subPacket);
				default:
					pd.reset();
					return pd;
				}
			case 0x22:
				switch (pd.read()) {
				case 0x03:
					return new Zoning2(subPacket);
//				case 0x06:
//					AddtoChat.action(subPacket, this);
//					break;
				case 0x06:{
					pd.skip(1);
					switch(pd.read()){
					case 0x00:
						return new RequestPlayerNamebyPlayerId(subPacket);
					}
				}
				case 0x0d:
					return new Zoning1(subPacket);
				default:
					pd.reset();
					return pd;
				}
			case 0x27:
				return new RequestInfoAboutWordlID(subPacket);
			case 0x31:
				return new RequestShortPlayerInfo(subPacket);
			default:
				pd.reset();
				return pd;
			}
		case 0x0b:
			return new CPing(subPacket);
		case 0x0c:
			return new GetTimeSync(subPacket);
		case 0x20:
			return new Movement(subPacket);
		case 0x2a:
			return new RequestPositionUpdate(subPacket);
		default:
			pd.reset();
			return pd;
		}
	}
}
