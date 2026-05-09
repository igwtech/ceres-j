package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

/**
 * Recognises the 1-byte {@code UDP S->C 0x06} singleton.
 *
 * <p>Observed in 4 of 17 retail captures (6 hits, all exactly
 * 1 byte: {@code 06}). No payload, no markers, no temporal
 * pattern across captures. Looks like a session-keepalive or
 * channel-poke beacon — not enough evidence to act yet.
 *
 * <p>See {@code docs/protocol/packets/udp_s2c_06.md}.
 *
 * <p>TODO: act on this — current evidence is insufficient to pin
 * a behaviour. Recognising stops the log noise.
 */
public class Sub0x06Recognized extends GamePacketDecoderUDP {

    public Sub0x06Recognized(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        // Recognise-only.
    }
}
