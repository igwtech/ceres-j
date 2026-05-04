package server.gameserver.npc;

import server.gameserver.NPC;
import server.gameserver.WorldMessageBus;
import server.gameserver.Zone;
import server.gameserver.ZoneManager;
import server.tools.Out;

/**
 * Bus consumer for {@link MobDeathIntent}. v1 just removes the dead
 * NPC instance from its {@link Zone}. Loot drop, the
 * {@code 0x03/0x26 RemoveWorldItem} client packet, and the kill-
 * credit XP path land in follow-up commits.
 *
 * <p>Two test seams mirror the {@link DroneManager} /
 * {@link MobManager} pattern: a {@link ZoneLookup} for finding the
 * mob's zone without booting {@link ZoneManager}, and a
 * {@link KillLogger} for capturing kill events without printing to
 * the live {@link Out} log.
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

    private static ZoneLookup  zoneLookup  = MobDeathHandler::defaultZoneLookup;
    private static KillLogger  killLogger  = MobDeathHandler::defaultKillLogger;

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

    private static void defaultKillLogger(MobDeathIntent intent,
                                           NPC removed) {
        Out.writeln(Out.Info,
            "Mob killed: npc=0x" + Integer.toHexString(intent.npcId)
            + " type=" + (removed == null ? "?" : removed.getType())
            + " killer=0x" + Integer.toHexString(intent.killerUid)
            + " zone=" + intent.zoneIdField);
    }

    // ─── Test seams ─────────────────────────────────────────────────

    public static void setZoneLookupForTesting(ZoneLookup l) {
        zoneLookup = (l == null) ? MobDeathHandler::defaultZoneLookup : l;
    }

    public static void setKillLoggerForTesting(KillLogger k) {
        killLogger = (k == null) ? MobDeathHandler::defaultKillLogger : k;
    }

    public static void resetForTesting() {
        zoneLookup = MobDeathHandler::defaultZoneLookup;
        killLogger = MobDeathHandler::defaultKillLogger;
    }
}
