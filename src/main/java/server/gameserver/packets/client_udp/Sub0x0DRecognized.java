package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

/**
 * Recognises the 10-byte {@code UDP S->C 0x0d} sub-packet.
 *
 * <p>Observed in 2 of 17 retail captures (4 hits, all 10 bytes).
 * Layout mirrors a reliable wrapper carrying a {@code 0x1f / 0x2d}
 * sub-tag:
 * <pre>
 *   0d 00 03 [seq_lo seq_hi] 1f 01 00 2d [byte]
 * </pre>
 * The {@code 1f 01 00 2d} suffix is the reliable {@code 0x03→0x1f}
 * GamePacket header followed by sub-tag {@code 0x2d} (NPC-data on
 * the client→server side). Top marker:
 * {@code OUTSIDE_AREAM5_GENREP_OPEN}.
 *
 * <p>Two-capture evidence is below the project's "≥3 to commit
 * field semantics" bar, so the value byte is read-only — we
 * recognise the shape but don't ascribe meaning.
 *
 * <p>See {@code docs/protocol/packets/udp_s2c_0d.md}.
 *
 * <p>TODO: act on this — likely a server-pushed NPC state delta
 * but evidence is thin.
 */
public class Sub0x0DRecognized extends GamePacketDecoderUDP {

    public Sub0x0DRecognized(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        // Recognise-only — 2-capture evidence is below threshold
        // for asserting field semantics.
    }
}
