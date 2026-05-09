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
        // Recognise-only. Server-side reliable-retransmit state
        // is not modelled; the outer dispatcher already handles
        // the symmetric ACK direction.
    }
}
