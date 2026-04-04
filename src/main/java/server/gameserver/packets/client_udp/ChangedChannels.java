package server.gameserver.packets.client_udp;

import server.gameserver.ChatManager;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

// is sent by client when the player changes the custom channels
// he's listening to
public class ChangedChannels extends GamePacketDecoderUDP{

	public ChangedChannels(byte[] subpacket){	
		super(subpacket);
	}
	
	public void execute(Player pl) {
		int channels = 0;
		
		skip(7);		
		channels = readInt();		
		
		ChatManager.changedListening(pl, channels);
		
		return;
	}
}
