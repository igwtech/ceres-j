package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

/**
 * Recognises the {@code UDP S->C 0x00} sub-packet family so the
 * server log doesn't drown in {@code Unknown UDP13 Packet} lines
 * when retail-style traffic is replayed against the parser.
 *
 * <p>Observed in 12 of 17 retail captures (147 total hits). The
 * payload is heterogeneous; size distribution from
 * {@code docs/protocol/_data/packets.json}:
 * <pre>
 *   12B (101)  00 3c 01 00 [LE32 id] 00 00 [LE16] 00
 *    6B  (26)  00 2d 00 [byte] 00 00
 *    4B   (6)  00 00 [byte] 00
 *    5B   (4)
 *    9B   (3)
 *   other (≤2 each)
 * </pre>
 *
 * <p>Top markers in the corpus: {@code OUTSIDE_AREAM5_GENREP_OPEN},
 * {@code ZONING_AREAMC5_*}, {@code MEDBED_DONE}, {@code KILL_MOB4}.
 * The {@code 0x00 0x3c} variant looks like a state-change broadcast
 * (zoning / genrep / medbed all touch the player's location) but
 * the byte-level layout is not yet pinned. The {@code 0x00 0x2d}
 * variant aligns with NPC-data sub-tags.
 *
 * <p>See {@code docs/protocol/packets/udp_s2c_00.md} for the
 * cross-capture evidence and structure-derivation TODO.
 *
 * <p>TODO: act on the payload — until structure is verified across
 * ≥3 captures per variant we just recognise the bytes.
 */
public class Sub0x00Recognized extends GamePacketDecoderUDP {

    public Sub0x00Recognized(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        // Recognised, no action. The structure is well-evidenced
        // (see catalog) but heterogeneous; per-variant decoders
        // land in follow-up commits.
    }
}
