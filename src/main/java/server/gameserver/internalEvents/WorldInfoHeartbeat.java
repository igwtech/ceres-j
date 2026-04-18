package server.gameserver.internalEvents;

import java.util.List;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.packets.server_udp.WorldNPCInfo;
import server.tools.Out;
import server.tools.Timer;

/**
 * Periodic reliable {@code 0x03 -> 0x28} WorldInfo broadcast.
 *
 * <p>Retail sends 36 per session (ACC1_CHAR1) as replies to the
 * client's {@code 0x03 -> 0x27 RequestWorldInfo} queries. But the
 * client only queries objects it already knows about from raw
 * {@code 0x1b} broadcasts — so proactively streaming WorldInfo for
 * every zone NPC avoids the query-then-reply round-trip and keeps the
 * client's world model populated from the start.
 *
 * <p>Round-robins through zone NPCs at 500 ms intervals (~2 Hz),
 * similar to the observed retail rate of 36 over ~16 s.
 */
public class WorldInfoHeartbeat extends DummyEvent {

    public static final long INTERVAL_MS = 500;

    private int npcIndex = 0;

    public WorldInfoHeartbeat() {
        this.eventTime = Timer.getRealtime() + INTERVAL_MS;
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || !pl.isloggedin() || pl.getUdpConnection() == null) {
            return;
        }

        Zone zone = pl.getZone();
        if (zone == null) return;

        List<NPC> npcs = zone.getAllNPCs();
        if (npcs.isEmpty()) {
            pl.addEvent(new WorldInfoHeartbeat());
            return;
        }

        npcIndex = npcIndex % npcs.size();
        NPC npc = npcs.get(npcIndex);
        npcIndex++;

        try {
            pl.send(new WorldNPCInfo(pl, npc));
        } catch (Exception e) {
            Out.writeln(Out.Error,
                "WorldInfoHeartbeat: send failed: " + e.getMessage());
        }

        WorldInfoHeartbeat next = new WorldInfoHeartbeat();
        next.npcIndex = this.npcIndex;
        pl.addEvent(next);
    }
}
