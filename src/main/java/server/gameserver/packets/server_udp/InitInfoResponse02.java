package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1302;

/**
 * Session initialization InfoResponse via the {@code 0x02} wrapper.
 *
 * <p>Retail sends {@code 0x02→0x23} with inner payload {@code 0f 00 03 00 01 00}
 * during the initial burst. This appears to be a session/game-mode flag
 * the client checks during initialization. Consistent across all 4 retail
 * captures.
 */
public class InitInfoResponse02 extends PacketBuilderUDP1302 {
    public InitInfoResponse02(Player pl) {
        super(pl);
        write(0x23); // InfoResponse sub-type
        write(new byte[]{0x0f, 0x00, 0x03, 0x00, 0x01, 0x00});
    }
}
