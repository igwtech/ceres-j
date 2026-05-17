package server.gameserver.packets.server_udp;

import java.nio.charset.StandardCharsets;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Reliable {@code 0x03 -> 0x28} WorldInfo packet for a single NPC.
 *
 * <p><strong>Retail-evidenced layout.</strong> Byte-diffed 2026-05-16
 * against the verified retail decode in
 * {@code docs/protocol/packets/udp_s2c_03_28.md} and the three raw
 * {@code 0x03/0x28} samples preserved in
 * {@code docs/protocol/_data/packets.json} (entities 0x0149, 0x013e,
 * 0x0130 from PLAZA-&gt;PEPPER) plus the hand-decoded AUGUSTO "PMAN"
 * (entity 0x0124) reference sample. Offsets below are relative to the
 * full inner body <em>including</em> the {@code 0x28} sub-op, matching
 * the catalog decode:
 *
 * <pre>
 *   [0]      28                     sub-opcode (written by caller)
 *   [1-2]    00 01                  CONSTANT (every retail sample)
 *   [3-4]    world_obj_id LE16      per-NPC (== the 0x1b / 0x2d id)
 *   [5-6]    00 00                  CONSTANT
 *   [7-10]   instance_handle LE32   per-NPC unique, stable across all
 *                                   of this NPC's packets. RETAIL: a
 *                                   server-assigned handle; the three
 *                                   preserved samples carry distinct
 *                                   values (0x78edef93, 0x78edeb27,
 *                                   0x78ee1d76) and the AUGUSTO sample
 *                                   0x379a516a. The pre-#178 constant
 *                                   8958887 (0x0088B3A7) appears in
 *                                   NONE of the retail evidence.
 *   [11-12]  class_id LE16          per-NPC class (0x28-space; e.g.
 *                                   PMAN 0x014f, entity-0149 0x003b).
 *                                   NOTE: this is a different field
 *                                   from raw 0x1b's entity_class_id.
 *   [13-14]  Y LE16
 *   [15-16]  Z LE16
 *   [17-18]  X LE16
 *   [19-33]  15-byte state/attr     RETAIL: a state block whose exact
 *                                   sub-structure is an open question
 *                                   (PMAN = 00 ca 06 + 12x00; entity
 *                                   0149 = 00 0e 01 + 12x00). It is
 *                                   per-NPC runtime state we cannot
 *                                   reproduce; emitting all-zero is a
 *                                   retail-valid "no-state" instance
 *                                   (the trailing >=12 bytes are zero
 *                                   in every sample) and avoids
 *                                   inventing unverified bytes.
 *   [34+]    type_name\0            ASCII NPC type token (e.g. "PMAN")
 *   [+]      orientation\0          ASCII signed decimal (e.g. "1")
 * </pre>
 *
 * <p>What #178 corrected vs the pre-#178 implementation:
 * <ul>
 *   <li>{@code [7..10]} was hard-coded to the constant {@code 8958887}
 *       for <em>every</em> NPC. The client keys its world-object table
 *       on this handle, so every NPC collided onto one object
 *       reference. Retail proves this field is per-NPC and that the
 *       constant never appears. Now a deterministic per-NPC handle.</li>
 *   <li>The pre-#178 body placed a spurious {@code 0x22} "zone area"
 *       byte and a 16-byte filler between X and the strings, landing
 *       the strings one byte late. Retail has exactly a 15-byte block
 *       there. Now 15 bytes.</li>
 *   <li>The trailing strings were {@code scriptName\0 modelName\0};
 *       retail is {@code type_name\0 orientation\0}. Now corrected.</li>
 * </ul>
 *
 * <p>An earlier (unvalidated) #178 WIP attempt wrote {@code [18]=00}
 * then a 4-byte {@code maxHP} as a "class_attr" — that was pure
 * speculation (no retail sample shows an HP value there: entity-0149's
 * bytes are {@code 00 0e 01 00 00}, not an HP magnitude) and it left
 * the body two bytes too long, mislocating the strings. Discarded.
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
	 * {@code 0x03/0x28} emitters cannot drift apart again. Offsets in
	 * comments are doc-relative (i.e. {@code 0x28} is at doc [0], so
	 * the first byte written here is doc [1]).
	 */
	static void writeBody(PacketBuilderUDP1303 b, NPC npc) {
		// [1-2] constant
		b.write(0x00);
		b.write(0x01);
		// [3-4] world-object id
		b.writeShort(npc.getMapID());
		// [5-6] constant
		b.write(0x00);
		b.write(0x00);
		// [7-10] per-NPC unique, stable instance handle
		b.writeInt(npc.getWorldInstanceHandle());
		// [11-12] class id (0x28-space)
		b.writeShort(npc.getType());
		// [13-14] Y
		b.writeShort(npc.getYpos());
		// [15-16] Z
		b.writeShort(npc.getZpos());
		// [17-18] X
		b.writeShort(npc.getXpos());
		// [19-33] 15-byte state/attr block. Retail sub-structure is an
		// open question; all-zero is a retail-valid no-state instance
		// (>=12 trailing bytes zero in every sample). Emitting any
		// non-zero guess here would be unbacked by retail evidence.
		for (int i = 0; i < 15; i++) {
			b.write(0x00);
		}
		// [34..] type_name\0 orientation\0
		b.write(npc.getName().getBytes(StandardCharsets.US_ASCII));
		b.write(0x00);
		b.write(Integer.toString(npc.getAngle())
				.getBytes(StandardCharsets.US_ASCII));
		b.write(0x00);
	}
}
