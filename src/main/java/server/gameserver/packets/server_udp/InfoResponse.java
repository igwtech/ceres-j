package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * InfoResponse (0x03 → 0x23) — session/entity information response.
 *
 * Retail sends two InfoResponse sub-packets in the initial burst:
 *
 * 1. After CharInfo multipart: {@code 20 00 10 00 00 00} (6 bytes)
 *    — appears to be entity/zone info (0x20 = 32, possibly zone-related flag)
 *
 * 2. Mixed into the zone-data packet: {@code 0e 00 00 00 00 00 00 00 01 00} (10 bytes)
 *    — appears to be session info (0x0e = 14 sub-opcode, trailing 01 00)
 *
 * These are observed in retail capture ACC1_CHAR1 at packet #11 (seq 24)
 * and packet #12 (seq 27).
 */
public class InfoResponse extends PacketBuilderUDP1303 {

    /** Zone/entity info variant (6 bytes): 20 00 10 00 00 00 */
    public static InfoResponse zoneInfo(Player pl) {
        return new InfoResponse(pl, new byte[]{0x20, 0x00, 0x10, 0x00, 0x00, 0x00});
    }

    /** Session info variant (10 bytes): 0e 00 00 00 00 00 00 00 01 00 */
    public static InfoResponse sessionInfo(Player pl) {
        return new InfoResponse(pl, new byte[]{0x0e, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00});
    }

    private InfoResponse(Player pl, byte[] payload) {
        super(pl);
        write(0x23);  // reliable sub-type: InfoResponse
        write(payload);
    }
}
