package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

/**
 * Server→client echo of a C→S {@code 0x03/0x1f/0x1e} InventoryMove.
 *
 * <h3>Wire form (current)</h3>
 *
 * <p>Emits the {@code 1f [mapid LE2] 25 1e [container_ref][slot…]}
 * form — act_tag {@code 0x25}, sub-action {@code 0x1e}. This matches
 * the catalog doc spec:
 * <ul>
 *   <li>{@code docs/protocol/packets/udp_s2c_03_1f.md} (10-byte
 *       variant {@code 1f 01 00 25 1e b6 cc 01 00 0f} — "server echo of
 *       the C→S 1e InventoryMove — the body is the moved-item's
 *       container reference + slot").</li>
 *   <li>{@code docs/protocol/_funcref_subtags.md} table-B row 0x1e:
 *       {@code 25 1e [container_ref][slot…]} (~10B).</li>
 * </ul>
 *
 * <p><strong>UNVERIFIED:</strong> the exact body layout below
 * (echoing dst container + dst position) is DOC-DERIVED only. We do
 * NOT yet have a clean retail F2-drag pcap to byte-confirm the
 * {@code 0x25/0x1e} body (field order, widths, the trailing
 * {@code 0x0f}-style byte). A clean retail inventory-move capture is
 * still needed to pin this exactly.
 *
 * <h3>Old form (kept for byte knowledge — DO NOT delete)</h3>
 *
 * <p>Prior to 2026-05 this class emitted the {@code 0x25/0x13}
 * txn-wrapper with inner tag {@code 0x14}:
 * <pre>
 *   1f [mapid LE2] 25 13 [txn LE2] 14
 *   [srcCont][srcPos LE2][dstCont][dstPos LE2] 01 00 00
 * </pre>
 * That {@code 0x25/0x13} channel is actually the CASH transaction
 * carrier (see {@code CashUpdate}); it was the wrong sub-channel for
 * an inventory-move echo. The byte sequence is preserved here as a
 * comment so the knowledge isn't lost if the {@code 0x25/0x1e}
 * hypothesis is later disproved against a retail capture.
 */
public class InventoryMoveAck extends PacketBuilderUDP13{

	public InventoryMoveAck(Player pl, int isrccont, int srcpos, int idstcont, int dstpos) {
		super(pl);
		write(0x1f);
		writeShort(pl.getMapID());
		write(0x25); // act_tag
		write(0x1e); // sub-action: InventoryMove echo (doc-derived)
		// Body = moved-item's container reference + slot (the
		// DESTINATION, where the item now lives). Doc-derived; needs a
		// retail F2-drag pcap to byte-confirm field order/widths.
		write(idstcont);
		writeShort(dstpos);

		// --- OLD 0x25/0x13 txn-wrapper form (CASH carrier sub-channel,
		//     wrong for inventory; preserved for byte knowledge) ---
		// write(0x25); write(0x13);
		// pl.incrementTransactionID();
		// writeShort(pl.getTransactionID());
		// write(0x14);
		// write(isrccont); writeShort(srcpos);
		// write(idstcont); writeShort(dstpos);
		// write(new byte[]{0x01, 0x00, 0x00});
	}
}
