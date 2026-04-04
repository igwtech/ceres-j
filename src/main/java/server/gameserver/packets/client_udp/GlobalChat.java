package server.gameserver.packets.client_udp;

import server.gameserver.ChatManager;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

public class GlobalChat extends GamePacketDecoderUDP{
	
	public GlobalChat(byte[] subpacket){	
		super(subpacket);
	}
	
	public void execute(Player pl) {
		int chan = 0;
		String message = new String();
		
		skip(7);		
		chan = readShort();		
		message += readCString(13);
		
		ChatManager.NewMessage(pl, message, chan);
		
		return;
	}

}
