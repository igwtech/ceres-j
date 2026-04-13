package server.gameserver.packets.client_udp;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.WorldNPCInfo;
import server.tools.Out;

/**
 * Handles the client's {@code 0x13 → 0x03 → 0x27} RequestWorldInfo packet.
 *
 * <p>The 4-byte inner payload is the world object id the client saw in
 * a prior {@code 0x1b} raw position broadcast and wants details on.
 * Retail responds with {@code 0x13 → 0x03 → 0x28} WorldInfo; if we
 * don't, the client eventually treats its world-view as stale and
 * re-enters Synchronizing.
 */
public class RequestInfoAboutWordlID extends GamePacketDecoderUDP {

    public RequestInfoAboutWordlID(byte[] subPacket) {
        super(subPacket);
    }

    public void execute(Player pl) {
        skip(4);  // 0x03 + seq(2) + 0x27
        int objectId = readInt();

        if (pl == null || pl.getZone() == null) return;

        NPC npc = pl.getZone().getNPC(objectId);
        if (npc == null) {
            // Our raw 0x1b broadcasts advertise a phantom object id
            // that isn't backed by a real NPC in the zone. Without a
            // lookup hit we cannot build a WorldNPCInfo — sending it
            // with a null NPC NPEs on the first field access.
            // For now just log and drop; the client retries a few
            // times then moves on. Proper fix requires phantom NPCs
            // registered in the Zone or a minimal "no such object"
            // reply packet.
            Out.writeln(Out.Info,
                "RequestWorldInfo for unknown objectId=0x"
                + Integer.toHexString(objectId)
                + " — dropping (no matching NPC in zone)");
            return;
        }
        pl.send(new WorldNPCInfo(pl, npc));
    }
}
