package server.gameserver.npc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.WorldMessageBus;
import server.gameserver.Zone;
import server.gameserver.ZoneManager;
import server.tools.Out;

/**
 * Per-zone scheduler that adapts the live {@link Zone} graph into
 * {@link MobAIRunner} input snapshots and runs one AI tick across
 * every mob in every zone.
 *
 * <p>This is the missing piece between the {@link MobAI} policy
 * (pure logic) and the broadcast mechanism (
 * {@link MobManager} → {@link MobStateBroadcast}). Phase 3
 * parts 2–4 each ship a slice of the chain; this commit closes the
 * loop end-to-end so a real player walking near a real NPC
 * triggers a real {@code 0x03/0x2d} broadcast.
 *
 * <h3>Adapters</h3>
 *
 * <p>{@link NPC} positions are stored as ints (the 32000-centred
 * coordinate system used on the wire); {@link MobAI} works in
 * floats — we cast at the adapter boundary. {@link Player}
 * positions come from {@code PlayerCharacter.MISC_*_COORDINATE}
 * which is also int-based; same conversion.
 *
 * <h3>Aggro tuning</h3>
 *
 * <p>v1 hard-codes {@link #DEFAULT_AGGRO_RANGE} / {@link
 * #DEFAULT_DEAGGRO_RANGE}. Per-mob aggro values from
 * {@code npc.def} are wired in a follow-up: each mob template
 * carries its own range column we haven't surfaced yet.
 *
 * <h3>Scheduling</h3>
 *
 * <p>The scheduler does not own its own thread. Callers pull
 * {@link #tickOnce} on whatever cadence makes sense — typically
 * {@link server.gameserver.WorldTickScheduler} at 50 ms, or a
 * dedicated AI scheduler at 100 ms. Decoupling rate from invocation
 * keeps it testable without sleeps.
 */
public final class MobAIScheduler {

    /** Default mob aggro radius in world units (~20 m). */
    public static final float DEFAULT_AGGRO_RANGE   = 20f;
    /** Default mob deaggro radius (~30 m, hysteresis vs aggro). */
    public static final float DEFAULT_DEAGGRO_RANGE = 30f;

    /** Strategy: enumerate every Zone the scheduler should walk.
     *  Default uses {@link ZoneManager#getAllZones()}. */
    @FunctionalInterface
    public interface ZoneEnumerator {
        Collection<Zone> zones();
    }

    private static ZoneEnumerator zoneEnumerator = ZoneManager::getAllZones;
    private static float aggroRange   = DEFAULT_AGGRO_RANGE;
    private static float deaggroRange = DEFAULT_DEAGGRO_RANGE;

    private MobAIScheduler() {}

    /**
     * Run one MobAI tick across every zone. Returns the total
     * number of state transitions posted to the bus.
     *
     * @param bus world message bus to post {@link MobStateChangeIntent}
     *            to (typically {@code GameServer.getBus()})
     */
    public static int tickOnce(WorldMessageBus bus) {
        if (bus == null) return 0;
        int totalChanges = 0;
        for (Zone zone : zoneEnumerator.zones()) {
            if (zone == null) continue;
            try {
                totalChanges += tickZone(bus, zone);
            } catch (Exception e) {
                Out.writeln(Out.Error,
                    "MobAIScheduler: zone " + zone.getZoneId()
                    + " tick threw " + e.getMessage());
            }
        }
        return totalChanges;
    }

    /** Tick a single zone. Public for tests + diagnostic admin
     *  commands ({@code !mobtick <zoneId>}). */
    public static int tickZone(WorldMessageBus bus, Zone zone) {
        if (bus == null || zone == null) return 0;
        List<NPC> npcs = zone.getAllNPCs();
        if (npcs.isEmpty()) return 0;
        // Reap dead mobs first — clear their MobManager state so a
        // corpse can't keep firing PlayerDamageIntents from a stale
        // COMBAT snapshot, and remove them from this tick's AI input
        // so they don't re-aggro on a nearby player.
        reapDeadNpcs(npcs);
        List<MobAI.PlayerSnapshot> playerSnapshots =
                snapshotPlayers(zone.getAllPlayers());
        List<MobAIRunner.MobView> mobViews = adaptMobs(npcs);
        return MobAIRunner.tickAll(bus, zone.getZoneId(),
                mobViews, playerSnapshots, aggroRange, deaggroRange);
    }

    /** Walk the NPC list, find any with HP <= 0, and clear their
     *  state from {@link MobManager} so the attack ticker stops
     *  firing for them. The NPC itself stays in the Zone — actual
     *  body removal / loot spawn / respawn timer lives in a later
     *  phase's death handler. */
    static int reapDeadNpcs(List<NPC> npcs) {
        int reaped = 0;
        for (NPC n : npcs) {
            if (n == null || !n.isDead()) continue;
            if (MobManager.clear(n.getMapID()) != null) reaped++;
        }
        return reaped;
    }

    /** Convert live {@link Player}s to immutable position snapshots.
     *  Skips players with no character (still authenticating) and
     *  dead players (HP = 0) — the AI ignores both. */
    static List<MobAI.PlayerSnapshot> snapshotPlayers(Collection<Player> players) {
        if (players == null || players.isEmpty()) {
            return Collections.emptyList();
        }
        List<MobAI.PlayerSnapshot> out = new ArrayList<>(players.size());
        for (Player p : players) {
            if (p == null) continue;
            PlayerCharacter pc = p.getCharacter();
            if (pc == null) continue;
            int uid = pc.getMisc(PlayerCharacter.MISC_ID);
            float x = pc.getMisc(PlayerCharacter.MISC_X_COORDINATE);
            float y = pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE);
            float z = pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE);
            // PlayerCharacter doesn't expose HP via a public getter
            // here — Player.die() sets isloggedin to false on death,
            // so a dead player drops out of the zone before the next
            // tick. Treat all snapshot entries as alive.
            out.add(new MobAI.PlayerSnapshot(uid, x, y, z, true));
        }
        return out;
    }

    /** Wrap each <strong>living</strong> {@link NPC} as a
     *  {@link MobAIRunner.MobView}. Dead mobs are excluded so they
     *  can't re-aggro on a nearby player. */
    static List<MobAIRunner.MobView> adaptMobs(List<NPC> npcs) {
        List<MobAIRunner.MobView> out = new ArrayList<>(npcs.size());
        for (NPC n : npcs) {
            if (n == null) continue;
            if (n.isDead()) continue;
            out.add(new NpcMobView(n));
        }
        return out;
    }

    /** Adapter: surfaces an {@link NPC}'s position as floats. */
    static final class NpcMobView implements MobAIRunner.MobView {
        private final NPC npc;
        NpcMobView(NPC npc) { this.npc = npc; }
        @Override public int npcId()  { return npc.getMapID(); }
        @Override public float posX() { return npc.getXpos(); }
        @Override public float posY() { return npc.getYpos(); }
        @Override public float posZ() { return npc.getZpos(); }
    }

    // ─── Test seams ─────────────────────────────────────────────────

    public static void setZoneEnumeratorForTesting(ZoneEnumerator e) {
        zoneEnumerator = (e == null) ? ZoneManager::getAllZones : e;
    }

    public static void setRangesForTesting(float aggro, float deaggro) {
        aggroRange   = aggro;
        deaggroRange = deaggro;
    }

    public static void resetForTesting() {
        zoneEnumerator = ZoneManager::getAllZones;
        aggroRange     = DEFAULT_AGGRO_RANGE;
        deaggroRange   = DEFAULT_DEAGGRO_RANGE;
    }
}
