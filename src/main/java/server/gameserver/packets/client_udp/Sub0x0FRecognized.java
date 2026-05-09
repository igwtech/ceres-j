package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

/**
 * Recognises the 10-byte {@code UDP S->C 0x0f} sub-packet.
 *
 * <p>Observed in 5 of 17 retail captures (11 hits, all 10 bytes).
 * Layout:
 * <pre>
 *   0f 00 03 [seq_lo seq_hi] 1f 01 00 38 04   (most common)
 *   0f 00 03 [seq_lo seq_hi] 1b 82 00 00 00   (alt — 2 captures)
 * </pre>
 * The 8-of-11 dominant variant is a reliable {@code 0x03→0x1f}
 * GamePacket with sub-tag {@code 0x38} value {@code 0x04}; the
 * remaining 3-of-11 carry reliable type {@code 0x1b} (raw
 * position update) with constant tail {@code 82 00 00 00 00}.
 *
 * <p>Top markers in the corpus include {@code DOOR_IF},
 * {@code ZONING_AREAMC5_COMMANDUNIT}, {@code ZONING_AREAMC5_EXIT}
 * — all door / zone-edge events. The {@code 0x38} sub-tag is a
 * door-state or transition broadcast candidate but not pinned.
 *
 * <p>See {@code docs/protocol/packets/udp_s2c_0f.md}.
 *
 * <p>TODO: act on this — door-state mapping is the most likely
 * payload, pending Ghidra cross-check of the {@code 0x38} sub-tag
 * dispatch.
 */
public class Sub0x0FRecognized extends GamePacketDecoderUDP {

    public Sub0x0FRecognized(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        // Recognise-only.
    }
}
