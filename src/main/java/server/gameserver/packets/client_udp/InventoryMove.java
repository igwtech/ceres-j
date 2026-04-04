package server.gameserver.packets.client_udp;

import server.database.items.ItemContainer;
import server.database.items.ItemManager;

import server.gameserver.Player;

import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.InventoryMoveAck;
import server.tools.Out;

public class InventoryMove extends GamePacketDecoderUDP  {

	public InventoryMove(byte[] subPacket) {
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
		
		skip(7);
		
		isrccont	= read();
		srccont 	= pl.getCharacter().getContainer(isrccont);
		srcpos		= readShort();
		idstcont	= read();
		dstcont		= pl.getCharacter().getContainer(idstcont);
		dstpos		= readShort();
		
		if(dstcont == null || srccont ==  null){
			Out.writeln(Out.Info, "one of the containers not found!");
			return;
		}
		
		if(dstcont.getContainerType() == ItemContainer.CONTAINERTYPE_PLINVENTORY){
			flags = ItemContainer.FLAG_DSTPOS_XY;
		}
		
		if(ItemManager.moveItem(srccont, srcpos, dstcont, dstpos, flags))
			pl.send(new InventoryMoveAck(pl, isrccont, srcpos, idstcont, dstpos));
	}

}
