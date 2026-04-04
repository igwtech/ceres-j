package server.gameserver.packets.client_udp;

import server.database.items.ItemContainer;
import server.database.items.ItemManager;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.InventoryMoveDenied;
import server.tools.Out;

public class InventoryCombineItems extends GamePacketDecoderUDP  {

	public InventoryCombineItems(byte[] subPacket) {
		super(subPacket);
	}

	public void execute(Player pl) {
		int isrccont = 0;
		int idstcont = 0;
		int srcpos = 0;
		int dstpos = 0;
		int flags = 0;
		ItemContainer srccont = null;
		ItemContainer dstcont = null;
		
		skip(8);
		
		isrccont	= read();
		srcpos		= readShort();
		idstcont	= read();
		dstpos		= readShort();
		
		srccont = pl.getCharacter().getContainer(isrccont);
		dstcont = pl.getCharacter().getContainer(idstcont);
		
		if(dstcont == null || srccont ==  null){
			Out.writeln(Out.Info, "one of the containers not found!");
			return;
		}
		
		/*if(!ItemManager.moveItem(srccont, srcpos, dstcont, dstpos, flags)){
			Out.writeln(Out.Info, "ItemCombine not Allowed! Do something!");
			pl.send(new InventoryMoveDenied(pl, isrccont, srcpos, idstcont, dstpos));
		}*/ //TODO: MUCH!!!!!
		
		Out.writeln(Out.Info, "ItemCombine not Allowed!");
		pl.send(new InventoryMoveDenied(pl, isrccont, srcpos, idstcont, dstpos));
	}

}
