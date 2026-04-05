package server.gameserver.packets.server_tcp;

import java.net.Inet4Address;
import java.net.UnknownHostException;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderTCP;
import server.networktools.ProtocolConstants;

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
		} catch (UnknownHostException e) {
			// Failed to resolve server IP; serverip remains null
		}

		write(serverip);
		// Per-session UDP port allocated for this player (see AuthB +
		// PlayerUdpListener). Falls back to 5000 if the pool was exhausted.
		writeShort(pl.getUdpPort());
		// Retail pcap shows this 4-byte field as 0x00890000 (wire: 00 00 89 00).
		// Our old hardcoded 0x0000FFFF (wire: ff ff 00 00) has the non-zero
		// bytes in the OPPOSITE positions, which is almost certainly a
		// version/feature flag mismatch the client rejects silently.
		writeInt(ProtocolConstants.UDP_SERVER_DATA_FLAGS);
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