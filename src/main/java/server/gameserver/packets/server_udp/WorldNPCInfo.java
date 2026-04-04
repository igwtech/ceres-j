package server.gameserver.packets.server_udp;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

public class WorldNPCInfo extends PacketBuilderUDP1303{
	
	byte[] unknowndata = new byte[] {0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x53, 0x45, 0x4d, 0x50, 0x4c, 0x00, 0x31, 0x38, 0x30, 0x00};
	
	public WorldNPCInfo(Player pl, NPC npc) { // NPC missing
		super(pl);
		write(0x28);
		write(0x00);
		write(0x01);		// unknown
		writeShort(npc.getMapID());		// MapID
		write(0x00);
		write(0x00);
		writeInt(8958887);
		writeShort(npc.getType());
		writeShort(npc.getYpos());	// Y-Pos
		writeShort(npc.getZpos());	// Z-Pos
		writeShort(npc.getXpos());	// X-Pos
		write(0x00);		// Status
		writeShort(npc.getHP());	// HP
		write(0x00);
		writeShort(2828);
		write(0x07);
		writeShort(2313);
		write(unknowndata);
	}
}
