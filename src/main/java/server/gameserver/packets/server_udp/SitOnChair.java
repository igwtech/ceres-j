package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Chair-sit posture broadcast — sent when a player uses (clicks) a
 * world furniture object whose {@code worldmodel.def} UseFlags carry
 * the {@code ufChair} bit (8). Puts the subject player into the
 * seated posture for every client in the zone.
 *
 * <h3>Wire format (retail-verified)</h3>
 *
 * <p>Byte-pinned from {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}
 * (server 157.90.195.74). When the captured player clicked a chair
 * (C→S {@code 0x03/0x1f/0x17} UseItem with rawObjectId
 * {@code 0x00084800}, t=41.995s) the server replied at t=42.368s with
 * a reliable {@code 0x03/0x1f} sub-packet:
 *
 * <pre>
 *   03 &lt;seq LE2&gt; 1f &lt;localId LE2&gt; 21 &lt;rawObjectId LE4&gt; &lt;seatId u8&gt;
 * </pre>
 *
 * <p>Captured inner body (post {@code 03 seq2}):
 * {@code 1f 03 00 21 00 48 08 00 00} — localId {@code 0x0003},
 * tag {@code 0x21}, rawObjectId {@code 0x00084800} (echoes the id the
 * client sent in its {@code 0x17} use packet), seatId {@code 0x00}
 * (0 = real chair; 1+ = subway-cab seat). The server re-broadcasts
 * this every ~5 s while the player remains seated (the client
 * re-sends its {@code 0x17} keepalive at the same cadence).
 *
 * <p>This is the NC2 spelling of the NC1
 * {@code PMsgBuilder::BuildCharUseSeatMsg}
 * ({@code tinns/.../MessageBuilder.cxx:513}); same tag (0x21) and
 * field order, NC2's {@code 1f &lt;localId LE2&gt;} where NC1 wrote
 * {@code 1f &lt;localId LE2&gt;} identically.
 *
 * <p>{@link PacketBuilderUDP1303} writes the
 * {@code [0x13][ctr][ctr+sk][len][0x03][seq LE2]} reliable frame, so
 * this builder writes only the body that follows the {@code 0x03 seq}
 * pair, starting at {@code 0x1f}.
 *
 * @see ExitSeat
 * @see server.gameserver.packets.client_udp.UseItem
 */
public class SitOnChair extends PacketBuilderUDP1303 {

    /**
     * @param pl           the player the reliable frame is sent to
     *                     (supplies the per-session seq counter).
     * @param subjectMapId map/local id of the player who sat down
     *                     (as the receiver sees them; equals
     *                     {@code seatedPlayer.getMapID()}).
     * @param rawObjectId  the chair's rawObjectId — the exact value
     *                     the client sent in its {@code 0x17} use
     *                     packet (echoed back unchanged).
     * @param seatId       seat index: {@code 0} for a real chair,
     *                     {@code 1+} for a subway-cab seat.
     */
    public SitOnChair(Player pl, int subjectMapId, int rawObjectId,
                      int seatId) {
        super(pl);
        write(0x1f);
        writeShort(subjectMapId);   // localId LE16 (subject player)
        write(0x21);
        writeInt(rawObjectId);      // rawObjectId u32 LE (echoed)
        write(seatId & 0xFF);       // seatId (0 = real chair)
    }
}
