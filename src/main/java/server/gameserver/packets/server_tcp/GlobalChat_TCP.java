package server.gameserver.packets.server_tcp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderTCP;

public class GlobalChat_TCP extends PacketBuilderTCP{
	
	public GlobalChat_TCP(String message, Player sender, int channel){ // TODO move construction of the chat packets here
		super();
		String name = sender.getCharacter().getName();

		write((byte)0x83);
		write(0x17);
		write((byte)sender.getCharacter().getMisc(PlayerCharacter.MISC_ID));
		write((byte)sender.getCharacter().getMisc(PlayerCharacter.MISC_ID) >> 8 );
		write((byte)sender.getCharacter().getMisc(PlayerCharacter.MISC_ID) >> 16 );
		write((byte)sender.getCharacter().getMisc(PlayerCharacter.MISC_ID) >> 24 );
		write((byte)name.length());
		write((byte)channel);
		write((byte)(channel >> 8 )); // TODO: die Channel ab Handel NC funktionieren noch nicht ganz...
		write(name.getBytes());
		write(message.getBytes());
	}

}