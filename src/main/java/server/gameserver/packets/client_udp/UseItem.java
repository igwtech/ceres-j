package server.gameserver.packets.client_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_tcp.Packet838F;
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

		// Retail interaction-commit ack. Must arrive BEFORE any
		// state-change packets (OpenDoor, animation broadcasts,
		// vendor/trade window open, ...). Body invariant
		// `83 8f 00 00 00 00`. See Packet838F javadoc.
		pl.send(new Packet838F());

		String text = new String();
		text += "UnknownItem ID: " + id + " at pos: y:" +
		Float.floatToIntBits((float)pl.getCharacter().getMisc(PlayerCharacter.MISC_Y_COORDINATE)) +
		" z:" +
		Float.floatToIntBits((float)pl.getCharacter().getMisc(PlayerCharacter.MISC_Z_COORDINATE)) +
		" x:" +
		Float.floatToIntBits((float)pl.getCharacter().getMisc(PlayerCharacter.MISC_X_COORDINATE));
		Out.writeln(Out.Info, text);
		pl.send(new LocalChatMessage(pl, text));

		pl.send(new OpenDoor(id, pl));
	}

}
