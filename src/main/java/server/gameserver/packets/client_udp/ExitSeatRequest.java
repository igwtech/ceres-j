package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.tools.Out;

/**
 * Client-&gt;server explicit exit-seat request
 * ({@code 0x03/0x1f/0x22}). Sent when a seated player chooses to
 * stand up without moving (e.g. the client's stand-up key/UI).
 *
 * <h3>Wire format (retail-verified)</h3>
 *
 * <p>Byte-pinned from {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}.
 * At t=44.177s the seated player sent (post {@code 03 seq2}):
 *
 * <pre>
 *   1f 03 00 22            (no body)
 * </pre>
 *
 * <p>The server clears the player's seated state and broadcasts the
 * exit-seat posture
 * ({@link server.gameserver.packets.server_udp.ExitSeat},
 * {@code 0x03/0x1f/<localId>/0x22}) to the zone. Stand-up is also
 * implicitly triggered by any movement packet (see
 * {@link Movement}); this handler covers the explicit-request case
 * so a player who stands without moving still leaves the chair.
 *
 * @see server.gameserver.packets.server_udp.SitOnChair
 * @see server.gameserver.packets.server_udp.ExitSeat
 */
public class ExitSeatRequest extends GamePacketDecoderUDP {

	public ExitSeatRequest(byte[] subPacket) {
		super(subPacket);
	}

	public void execute(Player pl) {
		if (!pl.isSeated()) {
			return;
		}
		pl.setSeatedChairRawId(0);
		Zone z = pl.getZone();
		if (z != null) {
			z.sendPlayerExitSeat(pl);
			Out.writeln(Out.Info,
				"ExitSeatRequest: player stood up from chair");
		}
	}
}
