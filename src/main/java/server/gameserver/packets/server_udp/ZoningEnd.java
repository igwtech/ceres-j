package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * ZoningEnd (0x13 -> 0x03 -> 0x08) is sent after the server has finished
 * streaming the initial world state to a client. The modern NCE 2.5 client
 * uses this as a signal to leave the post-login loading screen and render the
 * world.
 *
 * Layout (reliable sub-packet payload):
 * <pre>
 *   byte    0x08              reliable sub-type (ZoningEnd)
 *   short   mapId             player's map-id in zone
 *   byte    status            0x00 = ok
 * </pre>
 *
 * Observed in retail pcaps as the terminator of the post-handshake burst of
 * world-entry packets.
 */
public class ZoningEnd extends PacketBuilderUDP1303 {

    public ZoningEnd(Player pl) {
        super(pl);
        write(0x08);
        writeShort(pl.getMapID());
        write(0x00);
    }
}
