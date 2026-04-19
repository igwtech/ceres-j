package server.gameserver.packets.server_udp;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;

/**
 * Raw {@code 0x32} NPC attribute broadcast.
 *
 * <p>Retail sends 182 per 60-second session (~3/s). Format (9 bytes):
 * {@code 32 [npc_id] [03/05] [04/05] [00 00] [float_lo] [float_mid] [float_hi]}
 *
 * <p>Appears to carry NPC health/status as a float value. The sub-types
 * (0x03/0x04/0x05) may select which attribute. Observed NPC ids in the
 * 0xf8-0xfc range (5 distinct NPCs in the zone).
 */
public class NpcAttributeBroadcast extends PacketBuilderUDP13 {

    public NpcAttributeBroadcast(Player pl, NPC npc) {
        super(pl);

        write(0x32);
        write(npc.getMapID() & 0xFF);  // NPC id low byte
        write(0x03);                   // attribute selector
        write(0x04);                   // sub-selector
        write(0x00);
        write(0x00);
        // NPC HP as float, LE 3 bytes (truncated IEEE 754)
        // Retail uses values like 22.0 (00 b0 41) and 40.5 (00 20 42)
        float hp = (float) npc.getHP();
        int bits = Float.floatToIntBits(hp);
        write(bits & 0xFF);
        write((bits >> 8) & 0xFF);
        write((bits >> 16) & 0xFF);
    }
}
