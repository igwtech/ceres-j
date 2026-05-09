package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

/**
 * Recognises the 1-byte {@code UDP S->C 0x31} singleton.
 *
 * <p>Observed in only 1 of 17 retail captures
 * ({@code RETAIL_ODA}, 1 hit, exactly 1 byte: {@code 31}). No
 * payload, no markers, no temporal correlate. Not enough data to
 * propose a meaning.
 *
 * <p>Per project rule "no speculation under 3 captures" the
 * decoder recognises only — the single hit is documented in
 * {@code docs/protocol/packets/udp_s2c_31.md} for future
 * correlation work.
 *
 * <p>TODO: act on this — needs more captures.
 */
public class Sub0x31Recognized extends GamePacketDecoderUDP {

    public Sub0x31Recognized(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        // Recognise-only — single-capture evidence.
    }
}
