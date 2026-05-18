package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

/**
 * Reply to the raw (unreliable) C&rarr;S interaction poll
 * ({@code 0x1f [targetId] 01 55} &mdash; see
 * {@link server.gameserver.packets.client_udp.InteractionPoll}).
 *
 * <h3>Wire format (retail-verified)</h3>
 *
 * <p>Byte-pinned from
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}
 * (live server 157.90.195.74). Every C&rarr;S poll
 * {@code 1f &lt;id&gt; 01 55} (4 B) is answered by the server with an
 * 8-byte raw {@code 0x13}-wrapped sub-packet:
 *
 * <pre>
 *   1f &lt;id u8&gt; 01 56 00 00 00 00
 * </pre>
 *
 * <p>The reply echoes the request's {@code id} byte unchanged, the
 * fixed {@code 01}, then {@code 56} ({@code = request tag 0x55 + 1},
 * the request&rarr;response discriminator), followed by a 4-byte
 * zero result word. The request/response pairing is exact in the
 * capture: 9&times; {@code 1f2b0155} &harr; 9&times;
 * {@code 1f2b015600000000}, 5&times;&harr;5&times;,
 * 5&times;&harr;5&times;, 4&times;&harr;4&times; for ids
 * {@code 0x2b/0xd5/0x0a/0x0b}.
 *
 * <p>This is the interaction-target acknowledgement the client waits
 * for before it can commit a use/sit action against that entity. If
 * the server never replies the client's interaction lock-out never
 * releases &mdash; the symptom was the in-game "Unknown" message when
 * trying to sit where a retail player sits, because Ceres-J logged
 * the poll as {@code Unknown UDP13 Packet} and emitted no answer.
 *
 * <p>{@link PacketBuilderUDP13} writes the raw (unreliable)
 * {@code [0x13][ctr][ctr+sk][len]} frame, so this builder writes only
 * the sub-packet body starting at {@code 0x1f} (no {@code 0x03 seq}
 * reliable pair &mdash; the poll is on the raw channel).
 *
 * @see server.gameserver.packets.client_udp.InteractionPoll
 */
public class InteractionPollAck extends PacketBuilderUDP13 {

    /**
     * @param pl       the player to answer (supplies the per-session
     *                 0x13 counter / session key).
     * @param targetId the poll's target/event id byte &mdash; echoed
     *                 back unchanged so the client can match the
     *                 reply to its request.
     */
    public InteractionPollAck(Player pl, int targetId) {
        super(pl);
        write(0x1f);
        write(targetId & 0xFF); // echoed target/event id
        write(0x01);            // CONSTANT (matches request byte[2])
        write(0x56);            // request tag 0x55 + 1 = response
        write(0x00);
        write(0x00);
        write(0x00);
        write(0x00);            // 4-byte zero result word
    }
}
