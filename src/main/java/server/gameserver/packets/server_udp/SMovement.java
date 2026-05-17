package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

/**
 * Peer-movement broadcast (UDP S→C {@code 0x20} channel): tells a
 * receiver where another player has moved.
 *
 * <h3>Wire format (retail-verified)</h3>
 *
 * <p>Decoded from retail pcaps RETAIL_LONG_PARTY_A (S→C peer
 * broadcasts) and cross-checked against the matching C→S movement
 * frame in RETAIL_NORMAN / RETAIL_DRSTONE:
 *
 * <pre>
 *   [0]      0x20                  channel
 *   [1..2]   entity_id LE16        moving player's map/entity id
 *   [3]      0x27                  type bitmask Y|Z|X (0x01|0x02|0x04)
 *   [4..7]   Y coord float32 LE
 *   [8..11]  Z coord float32 LE
 *   [12..15] X coord float32 LE
 *   [16]     status/flags byte     (0x40 / 0x60 in samples)
 * </pre>
 *
 * <p><b>Pre-fix bug (task #174):</b> the previous emitter used type
 * {@code 0x7f} and wrote each coordinate as {@code uint16 (value +
 * 32000)} — the dead NC1-era frame. The NCE 2.5 ("Evolution")
 * client encodes coordinates as float32 LE in <em>both</em>
 * directions (same frame as the StartPos {@code 0x03/0x2c}
 * {@link PositionUpdate}). Emitting the legacy uint16 frame meant
 * peers rendered at garbage positions; the matching inbound
 * misdecode also corrupted persisted MISC coords, which is why a
 * relog spawned at the middle of the map.
 */
public class SMovement extends PacketBuilderUDP13 {

	public SMovement(Player pl, PlayerCharacter pc, int mapId) {
		super(pl);
		write(0x20);
		writeShort(mapId);
		write(0x27); // type bitmask: Y|Z|X present
		writeFloat((float) pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
		writeFloat((float) pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
		writeFloat((float) pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
		write(pc.getMisc(PlayerCharacter.MISC_STATUS));
	}
}
