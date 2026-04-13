package server.gameserver;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import server.gameserver.packets.GamePacketReaderUDP;
import server.networktools.WireEncrypt;
import server.tools.Out;

/**
 * Dedicated UDP listener attached to a single {@link Player}.
 *
 * <p>NC2 retail allocates a unique server UDP port per session and uses it as
 * the session identifier — the client sends the port back from
 * {@code UDPServerData} and every subsequent game UDP packet lands on it.
 * This class mirrors that design: each logged-in player gets its own
 * {@link DatagramSocket} bound to a port from {@link UdpPortPool}, and a
 * dedicated receive loop that decrypts and dispatches packets directly to
 * the owning player.
 *
 * <p>Because every packet that arrives on this socket is unambiguously for
 * the associated player, there is no need for session-ID lookup or source-IP
 * heuristics. On the first packet (or on a zone-handoff reconnect from a
 * new client ephemeral port), the listener lazily creates or refreshes the
 * player's {@link GameServerUDPConnection} so outgoing sends can find the
 * current {@code (address, port)} pair.
 */
public final class PlayerUdpListener extends Thread {

	private final DatagramSocket socket;
	private final int port;
	private final Player player;
	private volatile boolean running;

	public PlayerUdpListener(int port, Player pl) throws SocketException {
		this.port = port;
		this.player = pl;
		this.socket = new DatagramSocket(port);
		this.socket.setSoTimeout(200);
		setName("PlayerUdpListener-" + port);
		setDaemon(true);
	}

	public DatagramSocket getSocket() {
		return socket;
	}

	public int getPort() {
		return port;
	}

	public Player getPlayer() {
		return player;
	}

	public boolean isRunning() {
		return running;
	}

	@Override
	public void run() {
		running = true;
		String userLabel = player.getAccount() != null ? player.getAccount().getUsername() : "?";
		Out.writeln(Out.Info, "PlayerUdpListener: started on port " + port + " for '" + userLabel + "'");
		while (GameServer.keeprunning && running) {
			try {
				DatagramPacket dp = new DatagramPacket(new byte[1500], 1500);
				socket.receive(dp);

				// Decrypt the wire-encrypted UDP datagram. NC2 retail
				// encrypts BOTH directions with the same LFSR CFB cipher
				// (FUN_00560090 = encrypt/sendto, FUN_0055ff30 =
				// decrypt/recvfrom). See WireEncrypt and
				// docs/PROTOCOL.md "UDP Wire Encryption".
				byte[] decrypted = WireEncrypt.decrypt(dp.getData(), 0, dp.getLength());
				if (decrypted == null || decrypted.length == 0) {
					Out.writeln(Out.Info, "UDP[" + port + "] drop malformed " + dp.getLength()
						+ "B from " + dp.getAddress().getHostAddress() + ":" + dp.getPort());
					continue;
				}
				dp.setData(decrypted, 0, decrypted.length);

				Out.writeln(Out.Info, "UDP[" + port + "] received " + decrypted.length + " bytes from "
					+ dp.getAddress().getHostAddress() + ":" + dp.getPort()
					+ " header=0x" + String.format("%02x", decrypted[0] & 0xFF));

				handle(dp);
			} catch (SocketTimeoutException e) {
				// normal — tight loop so we can observe keeprunning
			} catch (IOException e) {
				if (running) {
					Out.writeln(Out.Error, "PlayerUdpListener[" + port + "]: receive error: " + e.getMessage());
				}
				break;
			} catch (Exception e) {
				Out.writeln(Out.Error, "PlayerUdpListener[" + port + "]: unhandled: " + e.getMessage());
			}
		}
		closeSocketQuietly();
		Out.writeln(Out.Info, "PlayerUdpListener: stopped port " + port);
	}

	private void handle(DatagramPacket dp) {
		// Refresh or create the outgoing connection when the (address, port)
		// we see on the wire differs from what we last stored. The
		// zone-handoff case (client closes login UDP socket and reopens
		// from a fresh ephemeral port) must UPDATE the existing connection
		// in-place so the session counter and sessionkey survive — the
		// client validates incoming packets against the pre-handoff
		// counter, and a reset triggers silent drops + eventual timeout.
		GameServerUDPConnection con = player.getUdpConnection();
		if (con == null) {
			con = new GameServerUDPConnection(dp.getAddress(), dp.getPort(), player);
			player.setUdpConnection(con);
			Out.writeln(Out.Info, "PlayerUdpListener[" + port + "]: bound client "
				+ dp.getAddress().getHostAddress() + ":" + dp.getPort()
				+ " (" + (player.getAccount() != null ? player.getAccount().getUsername() : "?") + ")");
		} else if (!con.getAddress().equals(dp.getAddress()) || con.getPort() != dp.getPort()) {
			int oldPort = con.getPort();
			con.rebindClient(dp.getAddress(), dp.getPort());
			Out.writeln(Out.Info, "PlayerUdpListener[" + port + "]: zone handoff for "
				+ (player.getAccount() != null ? player.getAccount().getUsername() : "?")
				+ " client port " + oldPort + " -> " + dp.getPort()
				+ " (counter/sessionkey preserved)");
		}
		GamePacketReaderUDP.readPacket(dp, player);
	}

	public void shutdown() {
		running = false;
		closeSocketQuietly();
	}

	private void closeSocketQuietly() {
		if (socket != null && !socket.isClosed()) {
			socket.close();
		}
	}
}
