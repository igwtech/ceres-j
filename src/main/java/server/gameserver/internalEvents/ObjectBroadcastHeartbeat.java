package server.gameserver.internalEvents;

import java.util.List;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.packets.server_udp.ObjectPositionBroadcast;
import server.tools.Out;
import server.tools.Timer;

/**
 * Periodic unreliable {@code 0x1b} object-position broadcast.
 *
 * <p>Retail servers emit 122 of these per session at an average rate of
 * 7.7 Hz (ACC1_CHAR1; 75-333 across the other 3 captures). The modern
 * NCE 2.5 client's world-alive watchdog expires after 10-25 seconds
 * without them, forcing the "SYNCHRONIZING INTO CITY ZONE" overlay
 * to re-appear and the session to time out.
 *
 * <p>Iterates zone NPCs round-robin, broadcasting one NPC per tick.
 * With 8 NPCs in plaza_p1 and 150 ms interval, each NPC gets
 * broadcast every ~1.2 s (8 × 150 ms). Uses the unreliable
 * {@code 0x13} wrapper (no {@code 0x03} reliable sub-type) so the
 * broadcast doesn't rubberband the client's local movement prediction.
 */
public class ObjectBroadcastHeartbeat extends DummyEvent {

    public static final long INTERVAL_MS = 150;

    private int npcIndex = 0;

    public ObjectBroadcastHeartbeat() {
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
        if (!npcs.isEmpty()) {
            npcIndex = npcIndex % npcs.size();
            NPC npc = npcs.get(npcIndex);
            npcIndex++;

            try {
                pl.send(new ObjectPositionBroadcast(pl, npc));
            } catch (Exception e) {
                Out.writeln(Out.Error,
                    "ObjectBroadcastHeartbeat: send failed: " + e.getMessage());
            }
        }

        ObjectBroadcastHeartbeat next = new ObjectBroadcastHeartbeat();
        next.npcIndex = this.npcIndex;
        pl.addEvent(next);
    }
}
