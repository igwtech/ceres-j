package server.gameserver;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.Random;

import server.interfaces.ServerUDPPacket;
import server.networktools.PacketObfuscator;
import server.tools.Debug;
import server.tools.Out;

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
		// Prefer the per-player listener socket so outgoing datagrams carry
		// the session-specific source port that the client expects (NC2
		// retail binds a unique server UDP port per session and uses it as
		// the session id). Fall back to the shared ListenerUDP socket for
		// legacy paths, unit tests, or when port-pool allocation failed.
		java.net.DatagramSocket socket = null;
		if (player != null && player.getUdpListener() != null) {
			socket = player.getUdpListener().getSocket();
		}
		if (socket == null || socket.isClosed()) {
			socket = ListenerUDP.getSocket();
		}
		if (socket == null) {
			// Server socket not yet bound (e.g. during unit tests or very early
			// startup). Drop the packet silently rather than NPE-ing.
			return;
		}
		try {
			DatagramPacket dp[] = packet.getDatagramPackets();
			for (int i = 0; i < dp.length; i++) {
				// Send UDP responses WITHOUT encryption.
				// NCE 2.5 clients accept unencrypted server packets during the
				// 3-way handshake and subsequent world-entry stream — the
				// retail server obfuscates its outgoing traffic with a stream
				// cipher keyed on the session's global seed (see
				// docs/PROTOCOL.md for the analysis), but the client happily
				// consumes plaintext as well.
				dp[i].setAddress(clientaddress);
				dp[i].setPort(clientport);
				socket.send(dp[i]);
			}
		} catch (IOException e) {
			Out.writeln(Out.Error, "UDP send failed for "
				+ (player != null && player.getAccount() != null ? player.getAccount().getUsername() : "?")
				+ " → " + clientaddress + ":" + clientport + " : " + e.getMessage());
		} catch (NullPointerException e) {
			// Defensive: some getDatagramPackets() implementations can
			// produce an empty array with a null entry. Drop silently.
		}
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