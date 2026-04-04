package server.gameserver.packets.client_udp;

import server.database.items.*;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.LocalChatMessage;
import server.gameserver.packets.server_udp.ForcedZoning;
import server.gameserver.packets.server_udp.AddItem;
import server.tools.Out;

public class LocalChat extends GamePacketDecoderUDP{
	
	public LocalChat(byte[] subPacket) {
		super(subPacket);
	}
	
	public void execute(Player pl) {
		String message = new String();
		message += readCString(7);
		
		Out.writeln(Out.Info, "Message from user: "
				+ pl.getCharacter().getName() + " :"
				+ message);
		
		if(!checkforcommand(pl, message))
			pl.getZone().localChat(pl, message);
	}
	
	private boolean checkforcommand(Player pl, String message){
		if(message.substring(0,2).equals("./")){
	    	if(message.substring(2,5).equalsIgnoreCase("pos")){
	    		String text = new String();
	    		text += "y:" +
	    		(pl.getCharacter().getMisc(PlayerCharacter.MISC_Y_COORDINATE) + 32768) +
	    		"z:" +
	    		(pl.getCharacter().getMisc(PlayerCharacter.MISC_Z_COORDINATE) + 32768) +
	    		"x:" +
	    		(pl.getCharacter().getMisc(PlayerCharacter.MISC_X_COORDINATE) + 32768);
	    		Out.writeln(Out.Info, text);
	    		pl.send(new LocalChatMessage(pl, text, 2));
	    	}
	    	else if(message.length() >7)
	    	{
	    		if(message.substring(2,6).equalsIgnoreCase("zone")){
	    			//new Sync().send(session);
	    			try{
	    				int loc = 0;
	    				loc = Integer.parseInt(message.substring(7, message.length() - 1));
	    				if(loc != 0){
	    					pl.send(new ForcedZoning(pl, loc));
	    				}
	    			}
	    			catch(Exception e){
	    				// Invalid zone command arguments; ignored
	    			}
	    		}
	    		if(message.substring(2,5).equalsIgnoreCase("npc")){
	    			// this looks like: "./npc 3452;3345;22234;11;200;300;"
	    			try{
	    				int x, y, z, type, hp, armor = 0;
	    				int pos = 6;
	    				int pose = 0;

	    				pose = message.indexOf(";", pos);
	    				x = Integer.parseInt(message.substring(pos, pose));
	    				pos = pose + 1;
	    				pose = message.indexOf(";", pos);
	    				y = Integer.parseInt(message.substring(pos, pose));
	    				pos = pose + 1;
	    				pose = message.indexOf(";", pos);
	    				z = Integer.parseInt(message.substring(pos, pose));
	    				pos = pose + 1;
	    				pose = message.indexOf(";", pos);
	    				type = Integer.parseInt(message.substring(pos, pose));
	    				pos = pose + 1;
	    				pose = message.indexOf(";", pos);
	    				hp = Integer.parseInt(message.substring(pos, pose));
	    				pos = pose + 1;
	    				pose = message.indexOf(";", pos);
	    				armor = Integer.parseInt(message.substring(pos, pose));
	    				
	    				Out.writeln(Out.Info, "test" + x + " ; " + y + " ; "
	    						+ z + " ; "+ hp + " ; "+ type + " ; "+ armor + " ; ");

	    				pl.getZone().addNPCtoZone(x, y, z, hp, type, armor);
	    			}
	    			catch(Exception e){
	    				// Invalid NPC command arguments; ignored
	    			}
		    		// TODO: create new NPC
		    	}
	    		if(message.substring(2,6).equalsIgnoreCase("item")){
	    			// this looks like: "./npc 3452;3345;22234;11;200;300;"
	    			try{
	    				short ItemID, uses;
	    				int pos = 7;
	    				int pose = 0;
	    				
	    				pose = message.indexOf(";", pos);
	    				ItemID = Short.parseShort(message.substring(pos, pose));
	    				/*pos = pose + 1;
	    				pose = message.indexOf(";", pos);
	    				uses = Short.parseShort(message.substring(pos, pose));*/
	    				
	    				short[] tokens = new short[17];
	    				for(int i = 0; i < 17; i++){
	    					tokens[i] = 0;
	    				}
	    				
	    				tokens[Item.TOKENS_CURRCOND] 		= 255;
	    				tokens[Item.TOKENS_MAXCOND] 		= 255;
	    				tokens[Item.TOKENS_DMG] 			= 200;
	    				tokens[Item.TOKENS_FREQUENCY] 		= 200;
	    				tokens[Item.TOKENS_HANDLING] 		= 200;
	    				tokens[Item.TOKENS_RANGE] 			= 200;
	    				tokens[Item.TOKENS_AMMOUSES] 		= 3;
	    				tokens[Item.TOKENS_ITEMSONSTACK]	= 5;
	    				
	    				pl.send(new AddItem(pl, ItemManager.createItem(pl.getCharacter().getContainer(PlayerCharacter.PLAYERCONTAINER_F2), -1, ItemID, tokens, Item.ITEMFLAG_SIMPLE, 0)));
	    			}
	    			catch(Exception e){
	    				// Invalid item command arguments; ignored
	    			}
		    		// TODO: create new NPC
		    	}
	    	}
	    	return true;
	    }
		return false;
	}

	
}
