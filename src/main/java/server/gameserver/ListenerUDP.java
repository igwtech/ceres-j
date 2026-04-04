package server.gameserver;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.LinkedList;
import java.util.ListIterator;

import server.gameserver.packets.GamePacketReaderUDP;
import server.gameserver.packets.server_udp.UDPAlive;
import server.networktools.PacketObfuscator;
import server.tools.Out;

public final class ListenerUDP extends Thread {

	public boolean running;
	private static LinkedList<GameServerUDPConnection> connectionsList = new LinkedList<GameServerUDPConnection>();
	private static DatagramSocket serverSocket;

	public ListenerUDP(int port) throws SocketException {
		serverSocket = new DatagramSocket(port);
		serverSocket.setSoTimeout(100);
	}
	
	public void run() {
		Out.writeln(Out.Info, "Game Server Listening (UDP) on port " + serverSocket.getLocalPort());
		running = true;
		while (GameServer.keeprunning) {
			try {
				DatagramPacket dp = new DatagramPacket(new byte[1500], 1500);
				serverSocket.receive(dp);
				// Decrypt the UDP packet
				byte[] decrypted = PacketObfuscator.decrypt(dp.getData(), dp.getLength());
				if (decrypted != null) {
					dp.setData(decrypted, 0, decrypted.length);
				}
				Out.writeln(Out.Info, "UDP received " + dp.getLength() + " bytes from " + dp.getAddress().getHostAddress() + ":" + dp.getPort()
					+ " header=0x" + String.format("%02x", dp.getData()[0] & 0xFF));
				giveUDPConnection(dp);
			} catch (IOException e) {}
		}
		synchronized (this) {
			running = false;
			notifyAll();
		}
	}

	private void giveUDPConnection(DatagramPacket dp) {
		GameServerUDPConnection con = null;
		synchronized (connectionsList) {
			ListIterator<GameServerUDPConnection> it = connectionsList.listIterator();
			while (it.hasNext()) {
				GameServerUDPConnection temp_con = it.next();
				if (!temp_con.getAddress().equals(dp.getAddress())) continue;
				if (temp_con.getPort() != dp.getPort()) continue;
				con = temp_con;
				break;
			}
			if (con == null) {
				byte[] data = dp.getData();
				int len = dp.getLength();
				StringBuilder pktHex = new StringBuilder();
				for (int j = 0; j < Math.min(len, 20); j++) pktHex.append(String.format("%02x ", data[j] & 0xFF));
				Out.writeln(Out.Info, "UDP new connection (" + len + " bytes) from "
					+ dp.getAddress().getHostAddress() + ":" + dp.getPort()
					+ " data: " + pktHex.toString().trim());

				// Try to find session ID in the packet.
				// The client sends the transformed session ID (127 - originalSid[i]).
				// Try matching from different offsets and with/without transformation.
				Player pl = null;
				int sidOffset = -1;

				// Strategy 1: original format — header byte 0x01, session at offset 1
				if (data[0] == 1 && len >= 9) {
					byte[] sid = new byte[8];
					System.arraycopy(data, 1, sid, 0, 8);
					pl = PlayerManager.getPlayer(sid);
					if (pl != null) sidOffset = 1;
				}

				// Strategy 2: session ID at offset 0 (no header byte)
				if (pl == null && len >= 8) {
					byte[] sid = new byte[8];
					System.arraycopy(data, 0, sid, 0, 8);
					pl = PlayerManager.getPlayer(sid);
					if (pl != null) sidOffset = 0;
				}

				// Strategy 3: transformed session ID (127 - x) at offset 0
				if (pl == null && len >= 8) {
					byte[] sid = new byte[8];
					for (int j = 0; j < 8; j++) sid[j] = (byte)(127 - data[j]);
					pl = PlayerManager.getPlayer(sid);
					if (pl != null) {
						sidOffset = 0;
						Out.writeln(Out.Info, "UDP matched via transformed session ID");
					}
				}

				// Strategy 4: transformed session ID at offset 1
				if (pl == null && len >= 9) {
					byte[] sid = new byte[8];
					for (int j = 0; j < 8; j++) sid[j] = (byte)(127 - data[j + 1]);
					pl = PlayerManager.getPlayer(sid);
					if (pl != null) {
						sidOffset = 1;
						Out.writeln(Out.Info, "UDP matched via transformed session ID at offset 1");
					}
				}

				// Strategy 5: match by source IP — find any player expecting UDP
				// The modern NC2 client encrypts UDP handshake packets, so session ID
				// matching doesn't work. Fall back to IP-based matching.
				if (pl == null) {
					pl = PlayerManager.getPlayerAwaitingUDP();
					if (pl != null) {
						sidOffset = -1;
						Out.writeln(Out.Info, "UDP: matched player '" + pl.getAccount().getUsername() + "' by pending UDP (no session match)");
					}
				}

				if (pl == null) {
					Out.writeln(Out.Info, "UDP: no player matched");
					return;
				}

				if (sidOffset >= 0) {
					Out.writeln(Out.Info, "UDP: player matched '" + pl.getAccount().getUsername() + "' (offset=" + sidOffset + ")");
				}

				{
					con = new GameServerUDPConnection(dp.getAddress(), dp.getPort(), pl);
					pl.setUdpConnection(con);
					connectionsList.add(con);
					// Send UDPAlive to acknowledge the connection
					try {
						con.send(new UDPAlive(pl));
						Out.writeln(Out.Info, "UDP: sent UDPAlive to " + dp.getAddress().getHostAddress() + ":" + dp.getPort());
					} catch (Exception e) {
						Out.writeln(Out.Error, "UDP: failed to send UDPAlive: " + e.getMessage());
					}
				}
			}
		}
		if (con == null || con.getPlayer() == null) return;
		GamePacketReaderUDP.readPacket(dp, con.getPlayer());
	}

	public static DatagramSocket getSocket() {
		return serverSocket;
	}

	public static void close(GameServerUDPConnection connection) {
		synchronized (connectionsList) {
			connectionsList.remove(connection);
		}
	}
}
