package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.tools.Out;

/**
 * Client-&gt;server "close current dialog / interaction overlay"
 * request ({@code 0x03/0x1f/0x01/0x00/0x27}).
 *
 * <h3>Wire format (retail-verified)</h3>
 *
 * <p>Byte-pinned 2026-05-22 against the retail catalog
 * (27 observations across multiple captures, top marker
 * {@code NPC_VENDOR_CLOSE × 1}). After the reliable envelope
 * {@code 03 [seq2] 1f 01 00} the inner body is a single byte:
 *
 * <pre>
 *   27
 * </pre>
 *
 * <p>No payload — the client just signals "I closed the active
 * dialog/vendor/CityCom window; release any server-side
 * interaction lock". 13 of 14 retail observations had no body
 * whatsoever; one outlier had {@code 27 04 00 1f 16 01 55} which
 * is the close-dialog byte followed by an unrelated raw 0x1f
 * InteractionPoll subpacket adjacency (the close-dialog itself is
 * still 1-byte).
 *
 * <h3>Status</h3>
 *
 * <p>Recognise-only — no server-side state is currently held per
 * open-dialog, so closing one is a no-op. The handler exists so
 * the dispatch path doesn't log {@code UnknownClientUDPPacket} on
 * a frequent and harmless wire event. Once vendor / CityCom
 * server-side state-machines are implemented, this packet becomes
 * the canonical "release lock" trigger.
 *
 * @see server.gameserver.packets.client_udp.UseItem
 * @see server.gameserver.packets.GamePacketReaderUDP
 */
public final class CloseDialog extends GamePacketDecoderUDP {

	public CloseDialog(byte[] subPacket) {
		super(subPacket);
	}

	@Override
	public void execute(Player pl) {
		// Recognise-only — see class javadoc.
		Out.writeln(Out.Info,
			"CloseDialog: player=" + pl.getName()
				+ " released interaction lock");
	}
}
