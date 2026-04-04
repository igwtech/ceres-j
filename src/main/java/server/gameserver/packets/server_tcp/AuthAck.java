package server.gameserver.packets.server_tcp;

import server.database.accounts.Account;
import server.networktools.PacketBuilderTCP;

public class AuthAck extends PacketBuilderTCP {

	public AuthAck(Account ua) {
		super();
		write(0x83); //packetid
		write(0x81);
		writeInt(ua.getId());
		writeInt(0); // unknown
		writeShort(0); // passwordlength (if we send it back, but why should we?)
		write(0);
	}
}
