package server.gameserver;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.Random;

import server.interfaces.ServerUDPPacket;
import server.networktools.PacketObfuscator;
import server.tools.Debug;

public class GameServerUDPConnection {

	private InetAddress clientaddress;
	private int clientport;
	private Player player;
	private boolean handshakingState = false;
	private short udp13Sessionkey;
	private int udpSessionCounter;
	private int interfaceID;

	public GameServerUDPConnection(InetAddress address, int port, Player pl) {
		clientaddress = address;
		clientport = port;
		player = pl;
		udp13Sessionkey = (short) new Random().nextInt();
//		public final static int SESSIONKEY = 49732;
		udpSessionCounter = 0;
	}

	public int getPort() {
		return clientport;
	}

	public InetAddress getAddress() {
		return clientaddress;
	}

	public Player getPlayer() {
		return player;
	}

	public boolean getHandshakingState() {
		return handshakingState;
	}

	public void setHandshakingState(boolean b) {
		handshakingState = b;
	}

	public short getUdp13Sessionkey() {
		return udp13Sessionkey;
	}

	public void send(ServerUDPPacket packet) {
		Debug.sendPacket(packet, this);
		try {
			DatagramPacket dp[] = packet.getDatagramPackets();
			for (int i = 0; i < dp.length; i++) {
				// Send UDP responses WITHOUT encryption.
				// The client encrypts outgoing but expects unencrypted server responses
				// (confirmed by pcap: real server sends packet 405 with raw 0x01 header).
				dp[i].setAddress(clientaddress);
				dp[i].setPort(clientport);
				ListenerUDP.getSocket().send(dp[i]);
			}
		} catch (IOException e) {}
	}

	public int getSessionCounter() {
		return udpSessionCounter;
	}

	public int incandgetSessionCounter() {
		return ++udpSessionCounter;
	}

	public void close() {
		ListenerUDP.close(this);
	}

	public int getInterfaceId() {
		return interfaceID;
	}

	public void setInterfaceId(int i) {
		interfaceID = i;
	}
}