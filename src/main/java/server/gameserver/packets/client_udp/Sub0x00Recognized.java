package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;

/**
 * Recognises the {@code UDP 0x00} sub-packet family (both directions)
 * so the server log doesn't drown in {@code Unknown UDP13 Packet}
 * lines when retail-style traffic is replayed against the parser.
 *
 * <p>The 0x00 outer is the <strong>unreliable channel</strong> —
 * channel-duality per {@code project_opcode_structure} memory:
 * same sub-tag namespace as 0x03 reliable but fire-and-forget
 * (no seq tracking, no retransmit). Body layouts may differ per
 * sub-tag between channels (e.g. 0x00/0x27 uses LE16 world_id
 * vs 0x03/0x27's LE32 — verified 2026-05-10).
 *
 * <h3>S->C: 147 hits / 12 of 17 captures (udp_s2c_00.md)</h3>
 * <pre>
 *   12B (101)  00 3c 01 00 [LE32 id] 00 00 [LE16] 00
 *    6B  (26)  00 2d 00 [byte] 00 00
 *    4B   (6)  00 00 [byte] 00
 *    5B   (4)
 *    9B   (3)
 *   other (≤2 each)
 * </pre>
 *
 * <h3>C->S: 6,338 hits / 17 of 17 captures (udp_c2s_00.md)</h3>
 * <pre>
 *    6B (5,860)  0x00→0x2d unreliable session/NPC state (PvP-dom)
 *   12B (350)   0x00→0x3c entity-action (= udp_c2s_3c equivalent)
 *    5B (60)    0x00→0x27 RequestInfo (subway probe, LE16 world)
 *   15B (15)    0x00→0x55 UNKNOWN (float-pair trailer)
 *  211B (24)   0x00→0x07 unreliable multipart carrier
 *  231B (3)    0x00→0x07 unreliable multipart (large)
 *    1B (23)   bare keepalive ping
 * </pre>
 *
 * <p>Top markers (corpus-wide): {@code OUTSIDE_AREAM5_GENREP_OPEN},
 * {@code ZONING_AREAMC5_*}, {@code MEDBED_DONE}, {@code KILL_MOB4}
 * (S→C) and {@code POKE_START}, {@code FIRE_PVP_2},
 * {@code AIM_PVP}, {@code WHISPER} (C→S — PvP-heavy).
 *
 * <p>See {@code docs/protocol/packets/udp_s2c_00.md} and
 * {@code docs/protocol/packets/udp_c2s_00.md} for the full
 * sub-tag breakdown and verified samples.
 *
 * <p>TODO (P2): per-sub-tag unreliable handlers. The 0x00→0x2d
 * (5,860 samples/PvP) is the highest-impact target — its 6B body
 * matches 0x03/0x2d's NPCData sub-action 0x0a layout
 * (udp_s2c_2d.md). Once implemented, PvP movement smoothness
 * may improve as the server starts reconciling client-side
 * unreliable position deltas.
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
