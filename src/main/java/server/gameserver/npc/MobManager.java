package server.gameserver.npc;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import server.gameserver.Player;
import server.gameserver.WorldMessageBus;
import server.gameserver.Zone;
import server.gameserver.ZoneManager;
import server.tools.Out;

/**
 * In-memory state tracker for mobs / NPCs and the consumer side of
 * {@link MobStateChangeIntent} fan-out.
 *
 * <p>Phase 3 part 3 — closes the AI broadcast loop without yet
 * implementing the full state machine. The actual transitions (idle
 * wander, aggro, combat, death) come in subsequent phases; this
 * class provides the mechanism that those transitions will invoke:
 *
 * <ol>
 *   <li>AI logic decides "mob 0x42 enters combat" and calls
 *       {@link #setState(int, int, MobState, int, float, int)} on
 *       the bus-aware overload, which posts the intent.</li>
 *   <li>Handler runs on the world tick thread and emits one
 *       {@link MobStateBroadcast} per player in the mob's zone.</li>
 * </ol>
 *
 * <p>State is held in a {@link ConcurrentHashMap} so multiple AI
 * sources can mutate without a single-writer constraint — the bus
 * still serialises broadcast emission, so race conditions on the
 * SEND path are impossible.
 */
public final class MobManager {

    /** Strategy for collecting recipients of a state broadcast.
     *  Default uses {@link ZoneManager#getZone(int)}. */
    @FunctionalInterface
    public interface RecipientFinder {
        Collection<Player> recipientsFor(int zoneId);
    }

    /** Strategy for actually emitting a packet to a single recipient.
     *  Default constructs a fresh {@link MobStateBroadcast} bound to
     *  that recipient and calls {@link Player#send(server.interfaces.ServerUDPPacket)}. */
    @FunctionalInterface
    public interface PacketSink {
        void send(Player recipient, MobStateChangeIntent intent);
    }

    /** Snapshot of a mob's last broadcast state. Held in the state
     *  map so unchanged-state writes can be elided. */
    public static final class Snapshot {
        public final MobState state;
        public final int     flagsByte;
        public final float   altitude;
        public final int     targetId;

        Snapshot(MobState s, int f, float alt, int t) {
            this.state    = s;
            this.flagsByte = f;
            this.altitude = alt;
            this.targetId = t;
        }
    }

    private static final Map<Integer, Snapshot> STATES = new ConcurrentHashMap<>();
    private static RecipientFinder recipientFinder = MobManager::defaultRecipients;
    private static PacketSink      packetSink      = MobManager::defaultSend;

    private MobManager() {}

    public static void installBusHandlers(WorldMessageBus bus) {
        bus.registerHandler(MobStateChangeIntent.class,
                MobManager::dispatchStateChange);
    }

    /** Set a mob's state and post a broadcast intent if the state
     *  actually changed. Eliding no-op writes prevents the AI tick
     *  from generating a torrent of identical broadcasts when a mob
     *  sits idle.
     *
     *  @return true if the new state differs from the previous and
     *          a broadcast was posted */
    public static boolean setState(WorldMessageBus bus, int npcId, int zoneId,
                                    MobState state, int flagsByte,
                                    float altitude, int targetId) {
        if (state == null) return false;
        Snapshot prev = STATES.get(npcId);
        Snapshot next = new Snapshot(state, flagsByte, altitude, targetId);
        if (prev != null && snapshotsEqual(prev, next)) {
            return false;
        }
        STATES.put(npcId, next);
        if (bus != null) {
            bus.post(new MobStateChangeIntent(npcId, zoneId, state,
                    flagsByte, altitude, targetId));
        }
        return true;
    }

    /** Force a broadcast even if the state has not changed. Useful
     *  for "I just zoned in, send me everyone's current state"
     *  scenarios. */
    public static void resendCurrent(WorldMessageBus bus, int npcId, int zoneId) {
        Snapshot s = STATES.get(npcId);
        if (s == null || bus == null) return;
        bus.post(new MobStateChangeIntent(npcId, zoneId, s.state,
                s.flagsByte, s.altitude, s.targetId));
    }

    /** Current snapshot for a mob, or null if never set. */
    public static Snapshot getSnapshot(int npcId) {
        return STATES.get(npcId);
    }

    /** Forget a mob's state (despawn / death cleanup). */
    public static Snapshot clear(int npcId) {
        return STATES.remove(npcId);
    }

    /** Number of mobs currently tracked. */
    public static int trackedCount() { return STATES.size(); }

    /** Package-private: dispatch a single intent. Public for tests. */
    public static void dispatchStateChange(MobStateChangeIntent intent) {
        if (intent == null) return;
        for (Player r : recipientFinder.recipientsFor(intent.zoneId)) {
            if (r == null) continue;
            try {
                packetSink.send(r, intent);
            } catch (Exception e) {
                Out.writeln(Out.Error,
                    "MobManager: send failed for npc=" + intent.npcId
                    + ": " + e.getMessage());
            }
        }
    }

    private static boolean snapshotsEqual(Snapshot a, Snapshot b) {
        return a.state == b.state
            && a.flagsByte == b.flagsByte
            && Float.floatToIntBits(a.altitude) == Float.floatToIntBits(b.altitude)
            && a.targetId == b.targetId;
    }

    // ─── Default strategies ─────────────────────────────────────────

    private static Collection<Player> defaultRecipients(int zoneId) {
        Zone z = ZoneManager.getZone(zoneId);
        if (z == null) return Collections.emptyList();
        return z.getAllPlayers();
    }

    private static void defaultSend(Player recipient,
                                     MobStateChangeIntent intent) {
        recipient.send(new MobStateBroadcast(recipient,
                intent.npcId, intent.state, intent.flagsByte,
                intent.altitude, intent.targetId,
                MobStateBroadcast.ZERO_TAIL));
    }

    // ─── Test seams ─────────────────────────────────────────────────

    public static void setRecipientFinderForTesting(RecipientFinder f) {
        recipientFinder = (f == null) ? MobManager::defaultRecipients : f;
    }

    public static void setPacketSinkForTesting(PacketSink s) {
        packetSink = (s == null) ? MobManager::defaultSend : s;
    }

    public static void resetForTesting() {
        recipientFinder = MobManager::defaultRecipients;
        packetSink      = MobManager::defaultSend;
        STATES.clear();
    }
}
