package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.EquipStateAck;
import server.tools.Out;

/**
 * Client-&gt;server toolbelt equip / holster request
 * ({@code 0x03/0x1f/0x01/0x00/0x1f/&lt;slot&gt;}). Sent when the
 * acting player presses a toolbelt slot key (1-4 on the slot rail)
 * or holster (slot 0 / unarmed).
 *
 * <h3>Wire format (retail-verified)</h3>
 *
 * <p>Byte-pinned 2026-05-22 against the full retail catalog
 * (28 observations across multiple captures). After the reliable
 * envelope {@code 03 [seq2] 1f 01 00} the inner body is a fixed
 * 2-byte tuple:
 *
 * <pre>
 *   1f &lt;slot&gt;
 * </pre>
 *
 * <p>Observed {@code slot} values:
 * <ul>
 *   <li>{@code 0x00} (12×) — holster / unarmed</li>
 *   <li>{@code 0x01} (8×) — toolbelt slot 1</li>
 *   <li>{@code 0x02} (3×) — toolbelt slot 2</li>
 *   <li>{@code 0x04} (1×) — toolbelt slot 3 (bitmask form)</li>
 *   <li>{@code 0x08} (2×) — toolbelt slot 4 (bitmask form)</li>
 *   <li>{@code 0xff} (1×) — sentinel / reset</li>
 * </ul>
 *
 * <p>The Ghidra-decoded PlayerAction dispatcher
 * ({@code FUN_0064ec90 case 0x4c} → {@code FUN_007fcaf0}) accepts
 * the raw slot byte and updates the local toolbelt selection.
 * Slot values appear to be a bitmask, not a 0-based index — slots
 * 1/2/3/4 map to 0x01/0x02/0x04/0x08.
 *
 * <h3>Status</h3>
 *
 * <p>Task #195. The server now records the player's equipped slot
 * ({@link Player#setEquippedSlot(int)}) and replies with the
 * byte-pinned S→C state-ack {@link EquipStateAck} — one reliable
 * {@code 0x03/0x1f} packet carrying
 * {@code 1f [mapid LE2] 25 13 [txn LE2] 0b [slot] 00} (the same
 * {@code 0x25 0x13} transactional envelope the cash carrier uses,
 * with the equipped-slot sub-tag {@code 0x0b} instead of cash's
 * {@code 0x04}). 1:1 causal, byte-verified across two retail
 * sessions.
 *
 * <p>NOT yet implemented: peer broadcast of the equip to nearby
 * players (the capture that pinned this was single-player, so the
 * peer-broadcast wire is still unpinned). The {@code 0x4c}
 * PlayerAction full-weapon report ({@code 1f 01 00 4c 0f 00 03 00})
 * is fire-and-forget and gets NO reply — it is dispatched to
 * {@code ChangedChannels}, not here.
 *
 * @see server.gameserver.packets.server_udp.EquipStateAck
 * @see server.gameserver.packets.client_udp.InventoryMove
 * @see server.gameserver.packets.GamePacketReaderUDP
 */
public final class EquipHolster extends GamePacketDecoderUDP {

	/** Slot byte indicating "holster / unarmed". */
	public static final int SLOT_HOLSTER = 0x00;

	/** Reserved sentinel (rare, retail-observed once). */
	public static final int SLOT_SENTINEL = 0xff;

	public EquipHolster(byte[] subPacket) {
		super(subPacket);
	}

	/**
	 * Parses the slot byte from the inner body. Exposed for unit
	 * tests so the byte-shape contract is pinned independently of
	 * the {@link Player} side-effects.
	 *
	 * <p>Expects the underlying buffer to be positioned at the
	 * reliable sub-packet's first byte (i.e. the leading {@code 03}
	 * has not been consumed). Skips the 6-byte envelope
	 * {@code 03 [seq2] 1f 01 00} + the 1-byte sub-tag {@code 0x1f}
	 * = 7 bytes total, then reads the slot byte at offset 7.
	 *
	 * @return slot byte (0..0xff)
	 */
	public int parseSlot() {
		reset();
		skip(7);
		return read() & 0xff;
	}

	@Override
	public void execute(Player pl) {
		int slot = parseSlot();
		// Record the equipped slot and reply with the byte-pinned
		// S→C state-ack (0x25/0x13/0x0b). Peer broadcast to nearby
		// players is still unpinned (single-player capture).
		pl.setEquippedSlot(slot);
		pl.send(new EquipStateAck(pl, slot));
		Out.writeln(Out.Info,
			"EquipHolster: player=" + pl.getName()
				+ " slot=0x" + Integer.toHexString(slot)
				+ " → EquipStateAck sent");
	}
}
