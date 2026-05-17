package server.gameserver.packets.server_udp;

import java.nio.charset.StandardCharsets;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Reliable {@code 0x03 -> 0x28} WorldInfo packet for a single NPC.
 *
 * <p><strong>Retail-evidenced layout</strong> (byte-diffed 2026-05-16
 * against 331 {@code 0x03/0x28} packets from the AUGUSTO, NORMAN,
 * DRSTONE and PLAZA-&gt;PEPPER retail captures — see
 * {@code docs/protocol/packets/udp_s2c_03_28.md}):
 *
 * <pre>
 *   [0-1]   00 01                  CONSTANT
 *   [2-3]   world_obj_id LE16      per-NPC (== the 0x1b / 0x2d id)
 *   [4-5]   00 00                  CONSTANT
 *   [6-9]   instance_handle LE32   per-NPC unique, stable across all
 *                                  of this NPC's packets. RETAIL: a
 *                                  server-assigned handle (231 distinct
 *                                  values / 331 packets); the old
 *                                  hard-coded 8958887 appeared 0/331.
 *   [10-11] class_id LE16          per-NPC class / model id
 *   [12-13] Y LE16
 *   [14-15] Z LE16
 *   [16-17] X LE16
 *   [18]    00                     CONSTANT
 *   [19-22] class_attr LE32        class-derived (base HP magnitude in
 *                                  retail; e.g. 0x01ae WSK, 0x3bc1 COPBOT)
 *   [23-32] 00 x10                 stat slots (zero for non-combat NPCs)
 *   [33]    00                     CONSTANT
 *   [34]    00                     string separator
 *   [35+]   type_name\0            ASCII NPC type token (e.g. "WSK")
 *   [+]     orientation\0          ASCII signed decimal (e.g. "-90")
 * </pre>
 *
 * <p>The pre-2026-05-16 implementation hard-coded {@code [6..9]} to the
 * constant {@code 8958887} for <em>every</em> NPC. The client keys its
 * world-object table on this handle, so every NPC collided onto one
 * object reference and none rendered. It also re-emitted the model name
 * as the trailing string where retail emits the ASCII orientation.
 * Both are corrected here.
 */
public class WorldNPCInfo extends PacketBuilderUDP1303 {

	public WorldNPCInfo(Player pl, NPC npc) {
		super(pl);
		write(0x28);
		writeBody(this, npc);
	}

	/**
	 * Write the post-{@code 0x28} body for {@code npc} into {@code b}.
	 * Shared with {@link ZoneStateCompoundPacket} so the two
	 * {@code 0x03/0x28} emitters cannot drift apart again.
	 */
	static void writeBody(PacketBuilderUDP1303 b, NPC npc) {
		// [0-1]
		b.write(0x00);
		b.write(0x01);
		// [2-3] world-object id
		b.writeShort(npc.getMapID());
		// [4-5]
		b.write(0x00);
		b.write(0x00);
		// [6-9] per-NPC unique, stable instance handle
		b.writeInt(npc.getWorldInstanceHandle());
		// [10-11] class id
		b.writeShort(npc.getType());
		// [12-13] Y
		b.writeShort(npc.getYpos());
		// [14-15] Z
		b.writeShort(npc.getZpos());
		// [16-17] X
		b.writeShort(npc.getXpos());
		// [18] constant 00
		b.write(0x00);
		// [19-22] class-derived attribute (base HP magnitude in retail)
		b.writeInt(npc.getMaxHP());
		// [23-32] 10 stat slots (zero for non-combat NPCs)
		for (int i = 0; i < 10; i++) {
			b.write(0x00);
		}
		// [33] constant 00
		b.write(0x00);
		// [34] string separator
		b.write(0x00);
		// [35..] type_name\0 orientation\0
		b.write(npc.getName().getBytes(StandardCharsets.US_ASCII));
		b.write(0x00);
		b.write(Integer.toString(npc.getAngle())
				.getBytes(StandardCharsets.US_ASCII));
		b.write(0x00);
	}
}
