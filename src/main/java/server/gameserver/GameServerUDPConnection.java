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
	/** Per-session ring of recently-emitted reliable {@code 0x03}
	 *  sub-packets, keyed by their LE16 sequence counter. Used by
	 *  the {@link server.gameserver.packets.client_udp.ReliableAckSubPacket}
	 *  handler to satisfy {@code C→S 0x01 [seq LE2]} retransmit
	 *  requests. Without this the client's "Synchronizing" overlay
	 *  never clears (task #136 / #151). */
	private final server.networktools.ReliablePacketRing reliableRing
			= new server.networktools.ReliablePacketRing();

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

	/**
	 * Rebind the client-side endpoint without resetting the session state
	 * (counter, sessionkey). Used when the NC2 client performs a zone
	 * handoff: it closes its login UDP socket and opens a fresh one from a
	 * new ephemeral port. The session port on the server stays the same,
	 * so packets arriving from the new (addr, port) belong to the same
	 * logical session and the counter / sessionkey MUST NOT reset — the
	 * client validates inbound packets against the counter it saw before
	 * the reconnect and silently drops mismatches, which manifests as
	 * "Receive 0 Buffer" polling and eventual "Connection to worldserver
	 * failed" timeout.
	 */
	public void rebindClient(InetAddress address, int port) {
		this.clientaddress = address;
		this.clientport = port;
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
				// Encrypt each outgoing UDP datagram with the per-packet
				// LFSR cipher. The wire format prepends a 4-byte header:
				// [seed_lo][seed_hi][enc_len_lo][enc_len_hi][enc_data...]
				// See docs/PROTOCOL.md "UDP Wire Encryption" for details.
				byte[] plain = dp[i].getData();
				int plainLen = dp[i].getLength();
				byte[] wire = server.networktools.WireEncrypt.encrypt(plain, 0, plainLen);
				dp[i] = new DatagramPacket(wire, wire.length);
				dp[i].setAddress(clientaddress);
				dp[i].setPort(clientport);
				socket.send(dp[i]);
			}
			// Trace every outgoing UDP datagram so we can correlate the
			// server send path with the client's receive log. Format is
			// "UDP send src=<local>:<lport> dst=<client>:<cport> len=<n>
			// first=<hex> for=<user>" so a grep against docker logs makes it
			// obvious if packets are going to the wrong destination or
			// from the wrong source port.
			if (dp.length > 0) {
				int len = dp[0].getLength();
				String first = len > 0 ? String.format("%02x", dp[0].getData()[0] & 0xFF) : "?";
				Out.writeln(Out.Info, "UDP send src=" + socket.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort()
					+ " dst=" + clientaddress.getHostAddress() + ":" + clientport
					+ " count=" + dp.length + " firstlen=" + len + " firstbyte=0x" + first
					+ " for=" + (player != null && player.getAccount() != null ? player.getAccount().getUsername() : "?"));
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

	/**
	 * Reset the reliable-channel state for a zone-cross, matching
	 * the retail wire behaviour decoded 2026-05-14 from
	 * {@code RETAIL_PLAZA_CROSSZONE}: after the client sends
	 * Zoning2 and the server replies with Location + UDPAlive, the
	 * server's next UDP packet uses a freshly-reset 0x13 wrapper
	 * (counter back to 0, brand-new session key) and the client
	 * mirrors that on its side. Without this the reliable layer
	 * desyncs across the cross and the client hangs on the
	 * "Synchronizing" overlay.
	 *
	 * <p>Call order matters: invoke this <em>before</em>
	 * constructing the {@link
	 * server.gameserver.packets.server_udp.UDPAlive} that announces
	 * the cross, so the UDPAlive carries the NEW session key (its
	 * {@code -sessionkey} field is what the client adopts).
	 *
	 * @return the regenerated 16-bit session key (for logging)
	 */
	public synchronized short resetSessionForZoneCross() {
		udpSessionCounter = 0;
		udp13Sessionkey = (short) new Random().nextInt();
		reliableRing.clear();
		handshakingState = false;
		return udp13Sessionkey;
	}

	/** Per-session ring of recently-emitted reliable {@code 0x03}
	 *  sub-packets — see field javadoc. Public for the
	 *  PacketBuilderUDP1303 emit hook + the ReliableAckSubPacket
	 *  retransmit responder. */
	public server.networktools.ReliablePacketRing reliableRing() {
		return reliableRing;
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