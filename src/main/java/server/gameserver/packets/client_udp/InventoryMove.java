package server.gameserver.packets.client_udp;

import server.database.items.ItemContainer;
import server.database.items.ItemManager;

import server.gameserver.Player;

import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.InventoryMoveAck;
import server.gameserver.packets.server_udp.InventoryMoveDenied;
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

		// The src-read / dst-add position encodings are derived
		// per-container inside ItemManager.moveItem (F2 = XY-packed, QB =
		// flat slot). The old single dst-derived flag here broke F2→QB
		// moves; the flags arg is now legacy/unused. TODO(P5): container
		// id space refactor so src/dst types are unambiguous from the id.
		if (ItemManager.moveItem(srccont, srcpos, dstcont, dstpos, 0)) {
			pl.send(new InventoryMoveAck(pl, isrccont, srcpos, idstcont, dstpos));
			// NOTE: previously this also fired two TCP InteractionAcks.
			// No retail evidence shows a double (or any) TCP
			// InteractionAck for an inventory drag, so the speculative
			// double-send was removed (P6). The 0x25/0x1e UDP echo above
			// is the move confirmation.
		} else {
			// Move rejected — tell the client so its optimistic UI
			// reverts instead of staying desynced (P4). Mirrors how
			// InsideF2InvMove denies an illegal same-container move.
			Out.writeln(Out.Info, "InventoryMove not allowed — denying.");
			pl.send(new InventoryMoveDenied(pl, isrccont, srcpos, idstcont, dstpos));
		}
	}

}
