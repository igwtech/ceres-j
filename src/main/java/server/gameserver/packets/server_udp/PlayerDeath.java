package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Sends a death notification to the client.
 *
 * <p>Format: {@code 0x13 → 0x03 → 0x1f} GamePackets with inner
 * sub-opcode {@code 0x16} (Death). The client shows the death screen
 * overlay and disables movement.
 *
 * <p>The death packet carries the killed entity's world ID and the
 * killer's world ID (0 for self/environment kills).
 */
public class PlayerDeath extends PacketBuilderUDP1303 {

    /**
     * @param pl     the player receiving the death notification
     * @param killer the NPC/entity mapId that dealt the killing blow
     *               (0 for self/environment)
     */
    public PlayerDeath(Player pl, int killer) {
        super(pl);
        write(0x1f);                   // GamePackets sub-type
        writeShort(pl.getMapID());     // zone
        write(0x16);                   // Death sub-opcode
        // Retail format (7 bytes after 0x1f header):
        //   [mapId LE2] [0x16] [killer_id LE2] [00] [00]
        writeShort(killer);            // killer entity mapId (2 bytes LE)
        write(0x00);
        write(0x00);
    }

    public PlayerDeath(Player pl) {
        this(pl, 0);
    }
}
