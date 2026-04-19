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

    public PlayerDeath(Player pl) {
        super(pl);
        write(0x1f);                   // GamePackets sub-type
        writeShort(pl.getMapID());     // zone
        write(0x16);                   // Death sub-opcode
        writeInt(pl.getCharacter().getMisc(
            server.database.playerCharacters.PlayerCharacter.MISC_ID));  // killed entity
        writeInt(0);                   // killer ID (0 = environment/self)
        write(0x00);                   // death type (0 = normal)
    }
}
