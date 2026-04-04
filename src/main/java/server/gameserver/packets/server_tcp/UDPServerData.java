package server.gameserver.packets.server_tcp;

import java.net.Inet4Address;
import java.net.UnknownHostException;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderTCP;

public class UDPServerData extends PacketBuilderTCP {

	public UDPServerData(Player pl) {
		write(0x83);//packetid
		write(0x05);
		writeInt(pl.getAccount().getId());
		writeInt(pl.getCharacter().getMisc(PlayerCharacter.MISC_ID));

		String ip = pl.getServerIP();
		byte[] serverip = null;
		try {
			serverip = Inet4Address.getByName(ip).getAddress();
		} catch (UnknownHostException e) {}

		write(serverip);
		writeShort(5000); //port
		writeInt(0x0000FFFF); // protocol flags (must match real server behavior)
		byte[] sid = pl.getSessionID();
		write(127-sid[0]);//session id for udp
		write(127-sid[1]);
		write(127-sid[2]);
		write(127-sid[3]);
		write(127-sid[4]);
		write(127-sid[5]);
		write(127-sid[6]);
		write(127-sid[7]);
	}
}