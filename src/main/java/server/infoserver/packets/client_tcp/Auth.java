package server.infoserver.packets.client_tcp;

import server.database.accounts.Account;
import server.database.accounts.AccountManager;
import server.infoserver.InfoServerConnection;
import server.infoserver.packets.InfoPacketDecoderTCP;
import server.infoserver.packets.server_tcp.AuthAck;
import server.infoserver.packets.server_tcp.RequestFailed;

public final class Auth extends InfoPacketDecoderTCP {

	public Auth(byte[] arg0) {
		super(arg0);
	}

	public void execute(InfoServerConnection isc) {
		int packetId = readShort();
		int encryptionKey = read();
		skip(30);

		int usernameLength = readShort();
		int passwordLength = readShort() /2;
		
		String username = readCString(usernameLength);
		String password = readEncryptedString(passwordLength, encryptionKey);
		
		Account ua = AccountManager.getAccount(username, password);
		isc.setAccount(ua);
		if (ua != null) {
			isc.send(new AuthAck(ua));
		} else {
			isc.send(new RequestFailed("ERROR"));
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
