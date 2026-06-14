package server.gameserver.npc;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.WorldMessageBus;
import server.gameserver.Zone;
import server.gameserver.ZoneManager;
import server.gameserver.packets.server_udp.NpcKillCredit;
import server.gameserver.packets.server_udp.RemoveWorldItem;
import server.database.playerCharacters.PlayerCharacter;
import server.tools.Out;

/**
 * Bus consumer for {@link MobDeathIntent}. Removes the dead NPC from
 * its {@link Zone} and fans out the two client death signals: the
 * authoritative {@code 0x03/0x2d} kill-credit gate ({@link NpcKillCredit}
 * — the packet the client waits for before committing the death
 * animation) followed by the {@code 0x03/0x26 RemoveWorldItem} despawn.
 * Loot drop and the XP/kill-credit accounting path land in follow-ups.
 *
 * <p>Test seams mirror the {@link DroneManager} / {@link MobManager}
 * pattern: a {@link ZoneLookup} for finding the mob's zone without
 * booting {@link ZoneManager}, a {@link KillLogger} for capturing kill
 * events without printing to the live {@link Out} log, and
 * {@link DespawnSink} / {@link KillGateSink} for asserting the two
 * client broadcasts without real UDP sends.
 */
public final class MobDeathHandler {

    /** Strategy for resolving zone-id → {@link Zone}. Default uses
     *  {@link ZoneManager#getZone(int)}. */
    @FunctionalInterface
    public interface ZoneLookup {
        Zone find(int zoneId);
    }

    /** Strategy for kill-event logging — default writes to
     *  {@link Out#Info}. Tests inject a list-capturing variant. */
    @FunctionalInterface
    public interface KillLogger {
        void log(MobDeathIntent intent, NPC removedNpc);
    }

    /** Strategy for emitting the RemoveWorldItem broadcast to a
     *  single recipient. Default uses {@link Player#send}. Tests
     *  inject a list-capturing variant to assert fan-out. */
    @FunctionalInterface
    public interface DespawnSink {
        void sendDespawn(Player recipient, int entityId);
    }

    /** Strategy for emitting the {@link NpcKillCredit} death gate
     *  (0x2d) to a single recipient. Default uses {@link Player#send}.
     *  Tests inject a list-capturing variant. */
    @FunctionalInterface
    public interface KillGateSink {
        void sendKillGate(Player recipient, int deadEntityId,
                          int killerEntityId, int dmg);
    }

    private static ZoneLookup    zoneLookup    = MobDeathHandler::defaultZoneLookup;
    private static KillLogger    killLogger    = MobDeathHandler::defaultKillLogger;
    private static DespawnSink   despawnSink   = MobDeathHandler::defaultDespawnSink;
    private static KillGateSink  killGateSink  = MobDeathHandler::defaultKillGateSink;

    private MobDeathHandler() {}

    public static void installBusHandlers(WorldMessageBus bus) {
        bus.registerHandler(MobDeathIntent.class,
                MobDeathHandler::dispatch);
    }

