package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * ChatList (0x03 → 0x33) — chat channel list.
 *
 * Retail sends this in the initial burst with payload {@code ff 00}
 * (2 bytes). Observed at packet #12 seq 26 in retail capture ACC1_CHAR1.
 * The {@code 0xff} likely means "all channels" or "default channel list",
 * and {@code 0x00} is a terminator or count.
 */
public class ChatList extends PacketBuilderUDP1303 {

    public ChatList(Player pl) {
        super(pl);
        write(0x33);  // reliable sub-type: ChatList
        write(0xff);
        write(0x00);
    }
}
