package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * The periodic server-&gt;client "GamePackets TimeSync" heartbeat.
 *
 * <p>Wrapped as {@code 0x13 -> 0x03 (reliable) -> 0x1f GamePackets}.
 * Retail sends one of these every ~1&nbsp;s throughout the session (37–65
 * per session observed across 4 retail captures of ACC1/ACC2 × CHAR1/CHAR2,
 * see {@code docs/retail_burst_analysis.md}). The modern NCE&nbsp;2.5 client
 * appears to gate the "SYNCHRONIZING INTO CITY ZONE" overlay on continuous
 * receipt of this heartbeat after world entry: without it, the client stays
 * in sync state and eventually aborts the session.
 *
 * <p>Inner payload (5 bytes, after the 0x1f sub-type):
 * <pre>
 *   [0]     0x01 or 0x02   opcode variant (retail observed both)
 *   [1]     0x00           reserved / subcode
 *   [2..3]  0x25 0x23      constant 2-byte tag (same across every retail
 *                          capture — likely a GamePackets "TimeSync"
 *                          inner opcode id of 0x2325)
 *   [4]     zoneTag        per-session / per-zone byte
 *                          (0x40, 0x33, 0x30, 0x18 observed in the 4
 *                          captures — correlates with the zone the
 *                          character was logged into)
 * </pre>
 *
 * <p>The exact meaning of the bytes is unknown, but the payload is
 * constant for a given session in retail, so we reproduce the ACC1_CHAR1
 * pattern {@code 1f 01 00 25 23 <mapId&0xFF>}. If this turns out to be
 * incorrect the 5 bytes are easy to retarget — the important thing is
 * that the sub-type (0x1f) and the overall packet framing match retail.
 */
public class GamePacketTimeSync extends PacketBuilderUDP1303 {

    /** Inner GamePackets sub-type (0x1f) — fixed by the retail protocol. */
    public static final int REL_GAMEPACKETS = 0x1f;

    public GamePacketTimeSync(Player pl) {
        super(pl);
        write(REL_GAMEPACKETS);
        write(0x01);                      // opcode variant
        write(0x00);                      // reserved
        write(0x25);                      // constant tag byte 1
        write(0x23);                      // constant tag byte 2
        write(pl.getMapID() & 0xFF);      // zone-dependent tag byte
    }
}
