package server.gameserver.packets.server_udp;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Reliable {@code 0x13 -> 0x03 -> 0x2d} NPC data broadcast.
 *
 * <p>Retail sends 52 of these per session at ~3.3 Hz (ACC1_CHAR1).
 * Each one carries the current state of a single NPC — type, position,
 * HP, armor. The client uses these to populate/refresh its local NPC
 * table; without them the world feels "dead" and the client's
 * world-alive watchdog eventually fires.
 *
 * <p>Wire format (from retail samples, 9-byte and 54-byte variants):
 * <pre>
 *   Short variant (9B inner after 0x2d):
 *     [mapID_lo][mapID_hi] [0x00] [0x08] [0x00 0x00 0x00 0x00]
 *
 *   Long variant (54B inner after 0x2d):
 *     [npc_counter] [0x01 0x00 0x00] [type LE4] [0xFF 0xFF 0xFF 0xFF]
 *     [pos data...] [model data...]
 * </pre>
 *
 * <p>We use the short variant for the heartbeat — it's enough to keep
 * the client's NPC table refreshed without requiring the full model
 * data. The long variant is used during initial zone population (see
 * {@link WorldNPCInfo}).
 */
public class NpcDataBroadcast extends PacketBuilderUDP1303 {

    public NpcDataBroadcast(Player pl, NPC npc) {
        super(pl);
        write(0x2d);                          // reliable sub-type
        writeShort(npc.getMapID());           // NPC world-object id
        write(0x00);                          // padding
        write(0x08);                          // sub-block marker
        write(0x00);                          // status
        write(0x00);
        write(0x00);
        write(0x00);
    }
}
