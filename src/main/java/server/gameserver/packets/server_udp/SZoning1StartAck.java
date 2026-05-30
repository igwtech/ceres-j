package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Server's fast start-ack to the client's {@code Zoning1}
 * ({@code 0x03/0x22/0x0d}), task #303. Retail emits this within
 * 0..30 ms of receiving Zoning1 — well before the {@code 0x25/0x13}
 * commit fires ~250..400 ms later (see {@link SZoning1}).
 *
 * <p>Without it the modern NCE client has no "request acknowledged"
 * signal during the ~400 ms gap and frequently treats the cross as a
 * full resync candidate, falling back to a session reconnect and
 * hanging on the splash. Memory note
 * {@code ceresj-zoning1-missing-startack} lists this as a likely root
 * cause of #172 / #175 / #208 cross-hangs.
 *
 * <p>Wire (7B inner + 0x03 reliable wrapper):
 * <pre>
 *   1f [mapID LE2] 25 23 [trailing:u8]
 * </pre>
 *
 * <p>Trailing byte observed across 4 retail crossings: 0x24 (3×).
 * Memory note {@code zoning1-body-pinned} §A. The byte may carry a
 * per-session token (similar to the commit's {@code txn_id}); for
 * now we send 0x24, matching the dominant retail observation.
 *
 * <p>{@link #SZoning1StartAck(Player)} uses the player's current
 * mapID — this packet is emitted from the SOURCE zone before
 * MISC_LOCATION is mutated, so it carries the source sec_id.
 */
public class SZoning1StartAck extends PacketBuilderUDP1303 {

    /** Default trailing byte (3/4 retail crossings; #270/#271/#303). */
    public static final int RETAIL_TRAILING_DEFAULT = 0x24;

    public SZoning1StartAck(Player pl) {
        this(pl, RETAIL_TRAILING_DEFAULT);
    }

    public SZoning1StartAck(Player pl, int trailing) {
        super(pl);
        write(0x1f);
        writeShort(pl.getMapID());
        write(0x25);
        write(0x23);
        write(trailing & 0xFF);
    }
}
