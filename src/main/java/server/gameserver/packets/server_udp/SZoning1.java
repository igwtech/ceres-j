package server.gameserver.packets.server_udp;

import java.util.List;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.networktools.PacketBuilderUDP1303;

/**
 * Server's zone-cross <em>confirmation</em> burst, sent in reply to
 * the client's {@code Zoning1} (0x03/0x22/0x0d). The client emits
 * {@code Zoning2} ~35 ms after receiving it; without it the cross
 * never completes (verified across all 8 RETAIL_PLAZA_CROSSZONE
 * crossings — this is the last S→C packet before every Zoning2).
 *
 * <p>One {@code 0x13} datagram, multiple reliable sub-packets
 * (each via {@link PacketBuilderUDP1303#newSubPacket()} so each
 * gets its own contiguous reliable seq — retail bundles them the
 * same way and the client accepts it):
 *
 * <pre>
 *   0x03/0x1f  1f [mapID LE2] 25 13 [txn LE2] 0e 02   confirm marker
 *   0x03       2d [npcId LE2] 01 00 00 06   ×N         destination
 *                                                      zone NPC roster
 *   0x03/0x23  23 04 00…00 [i LE4] [txn LE2] 00 00     zone-info ack
 * </pre>
 *
 * Byte-verified against retail crossing D (→plaza_p3):
 * {@code 1f 05 00 25 13 02 30 0e 02} + 9×
 * {@code 2d <id> 01 00 00 06} + {@code 23 04 …01000000 0230 0000}.
 * Earlier builds omitted the NPC roster (marker + 0x23 only); the
 * client then couldn't populate the destination zone, never sent
 * Zoning2, fell back to a full seq-1 resync and reverted.
 */
public class SZoning1 extends PacketBuilderUDP1303 {

	/**
	 * @param i        the {@code 0x23} info field — the 2nd int of
	 *                 the Zoning1 body (retail crossing D: 1).
	 * @param pl       the player crossing.
	 * @param destZone the destination {@link Zone} whose NPC roster
	 *                 is streamed in the confirm burst. May be
	 *                 {@code null} (emits marker + 0x23 only).
	 */
	public SZoning1(int i, Player pl, Zone destZone) {
		super(pl);
		// ── confirm marker ──
		write(0x1f);
		writeShort(pl.getMapID());
		write(new byte[] {0x25, 0x13});
		pl.incrementTransactionID();
		writeShort(pl.getTransactionID());
		write(new byte[] {0x0e, 0x02});

		// ── destination-zone NPC roster ──
		// retail: 09 00 03 [seq] 2d [npcId LE2] 01 00 00 06
		if (destZone != null) {
			List<NPC> npcs = destZone.getAllNPCs();
			if (npcs != null) {
				for (NPC npc : npcs) {
					if (npc == null) continue;
					newSubPacket();
					write(0x2d);
					writeShort(npc.getMapID());
					write(new byte[] {0x01, 0x00, 0x00, 0x06});
				}
			}
		}

		// ── zone-info ack ──
		newSubPacket();
		write(new byte[] {
				  0x23, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00
				, 0x00, 0x00, 0x00, 0x00, 0x00});
		writeInt(i);
		writeShort(pl.getTransactionID());
		write(new byte[] {0x00, 0x00});
	}

	/** Back-compat: confirm with no NPC roster (marker + 0x23). */
	public SZoning1(int i, Player pl) {
		this(i, pl, null);
	}
}
