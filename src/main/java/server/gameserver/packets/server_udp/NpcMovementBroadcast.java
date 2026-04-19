package server.gameserver.packets.server_udp;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

/**
 * Raw {@code 0x20} NPC movement broadcast (S→C).
 *
 * <p>Retail sends 78 per 60-second session (~1.3/s). Same format as the
 * client's C→S movement packet — the server echoes NPC positions using
 * the same sub-type. 13 bytes:
 * {@code 20 [type] [mapId_lo] [Y LE2] [Z LE2] [X LE2] [orient] [status] [00]}
 *
 * <p>Type flags: 0x01 = Y update, 0x21 = Y + status update.
 */
public class NpcMovementBroadcast extends PacketBuilderUDP13 {

    public NpcMovementBroadcast(Player pl, NPC npc) {
        super(pl);

        write(0x20);
        write(0x21);  // type flags: Y + status (most common in retail)
        write(npc.getMapID() & 0xFF);  // mapId low byte

        int y = (npc.getYpos() + 32000) & 0xFFFF;
        int z = (npc.getZpos() + 32000) & 0xFFFF;
        int x = (npc.getXpos() + 32000) & 0xFFFF;

        writeShort(y);
        writeShort(z);
        writeShort(x);

        int angle = npc.getAngle() & 0xFF;
        write(angle == 0 ? 0x40 : angle);  // orient
        write(0x00);  // status
        write(0x00);  // padding
    }
}
