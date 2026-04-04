package server.gameserver.packets.client_tcp;

import server.database.accounts.Account;
import server.database.accounts.AccountManager;
import server.gameserver.GameServerTCPConnection;
import server.gameserver.packets.GamePacketDecoderTCP;
import server.gameserver.packets.server_tcp.AuthAck;
import server.gameserver.packets.server_tcp.RequestFailed;
import server.tools.Out;

public final class Auth extends GamePacketDecoderTCP {

	public Auth(byte[] arg0) {
		super(arg0);
	}

	public void execute(GameServerTCPConnection tcp) {
		try {
			// Hex dump the entire packet for protocol analysis
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < count; i++) {
				hex.append(String.format("%02x ", buf[i] & 0xFF));
				if ((i + 1) % 16 == 0) hex.append("\n                ");
			}
			Out.writeln(Out.Info, "Auth packet (" + count + " bytes): " + hex.toString().trim());

			skip(2); // packet id (0x84 0x80)
			int encryptionKey = read();
			skip(30); // unknown (30 bytes in modern NC2 client, was 18 in older versions)

			int usernameLength = readShort();
			int passwordLength = readShort() / 2;

			Out.writeln(Out.Info, "Auth: key=0x" + Integer.toHexString(encryptionKey)
				+ " usernameLen=" + usernameLength
				+ " passwordLen=" + passwordLength
				+ " bufSize=" + count
				+ " pos=" + pos);

			if (usernameLength <= 0 || usernameLength > 256) {
				Out.writeln(Out.Error, "Auth: invalid username length: " + usernameLength);
				tcp.send(new RequestFailed("ERROR"));
				return;
			}

			if (passwordLength < 0 || passwordLength > 256) {
				Out.writeln(Out.Error, "Auth: invalid password length: " + passwordLength);
				tcp.send(new RequestFailed("ERROR"));
				return;
			}

			String username = readCString(usernameLength);
			String password = readEncryptedString(passwordLength, encryptionKey);

			Out.writeln(Out.Info, "Auth: user='" + username + "' (password length " + password.length() + ")");

			Account ua = AccountManager.getAccount(username, password);
			tcp.setAccount(ua);
			if (ua != null) {
				tcp.send(new AuthAck(ua));
			} else {
				tcp.send(new RequestFailed("ERROR"));
			}
		} catch (Exception e) {
			Out.writeln(Out.Error, "Auth: packet parsing failed: " + e.getMessage());
			tcp.send(new RequestFailed("ERROR"));
		}
	}

}
//0x00		short		packet id
//0x02		byte		encryption key ;)

//18 bytes unknown

//0x15		short		username length (including c-style ending-0)
//0x17		short		password length (encoded)
//0x19		c-string	username
//+0x00		short[]		encoded password

//1 byte unknown
