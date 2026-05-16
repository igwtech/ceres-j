package server.gameserver.internalEvents;

import java.util.List;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.packets.server_udp.ZoneStateCompoundPacket;
import server.tools.Out;
import server.tools.Timer;

/**
 * Single compound heartbeat that sends one {@link ZoneStateCompoundPacket}
 * per tick, containing a raw 0x1b position broadcast + reliable 0x2d
 * NPCData + reliable 0x28 WorldInfo for one NPC. Round-robins through
 * all zone NPCs.
 *
 * <p>Replaces the three separate heartbeats (ObjectBroadcastHeartbeat,
 * NpcDataHeartbeat, WorldInfoHeartbeat) which collectively ran at 12 Hz
 * and flooded the client's reliable queue. This runs at 2 Hz (500 ms
 * interval) which, with 8 NPCs in plaza_p1, refreshes each NPC every
 * 4 seconds — matching the effective rate observed in retail captures
 * (52 NPCData + 36 WorldInfo + 122 raw 0x1b over ~16 seconds).
 */
public class ZoneStateHeartbeat extends DummyEvent {

    public static final long INTERVAL_MS = 500;

    private int npcIndex = 0;

    public ZoneStateHeartbeat() {
        this.eventTime = Timer.getRealtime() + INTERVAL_MS;
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || !pl.isloggedin() || pl.getUdpConnection() == null) {
            return;
        }

        Zone zone = pl.getZone();
        if (zone == null) return;

        // Silence NPC zone-state (0x1b broadcast + 0x2d NPCData +
        // 0x28 WorldInfo) while a zone-cross is in flight
        // (pendingZoneId set by Zoning1, cleared by Zoning2 /
        // WorldEntry). localhost-vs-retail byte-diff 2026-05-16:
        // in the 0.7 s after Zoning1 retail sends ONLY TimeSync
        // (03/0x1f) + a UDPAlive — ZERO NPC packets. Ceres flooded
        // 1b/2d/28 mid-cross; that old-zone NPC burst is what the
        // client never sees in retail and is the remaining
        // divergence blocking the plaza_p1 → plaza_p3 cross. Keep
        // rescheduling so NPC refresh resumes once the cross
        // commits.
        if (pl.getPendingZoneId() != 0) {
            ZoneStateHeartbeat resume = new ZoneStateHeartbeat();
            resume.npcIndex = this.npcIndex;
            pl.addEvent(resume);
            return;
        }

        List<NPC> npcs = zone.getAllNPCs();
        if (!npcs.isEmpty()) {
            npcIndex = npcIndex % npcs.size();
            NPC npc = npcs.get(npcIndex);
            npcIndex++;

            try {
                pl.send(new ZoneStateCompoundPacket(pl, npc));
            } catch (Exception e) {
                Out.writeln(Out.Error,
                    "ZoneStateHeartbeat: send failed: " + e.getMessage());
            }
        }
        // No-NPCs branch: nothing to send. A previous attempt
        // broadcast the player's own position with a phantom
        // object id (mapId|0x80) to keep the WORLDCLIENT
        // watchdog alive — but the modern client renders that
        // as a duplicate ghost-self standing a few meters
        // away. Other heartbeats (TimeSync, PoolStatus, CPing
        // reply) are sufficient to keep the connection alive.

        ZoneStateHeartbeat next = new ZoneStateHeartbeat();
        next.npcIndex = this.npcIndex;
        pl.addEvent(next);
    }
}
