package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.state.ClientStateMachine;
import server.tools.Debug;

/**
 * Handles {@code C→S 0x03/0x08} reliable-ACK frames.
 *
 * <p>Per the {@code reliable_ack_08_decoded} memory (2026-05-10):
 * {@code 0x03/0x08} is the client's explicit reliable-ACK channel,
 * NOT the long-mislabelled "ZoningEnd". The wire format is the
 * standard reliable wrapper:
 *
 * <pre>
 *   [0x03][own_seq LE2][0x08][acked_seq_minus_1 LE2]
 * </pre>
 *
 * <p>The acked seq is encoded as {@code (target_seq − 1) mod 2¹⁶},
 * matching retail's off-by-one convention. We re-add the one here
 * so the {@link ClientStateMachine} sees the actual seq the client
 * has confirmed.
 *
 * <p>Distinct from {@link ReliableAckSubPacket}, which decodes the
 * raw {@code 0x01} sub-channel (server retransmit-on-demand
 * requests from the client). The {@code 0x01} path consumes the
 * server's retransmit ring; the {@code 0x08} path here only
 * advances the FSM's view of "client has confirmed up to seq N."
 *
 * <p>This handler is observation-only (Phase 1, task #239). The
 * server already maintains its reliable-out ring elsewhere; this
 * landing just records the ack-point into
 * {@link ClientStateMachine}. Phase 2 will use it to gate the
 * portal-cross commit chain.
 */
public class ReliableAck08 extends GamePacketDecoderUDP {

    public ReliableAck08(byte[] subPacket) {
        super(subPacket);
    }

    /**
     * The seq the client has actually acknowledged (post off-by-one
     * correction). Returns {@code -1} on a malformed body.
     */
    public int ackedSeq() {
        // subPacket layout (6 bytes):
        //   [0]    0x03      reliable channel
        //   [1..2] own_seq   LE16 (this client's reliable seq)
        //   [3]    0x08      ack sub-opcode
        //   [4..5] target-1  LE16 (acked seq, off-by-one)
        if (buf == null || buf.length < 6) return -1;
        int wire = (buf[4] & 0xff) | ((buf[5] & 0xff) << 8);
        return (wire + 1) & 0xFFFF;
    }

    @Override
    public void execute(Player pl) {
        if (pl == null) return;
        int seq = ackedSeq();
        if (seq < 0) return;
        ClientStateMachine fsm = pl.getStateMachine();
        if (fsm == null) return;
        fsm.observeReliableAck(seq);
        if (Debug.isSubPacketsEnabled()) {
            Debug.subPacket("ReliableAck08: client confirmed seq=" + seq);
        }
    }
}
