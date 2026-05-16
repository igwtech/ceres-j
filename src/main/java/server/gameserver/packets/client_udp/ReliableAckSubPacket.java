package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

/**
 * Recognises the {@code UDP S->C 0x01} reliable-ACK sub-packet.
 *
 * <p>3-byte body {@code [0x01][seq_lo][seq_hi]} — the same wire
 * shape that {@code GamePacketReaderUDP.readPacket} synthesises
 * when it ACKs a client-pushed {@code 0x03} reliable. In retail
 * captures the server emits this exact frame to ACK any reliable
 * the client sent up.
 *
 * <p>Observed in 3 of 17 retail captures (13 hits). All 13 are
 * exactly 3 bytes; the LE16 sequence increments monotonically per
 * connection (e.g. {@code 01 44 5a, 01 45 5a, 01 46 5a} in
 * {@code RETAIL_VEHICLE_DRONE}). Top marker:
 * {@code OUTSIDE_AREAM5_GENREP_OPEN}.
 *
 * <p>The server has no client-side reliable-state to advance, so
 * this is recognise-and-ignore: the legacy reliable layer never
 * tracks server-pushed retransmits, and the client's own ACKs are
 * already handled by the outer {@code 0x13} dispatcher (which
 * fires a server-side ACK on every {@code 0x03} body it sees).
 *
 * <p>See {@code docs/protocol/packets/udp_s2c_01.md}.
 */
public class ReliableAckSubPacket extends GamePacketDecoderUDP {

    public ReliableAckSubPacket(byte[] subPacket) {
        super(subPacket);
    }

    /**
     * Convenience accessor for tests: the LE16 sequence number
     * being ACKed (post-skip of the {@code 0x01} tag byte).
     */
    public int ackSeq() {
        if (buf.length < 3) return -1;
        return (buf[1] & 0xff) | ((buf[2] & 0xff) << 8);
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || pl.getUdpConnection() == null) return;
        // Harness/test gate: when the player isn't logged in, the
        // ring's contents come from a test-fixture's emit history
        // and don't correspond to retail's seq space. Suppress the
        // retransmit to avoid pcap-replay alignment noise. The
        // production overlay-clear path completes its handshake
        // before any retransmit requests arrive, so this guard
        // doesn't affect real gameplay.
        if (!pl.isloggedin()) return;
        int seq = ackSeq();
        if (seq < 0) return;
        // Look up the requested seq in the per-session ring;
        // re-emit via the 0x02 simplified-reliable wrapper.
        // Without this the client's "Synchronizing into city zone"
        // overlay never clears (task #136 / #151 — retail captures
        // show ~17 ack-requests in 2 rounds within ~1s post-
        // handshake, all expecting matching 0x02 retransmits).
        server.networktools.ReliablePacketRing ring =
                pl.getUdpConnection().reliableRing();
        byte[] body = ring.get(seq);
        if (body == null) {
            // Evicted or never emitted. Silently ignore — the
            // client's reliable layer has its own bounded retry /
            // give-up logic.
            int counter = pl.getUdpConnection().getSessionCounter();
            int ringSize = ring.size();
            server.tools.Out.writeln(server.tools.Out.Info,
                    "ReliableAck: seq " + seq
                    + " not in ring (counter=" + counter
                    + " ring_size=" + ringSize + ")");
            return;
        }
        // Re-wrap as [0x02][seq LE2][original body]. The seq MUST
        // be the exact seq the client asked for — retail
        // (RETAIL_PLAZA_CROSSZONE) always echoes the requested seq
        // so the client can slot the resent body into the gap in
        // its reliable receive window. Using a free-running counter
        // here (the old bug) meant the client could never close the
        // gap and flooded 0x01 requests forever, blocking the
        // zone-cross ("Synchronizing" overlay).
        server.networktools.PacketBuilderUDP1302 retransmit =
                new server.networktools.PacketBuilderUDP1302(pl, seq);
        retransmit.write(body);
        pl.send(retransmit);
    }
}
