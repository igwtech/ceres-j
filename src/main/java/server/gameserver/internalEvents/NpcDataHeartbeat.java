package server.gameserver.internalEvents;

import java.util.List;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.packets.server_udp.NpcDataBroadcast;
import server.tools.Out;
import server.tools.Timer;

/**
 * Periodic reliable {@code 0x03 -> 0x2d} NPC data broadcast.
 *
 * <p>Retail sends 52 per session at ~3.3 Hz (ACC1_CHAR1). Each tick
 * picks the next NPC in the zone and sends its data to the player.
 * Round-robins through all zone NPCs so every NPC gets refreshed
 * over time.
 */
public class NpcDataHeartbeat extends DummyEvent {

    public static final long INTERVAL_MS = 300;

    private int npcIndex = 0;

    public NpcDataHeartbeat() {
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
            pl.addEvent(new NpcDataHeartbeat());
            return;
        }

        // Round-robin: send one NPC per tick
        npcIndex = npcIndex % npcs.size();
        NPC npc = npcs.get(npcIndex);
        npcIndex++;

        try {
            pl.send(new NpcDataBroadcast(pl, npc));
        } catch (Exception e) {
            Out.writeln(Out.Error,
                "NpcDataHeartbeat: send failed: " + e.getMessage());
        }

        // Self-reschedule — new instance so the event queue stays sorted
        NpcDataHeartbeat next = new NpcDataHeartbeat();
        next.npcIndex = this.npcIndex;
        pl.addEvent(next);
    }
}
