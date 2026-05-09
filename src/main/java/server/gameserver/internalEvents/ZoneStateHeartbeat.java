package server.gameserver.internalEvents;

import java.util.List;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.packets.server_udp.ObjectPositionBroadcast;
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
        } else {
            // No NPCs in the zone — without ANY raw 0x1b broadcast
            // the client's WORLDCLIENT watchdog times out at ~45 s
            // with "Connection to worldserver failed". Fall back to
            // broadcasting the player's own position via a phantom
            // object id (mapId | 0x80 to keep it distinct from the
            // self-mapId; same trick documented in
            // memory/raw_1b_broadcast.md). This satisfies the
            // watchdog while we wait for proper NPC spawning.
            try {
                PlayerCharacter pc = pl.getCharacter();
                if (pc != null) {
                    int phantomId = pl.getMapID() | 0x80;
                    NPC phantom = new NPC(
                            pc.getMisc(PlayerCharacter.MISC_X_COORDINATE),
                            pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE),
                            pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE),
                            100, 0, 0, phantomId);
                    pl.send(new ObjectPositionBroadcast(pl, phantom));
                }
            } catch (Exception e) {
                Out.writeln(Out.Error,
                    "ZoneStateHeartbeat: phantom send failed: "
                    + e.getMessage());
            }
        }

        ZoneStateHeartbeat next = new ZoneStateHeartbeat();
        next.npcIndex = this.npcIndex;
        pl.addEvent(next);
    }
}
