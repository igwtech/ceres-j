package server.gameserver.packets.server_tcp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.networktools.PacketBuilderTCP;

public class Location extends PacketBuilderTCP {

	public Location(Player pl) {
		int location = pl.getCharacter().getMisc(PlayerCharacter.MISC_LOCATION);
		write(0x83);
		write(0x0c);
		writeInt(location);
		if(location == 9999)
			writeInt(1); //unknown
		else
			writeInt(0);
		// Retail pcap (pepper_p3) shows a third 4-byte zero field before the
		// zone name string. Without it the client reads the first 4 bytes of
		// the name as a dummy field and the zone path is truncated by 4
		// characters, producing a world-load failure like
		// "Worldfile opening failed: .\worlds\a/plaza_p1.bsp".
		writeInt(0);
		if(location == 9999)
			write(new String("apps/clean/plaza_app_4_c").getBytes());
		else
			write(pl.getZone().getWorldname().getBytes());
//		write(new String("theroom").getBytes());
		write(0); //CStyle
	}

}
