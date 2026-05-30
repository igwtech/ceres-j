package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.gameserver.Zone;
import server.networktools.PacketBuilderUDP1303;

/**
 * S→C {@code 0x03/0x1f/<mapId LE2>/0x1b} — LocalChat broadcast.
 *
 * <h3>Wire format (live-verified 2026-05-29 iter 45 in Viarosso
 * via Frida-in-Wine capture, msn3wolf/Krafteo typing "Hi everyone"):</h3>
 *
 * <pre>
 *   13 [seq:LE2] [ack:LE2] [sublen:LE2]
 *      03 [seq2:LE2]
 *      1f                  event class
 *      [mapId LE2]         sender's district (1=Plaza, 5=Viarosso, …)
 *      1b                  LocalChat sub-tag
 *      [ASCII bytes …]     message body (typically NUL-terminated)
 * </pre>
 *
 * <p>The mapId field is variable per-district — see
 * {@link Zone#getDistrictMapId()} for the live-verified mapping.
 * Hardcoded mapId of {@code 0} (the old 1-arg constructor) leaves
 * remote clients displaying chat with a default channel routing,
 * which works but is not retail-faithful.
 *
 * @see Zone#getDistrictMapId()
 */
public class LocalChatMessage extends PacketBuilderUDP1303 {

	/**
	 * Build a LocalChat broadcast deriving mapId from the player's
	 * current zone. Prefer this overload for retail-faithful chat —
	 * the previous hardcoded-{@code 0} version is still available
	 * but should only be used when a non-zone context applies (e.g.
	 * system-bus chat where district doesn't apply).
	 */
	public LocalChatMessage(Player pl, String message) {
		this(pl, message, derivedMapIdFor(pl));
	}

	public LocalChatMessage(Player pl, String message, int mapId) {
		super(pl);
		write(0x1f);
		writeShort(mapId);
		write(0x1b);
		write(message.getBytes());
	}

	/**
	 * Resolve the player's current district mapId (1=Plaza,
	 * 5=Viarosso, etc.). Falls back to {@code 2} (generic outdoor)
	 * when the player has no resolvable zone, matching the
	 * historical hardcoded value some callers used.
	 */
	private static int derivedMapIdFor(Player pl) {
		if (pl == null) return 2;
		Zone z = pl.getZone();
		return (z == null) ? 2 : z.getDistrictMapId();
	}
}
