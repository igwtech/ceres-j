package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
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
 * <p>Task #195. Currently a recognise-only handler: the server
 * acknowledges the wire and logs the slot, but does NOT yet update
 * the player's equipped-item state or broadcast the equip event to
 * peers. Implementing equipped-item persistence + the S→C peer
 * broadcast is follow-up work (the broadcast wire is unpinned as of
 * 2026-05-22 — no clean retail equip/holster capture found in the
 * existing pcap corpus).
 *
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
		// Recognise-only for now (task #195). The S→C peer-broadcast
		// wire is not yet byte-pinned and the equipped-item state
		// machine is not modelled server-side.
		Out.writeln(Out.Info,
			"EquipHolster: player=" + pl.getName()
				+ " slot=0x" + Integer.toHexString(slot)
				+ " (recognise-only — see task #195)");
	}
}
