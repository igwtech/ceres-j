package server.gameserver.packets.client_udp;

import server.database.items.ItemContainer;
import server.database.items.ItemManager;
import server.database.playerCharacters.PlayerCharacter;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.InventoryMoveDenied;
import server.tools.Out;

public class InsideF2InvMove extends GamePacketDecoderUDP  {

	public InsideF2InvMove(byte[] subPacket) {
		super(subPacket);
	}

	public void execute(Player pl) {
		int srcpos = 0;
		int dstpos = 0;
		int flags = 0;
		ItemContainer srccont = null;
		ItemContainer dstcont = null;
		
		skip(8);
		
		srcpos		= readShort();
		dstpos		= readShort();
		
		srccont = pl.getCharacter().getContainer(PlayerCharacter.PLAYERCONTAINER_F2);
		dstcont = srccont;
		
		if(dstcont == null || srccont ==  null){
			Out.writeln(Out.Info, "one of the containers not found!");
			return;
		}
		
		flags = ItemContainer.FLAG_DSTPOS_XY;
		
		if(!ItemManager.moveItem(srccont, srcpos, dstcont, dstpos, flags)){
			Out.writeln(Out.Info, "ItemMove not Allowed! Do something!");
			pl.send(new InventoryMoveDenied(pl, PlayerCharacter.PLAYERCONTAINER_F2, srcpos, PlayerCharacter.PLAYERCONTAINER_F2, dstpos));
		}
	}

}
