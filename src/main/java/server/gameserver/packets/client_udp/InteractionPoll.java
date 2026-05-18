package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.InteractionPollAck;
import server.tools.Out;

/**
 * Raw (unreliable) C&rarr;S interaction poll &mdash; the client
 * asking the server to confirm an interactive target before it
 * commits a use/sit/dialog action against that entity.
 *
 * <h3>Wire format (retail-verified)</h3>
 *
 * <p>Byte-pinned from
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}
 * (live server 157.90.195.74). Fixed 4-byte raw sub-packet inside
 * the {@code 0x13} wrapper (NOT {@code 0x03}-reliable):
 *
 * <pre>
 *   [0]  0x1f               raw sub-opcode
 *   [1]  target/event id    varies per target (0x2b, 0xd5, 0x0a,
 *                            0x0b, ... in this capture)
 *   [2]  0x01               CONSTANT
 *   [3]  0x55               CONSTANT (request tag; reply uses 0x56)
 * </pre>
 *
 * <p>All 23 C&rarr;S observations in the capture were exactly this
 * 4-byte form (length, byte[2]=0x01, byte[3]=0x55 invariant). The
 * client emits it in short bursts (~0.7&nbsp;s apart) while it has
 * an interactive entity targeted &mdash; e.g. just before pressing
 * E on a chair / NPC. This is the same raw {@code 0x1f} documented
 * in {@code docs/protocol/packets/udp_c2s_1f.md}; that doc only
 * covered the non-{@code 0x13}-wrapped path, so the wrapped poll
 * fell through to {@code Unknown UDP13 Packet}.
 *
 * <h3>Server response</h3>
 *
 * <p>Retail answers <em>every</em> poll with
 * {@link InteractionPollAck}
 * ({@code 1f &lt;id&gt; 01 56 00 00 00 00}), echoing the {@code id}
 * byte. The pairing is 1:1 in the capture. Without this reply the
 * client's interaction lock-out never releases &mdash; the observed
 * symptom was the in-game "Unknown" message when trying to sit where
 * a retail player sits.
 *
 * @see InteractionPollAck
 * @see UseItem
 */
public class InteractionPoll extends GamePacketDecoderUDP {

    /** Target/event id from body[1]. {@code -1} if the body was too
     *  short to carry one (defensive — retail is always 4 bytes). */
    private final int targetId;

    public InteractionPoll(byte[] subPacket) {
        super(subPacket);
        int id = -1;
        if (subPacket.length >= 2) {
            id = subPacket[1] & 0xFF;
        }
        this.targetId = id;
    }

    /** The polled target/event id (body[1]), or {@code -1}. */
    public int getTargetId() {
        return targetId;
    }

    public void execute(Player pl) {
        if (pl == null || targetId < 0) {
            return;
        }
        Out.writeln(Out.Info,
            "InteractionPoll: target id=0x"
            + Integer.toHexString(targetId) + " → ack");
        pl.send(new InteractionPollAck(pl, targetId));
    }
}
