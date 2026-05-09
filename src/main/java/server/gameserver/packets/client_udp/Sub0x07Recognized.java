package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

/**
 * Recognises the 10-byte {@code UDP S->C 0x07} sub-packet.
 *
 * <p>Observed in only 1 of 17 retail captures
 * ({@code RETAIL_VEHICLE_DRONE}, 4 hits, all 10 bytes). Layout
 * mirrors a reliable wrapper:
 * <pre>
 *   07 00 03 [seq_lo seq_hi] 1f 01 00 4a [byte]
 * </pre>
 * The trailing {@code 1f / 01 00 / 4a / [byte]} resembles a
 * reliable {@code 0x03→0x1f} GamePacket with sub-tag {@code 0x4a}
 * — a channel only the vehicle/drone capture exercised.
 *
 * <p>Per project rule "no speculation under 3 captures" we don't
 * try to parse fields; just recognise the byte to keep the log
 * clean if the parser is ever fed this shape (e.g. via the replay
 * harness).
 *
 * <p>See {@code docs/protocol/packets/udp_s2c_07.md}.
 *
 * <p>TODO: act on this — needs more captures of vehicle/drone
 * traffic to pin {@code 0x4a} sub-tag semantics.
 */
public class Sub0x07Recognized extends GamePacketDecoderUDP {

    public Sub0x07Recognized(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        // Recognise-only — single-capture evidence.
    }
}
