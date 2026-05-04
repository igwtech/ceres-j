package server.gameserver.packets.client_tcp;

import server.database.accounts.Account;
import server.database.accounts.AccountManager;
import java.net.SocketException;

import server.gameserver.GameServerTCPConnection;
import server.gameserver.Player;
import server.gameserver.PlayerManager;
import server.gameserver.PlayerUdpListener;
import server.gameserver.UdpPortPool;
import server.gameserver.packets.GamePacketDecoderTCP;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_tcp.Packet830D;
import server.gameserver.packets.server_tcp.RequestFailed;
import server.gameserver.packets.server_tcp.SessionReady;
import server.gameserver.packets.server_tcp.UDPServerData;
import server.tools.Out;

public class AuthB extends GamePacketDecoderTCP {

	public AuthB(byte[] arg0) {
		super(arg0);
	}

	public void execute(GameServerTCPConnection tcp) {
		try {
			// Hex dump for protocol analysis
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < count; i++) {
				hex.append(String.format("%02x ", buf[i] & 0xFF));
				if ((i + 1) % 16 == 0) hex.append("\n                ");
			}
			Out.writeln(Out.Info, "AuthB packet (" + count + " bytes): " + hex.toString().trim());

			skip(2); // packet id
			skip(4); // unknown
			skip(4); // client port
			int encryptionKey = read();
			skip(7); // unknown
			int spot = readInt();

			int passwordLength = readShort() / 2;
			int usernameLength = readShort();

			Out.writeln(Out.Info, "AuthB: key=0x" + Integer.toHexString(encryptionKey)
				+ " spot=" + spot
				+ " usernameLen=" + usernameLength
				+ " passwordLen=" + passwordLength
				+ " pos=" + pos
				+ " bufSize=" + count);

			if (usernameLength <= 0 || usernameLength > 256) {
				Out.writeln(Out.Error, "AuthB: invalid username length: " + usernameLength);
				tcp.send(new RequestFailed("ERROR"));
				return;
			}

			if (passwordLength < 0 || passwordLength > 256) {
				Out.writeln(Out.Error, "AuthB: invalid password length: " + passwordLength);
				tcp.send(new RequestFailed("ERROR"));
				return;
			}

			String username = readCString(usernameLength);
			String password = readEncryptedString(passwordLength, encryptionKey);

			Out.writeln(Out.Info, "AuthB: user='" + username + "' spot=" + spot);

			AccountManager.AuthResult ar = AccountManager.authenticate(username, password);
			Account ua = ar.account;
			if (ar.outcome == AccountManager.AuthOutcome.OK) {
				tcp.setAccount(ua);
				tcp.activatePlayer(spot);
				// Use the already-activated player — do NOT call findPlayer again
				// as that would create a duplicate with a different session ID
				Player pl = PlayerManager.findPlayer(ua);
				if (pl != null) {
					byte[] sid = pl.getSessionID();
					Out.writeln(Out.Info, "AuthB: player activated, sessionID="
						+ String.format("%02x %02x %02x %02x %02x %02x %02x %02x",
							sid[0], sid[1], sid[2], sid[3], sid[4], sid[5], sid[6], sid[7])
						+ " (transformed: "
						+ String.format("%02x %02x %02x %02x %02x %02x %02x %02x",
							127-sid[0], 127-sid[1], 127-sid[2], 127-sid[3],
							127-sid[4], 127-sid[5], 127-sid[6], 127-sid[7]) + ")");

					// Allocate a dedicated UDP port for this session BEFORE
					// the GetUDPConnection handler builds UDPServerData so
					// the client is told the right port to connect to.
					if (pl.getUdpListener() == null) {
						Integer assigned = UdpPortPool.allocate();
						if (assigned == null) {
							Out.writeln(Out.Error, "AuthB: UDP port pool exhausted, falling back to shared port 5000");
						} else {
							try {
								PlayerUdpListener listener = new PlayerUdpListener(assigned, pl);
								listener.start();
								pl.setUdpListener(listener);
								Out.writeln(Out.Info, "AuthB: allocated UDP port " + assigned + " for '" + username + "'");
							} catch (SocketException e) {
								Out.writeln(Out.Error, "AuthB: failed to bind UDP port " + assigned + ": " + e.getMessage() + " (falling back to shared 5000)");
								UdpPortPool.release(assigned);
							}
						}
					}

					// Retail post-AuthB sequence (verified TCP capture
					// of Drstone connecting to Plaza Sec-1 via port 5008,
					// 2026-05-01):
					//   SessionReady (0xa0 0x01) →
					//   UDPServerData (0x83 0x05) →
					//   GameinfoReady (0x83 0x0d) →
					//   Location (0x83 0x0c, with zone BSP name) →
					//   ... then 0x83 0x8f keepalives ...
					// The modern NCE 2.5.x client does NOT request these
					// via GetGamedata — the server pushes them proactively.
					// Without this push the client times out with
					// "connect to nethost failed" while waiting for UDP
					// handover info.
					tcp.send(new SessionReady());
					tcp.send(new UDPServerData(pl));
					tcp.send(new Packet830D());
					tcp.send(new Location(pl));
				} else {
					Out.writeln(Out.Error, "AuthB: player not found after activation");
					tcp.send(new RequestFailed("ERROR"));
				}
			} else {
				String reason;
				switch (ar.outcome) {
				case NOT_FOUND:    reason = "user not found"; break;
				case BAD_PASSWORD: reason = "wrong password"; break;
				case BANNED:       reason = "account banned"; break;
				default:           reason = ar.outcome.toString();
				}
				Out.writeln(Out.Error,
					"AuthB: authentication failed for '" + username
					+ "': " + reason);
				tcp.send(new RequestFailed("ERROR"));
			}
		} catch (Exception e) {
			Out.writeln(Out.Error, "AuthB: failed: " + e.getMessage());
			e.printStackTrace();
			tcp.send(new RequestFailed("ERROR"));
		}
	}
}