    /** Public for tests: dispatch a single death intent. */
    public static void dispatch(MobDeathIntent intent) {
        if (intent == null) return;
        Zone z = zoneLookup.find(intent.zoneIdField);
        if (z == null) {
            Out.writeln(Out.Warning,
                "MobDeathHandler: no zone " + intent.zoneIdField
                + " for npc " + intent.npcId);
            return;
        }
        NPC removed = z.removeNpc(intent.npcId);
        if (removed == null) {
            Out.writeln(Out.Warning,
                "MobDeathHandler: npc " + intent.npcId
                + " not in zone " + intent.zoneIdField
                + " (already reaped?)");
            return;
        }
        // The authoritative death signal the client waits for is the
        // 0x03/0x2d kill-credit gate (NpcKillCredit). The client
        // predicts the kill from its local HP estimate but only COMMITS
        // the death animation once this arrives; the 0x26 despawn below
        // is the follow-up that retires the model. Resolve the killer's
        // runtime entity id and the credited damage once, then fan both
        // out to every player in the zone.
        int killerEntityId = resolveKillerEntityId(z, intent.killerUid);
        int dmgCredit = NpcKillCredit.clampDmg(removed.getMaxHP());
        for (Player r : z.getAllPlayers()) {
            if (r == null) continue;
            try {
                killGateSink.sendKillGate(r, intent.npcId,
                        killerEntityId, dmgCredit);
            } catch (Exception e) {
                Out.writeln(Out.Error,
                    "MobDeathHandler: kill-gate send failed for "
                    + "npc=" + intent.npcId + ": " + e.getMessage());
            }
        }
        // Broadcast 0x03/0x26 RemoveWorldItem to every player in
        // the zone so their local entity tables despawn the model.
        // Retail emits 253 of these across 10/17 captures, all
        // tightly correlated with KILL_MOB markers — this is the
        // canonical mob-death client signal.
        for (Player r : z.getAllPlayers()) {
            if (r == null) continue;
            try {
                despawnSink.sendDespawn(r, intent.npcId);
            } catch (Exception e) {
                Out.writeln(Out.Error,
                    "MobDeathHandler: despawn send failed for "
                    + "npc=" + intent.npcId + ": " + e.getMessage());
            }
        }
        try {
            killLogger.log(intent, removed);
        } catch (Exception e) {
            Out.writeln(Out.Error,
                "MobDeathHandler: kill logger threw "
                + e.getMessage());
        }
    }

    // ─── Default strategies ─────────────────────────────────────────

    private static Zone defaultZoneLookup(int zoneId) {
        return ZoneManager.getZone(zoneId);
    }

    /**
     * Resolve the killer's runtime entity id ({@code mapID}) — what the
     * client expects in the kill-credit {@code killer_entity} field —
     * from the global {@code killerUid} ({@code MISC_ID}) carried by the
     * intent. Scans the dead mob's zone for the matching player. Falls
     * back to the low 16 bits of {@code killerUid} if the killer isn't
     * found in the zone (e.g. an environment/admin kill or a player who
     * already left), which is harmless for the verified self-kill case.
     */
    private static int resolveKillerEntityId(Zone z, int killerUid) {
        if (z != null) {
            for (Player p : z.getAllPlayers()) {
                if (p == null) continue;
                PlayerCharacter pc = p.getCharacter();
                if (pc != null
                        && pc.getMisc(PlayerCharacter.MISC_ID) == killerUid) {
                    return p.getMapID();
                }
            }
        }
        return killerUid & 0xffff;
    }

    private static void defaultKillLogger(MobDeathIntent intent,
                                           NPC removed) {
        Out.writeln(Out.Info,
            "Mob killed: npc=0x" + Integer.toHexString(intent.npcId)
            + " type=" + (removed == null ? "?" : removed.getType())
            + " killer=0x" + Integer.toHexString(intent.killerUid)
            + " zone=" + intent.zoneIdField);
    }

    private static void defaultDespawnSink(Player recipient, int entityId) {
        recipient.send(new RemoveWorldItem(recipient, entityId));
    }

    private static void defaultKillGateSink(Player recipient,
            int deadEntityId, int killerEntityId, int dmg) {
        recipient.send(new NpcKillCredit(recipient, deadEntityId,
                killerEntityId, dmg));
    }

    // ─── Test seams ─────────────────────────────────────────────────

    public static void setZoneLookupForTesting(ZoneLookup l) {
        zoneLookup = (l == null) ? MobDeathHandler::defaultZoneLookup : l;
    }

    public static void setKillLoggerForTesting(KillLogger k) {
        killLogger = (k == null) ? MobDeathHandler::defaultKillLogger : k;
    }

    public static void setDespawnSinkForTesting(DespawnSink s) {
        despawnSink = (s == null) ? MobDeathHandler::defaultDespawnSink : s;
    }

    public static void setKillGateSinkForTesting(KillGateSink s) {
        killGateSink = (s == null) ? MobDeathHandler::defaultKillGateSink : s;
    }

    public static void resetForTesting() {
        zoneLookup = MobDeathHandler::defaultZoneLookup;
        killLogger = MobDeathHandler::defaultKillLogger;
        despawnSink = MobDeathHandler::defaultDespawnSink;
        killGateSink = MobDeathHandler::defaultKillGateSink;
    }
}
