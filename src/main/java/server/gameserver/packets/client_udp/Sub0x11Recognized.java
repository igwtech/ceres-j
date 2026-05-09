package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

/**
 * Recognises the 10-byte {@code UDP S->C 0x11} sub-packet.
 *
 * <p>Observed in 2 of 17 retail captures (2 hits, both 10 bytes):
 * <pre>
 *   11 00 03 [seq_lo seq_hi] 1f 01 00 38 01
 * </pre>
 * Same {@code 0x38} sub-tag as {@link Sub0x0FRecognized} but with
 * value {@code 0x01} instead of {@code 0x04}. Top marker:
 * {@code USE_ELEVATOR}, which is consistent with the {@code 0x0f}
 * door-events hypothesis.
 *
 * <p>Two-capture evidence is below the project's "≥3 to commit
 * field semantics" bar, so we recognise but don't act.
 *
 * <p>See {@code docs/protocol/packets/udp_s2c_11.md}.
 *
 * <p>TODO: act on this — likely the elevator/door-state companion
 * to {@code 0x0f}.
 */
public class Sub0x11Recognized extends GamePacketDecoderUDP {

    public Sub0x11Recognized(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        // Recognise-only — 2-capture evidence.
    }
}
