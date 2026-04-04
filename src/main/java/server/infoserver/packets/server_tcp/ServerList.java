package server.infoserver.packets.server_tcp;

import java.net.Inet4Address;
import java.net.UnknownHostException;

import server.infoserver.InfoServerConnection;
import server.networktools.PacketBuilderTCP;
import server.tools.Config;

public class ServerList extends PacketBuilderTCP {

	public ServerList(InfoServerConnection isc) {
		String ip = isc.getServerIP();		
		byte[] serverip = new byte[4];
		try {
			serverip = Inet4Address.getByName(ip).getAddress();
		} catch (UnknownHostException e) {
			// Failed to resolve server IP; using default empty address
		}
		byte[] serverName = Config.getProperty("ServerName").getBytes();

		write(0x83);// packet id
		write(0x83);
		writeShort(1);// number of servers
		writeShort(0xe);// size of serverstructure
		
		//per server:
		write(serverip);
		writeInt(12000);//port number
		write(serverName.length+1);
		write(Integer.parseInt(Config.getProperty("CharsPerAccount")));
		writeShort(99); //how many players are online in % TODO
		writeShort(127); //unknown
		write(serverName);
		write(0); //Cstyle
	}
}
