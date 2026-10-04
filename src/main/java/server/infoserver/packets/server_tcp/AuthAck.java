package server.infoserver.packets.server_tcp;

import server.database.accounts.Account;
import server.networktools.PacketBuilderTCP;

/**
 * InfoServer AuthAck (0x83 0x81). Same 31-byte retail layout as the
 * gameserver {@link server.gameserver.packets.server_tcp.AuthAck}: the
 * client's password ciphertext is echoed back as session_data. The old
 * body sent length 0 and no data; the client then sent its stage-1
 * GetCharList (0x84 0x82) as 12 bytes instead of retail's 30.
 */
public class AuthAck extends PacketBuilderTCP {

	public AuthAck(Account ua, byte[] passwordCipher) {
		super();
		write(0x83); //packetid
		write(0x81);
		writeInt(ua.getId());
		writeInt(0); // reserved
		writeShort(passwordCipher.length);
		write(passwordCipher);
		write(0); // trailer
	}
}
