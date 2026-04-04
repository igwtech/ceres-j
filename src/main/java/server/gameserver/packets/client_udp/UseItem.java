package server.gameserver.packets.client_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.LocalChatMessage;
import server.gameserver.packets.server_udp.OpenDoor;
import server.tools.Out;

public class UseItem extends GamePacketDecoderUDP {

	public UseItem(byte[] subPacket) {
		super(subPacket);
	}

	public void execute(Player pl) {
		skip(7);
		int id = readInt();
//TODO remove debug and add functions
		
		String text = new String();
		text += "UnknownItem ID: " + id + " at pos: y:" +
		Float.floatToIntBits((float)pl.getCharacter().getMisc(PlayerCharacter.MISC_Y_COORDINATE)) +
		" z:" +
		Float.floatToIntBits((float)pl.getCharacter().getMisc(PlayerCharacter.MISC_Z_COORDINATE)) +
		" x:" +
		Float.floatToIntBits((float)pl.getCharacter().getMisc(PlayerCharacter.MISC_X_COORDINATE));
		Out.writeln(Out.Info, text);
		pl.send(new LocalChatMessage(pl, text));
		
		/*Out.writeln(Out.Debug, "use item " + id);
		Out.writeln(Out.Debug, "Y" + pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
		Out.writeln(Out.Debug, "Z" + pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
		Out.writeln(Out.Debug, "X" + pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));*/
		pl.send(new OpenDoor(id, pl));
	}

}
