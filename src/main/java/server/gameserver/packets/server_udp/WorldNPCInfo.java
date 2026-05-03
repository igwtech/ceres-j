package server.gameserver.packets.server_udp;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Reliable {@code 0x03→0x28} WorldInfo packet for a single NPC.
 *
 * <p>Retail inner-payload layout (confirmed from pepper_p3 captures):
 * <pre>
 *   [0-1]   00 01
 *   [2-3]   world_obj_id  (LE16) — must match the 0x1b broadcast id
 *   [4-5]   00 00
 *   [6-9]   world_instance_ref (LE32, hardcoded 0x008897A7 = 8958887)
 *   [10-11] npc_type_id   (LE16) — index into client's pak_npc.def
 *   [12-13] Y             (LE16)
 *   [14-15] Z             (LE16)
 *   [16-17] X             (LE16)
 *   [18]    00
 *   [19]    00            (session-related, unknown)
 *   [20]    0x22          (zone-area byte, varies per zone sub-sector)
 *   [21-23] 00 00 00
 *   [24-28] 00 00 00 00 00  (NPC stats — unknown encoding, zeroed for now)
 *   [29]    00            (combat class)
 *   [30-34] 00 00 00 00 00
 *   [35+]   script_name\0 (from pak_npc.def column 22)
 *   [+]     model_name\0  (from pak_npc.def column 23)
 * </pre>
 */
public class WorldNPCInfo extends PacketBuilderUDP1303 {

	public WorldNPCInfo(Player pl, NPC npc) {
		super(pl);
		write(0x28);

		// [0-1]
		write(0x00);
		write(0x01);
		// [2-3] world-object ID
		writeShort(npc.getMapID());
		// [4-5]
		write(0x00);
		write(0x00);
		// [6-9] world instance ref
		writeInt(8958887);
		// [10-11] NPC type ID (pak_npc.def setentry index)
		writeShort(npc.getType());
		// [12-13] Y
		writeShort(npc.getYpos());
		// [14-15] Z
		writeShort(npc.getZpos());
		// [16-17] X
		writeShort(npc.getXpos());
		// [18] padding
		write(0x00);
		// [19] unknown variable byte
		write(0x00);
		// [20] zone area
		write(0x22);
		// [21-23] padding
		write(0x00);
		write(0x00);
		write(0x00);
		// [24-28] stats (unknown, zeroed)
		write(0x00);
		write(0x00);
		write(0x00);
		write(0x00);
		write(0x00);
		// [29] combat class
		write(0x00);
		// [30-34] padding
		write(0x00);
		write(0x00);
		write(0x00);
		write(0x00);
		write(0x00);
		// [35+] script_name\0 model_name\0
		byte[] scriptBytes = npc.getScriptName().getBytes();
		write(scriptBytes);
		write(0x00);
		byte[] modelBytes = npc.getModelName().getBytes();
		write(modelBytes);
		write(0x00);
	}
}
