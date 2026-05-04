package server.gameserver.npc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import server.gameserver.WorldMessageBus;

/**
 * Per-tick consumer that translates {@link MobState#COMBAT} state
 * snapshots into outgoing {@link PlayerDamageIntent}s on a
 * configurable cadence.
 *
 * <p>The Phase 3 chain previously stopped at "mob enters COMBAT
 * with target X" — it never actually inflicted damage on the
 * target. This ticker closes that loop:
 *
 * <ol>
 *   <li>{@link #tickOnce} iterates every mob with a
 *       {@link MobManager.Snapshot} whose state is COMBAT and
 *       whose targetId is a real player UID.</li>
 *   <li>For each, checks "has {@link #attackIntervalMs} elapsed
 *       since this mob's last attack?". If yes, posts a
 *       {@link PlayerDamageIntent} via the bus (consumed by
 *       {@link PlayerDamageDispatcher}) and updates the per-mob
 *       last-attack timestamp.</li>
 * </ol>
 *
 * <p>v1 hardcodes {@link #DEFAULT_ATTACK_INTERVAL_MS} (1500 ms)
 * and {@link #DEFAULT_DAMAGE_PER_HIT} (5). Per-mob stats from
 * {@code npc.def} (attack speed, base damage, range) wire in a
 * follow-up phase. Until then the default lets ops verify the
 * full chain works end-to-end without needing balance data.
 *
 * <p>{@link TimeProvider} is a test seam so tests don't depend
 * on the wall clock.
 */
public final class MobAttackTicker {

    /** Default 1500 ms = ~0.66 attacks/sec — slow enough that a
     *  player with default HP survives long enough to react. */
    public static final long DEFAULT_ATTACK_INTERVAL_MS = 1500L;
    /** Default damage per attack. */
    public static final float DEFAULT_DAMAGE_PER_HIT   = 5f;

    /** Strategy for the current monotonic time in milliseconds. */
    @FunctionalInterface
    public interface TimeProvider {
        long currentMillis();
    }

    /** Per-mob last-attack timestamp in {@link TimeProvider#currentMillis}. */
    private static final Map<Integer, Long> LAST_ATTACK = new ConcurrentHashMap<>();
    private static long  attackIntervalMs = DEFAULT_ATTACK_INTERVAL_MS;
    private static float damagePerHit     = DEFAULT_DAMAGE_PER_HIT;
    private static TimeProvider timeProvider = System::currentTimeMillis;

    private MobAttackTicker() {}

    /**
     * Run the attack tick across every tracked mob.
     *
     * @return number of attacks posted (mobs that fired this tick)
     */
    public static int tickOnce(WorldMessageBus bus) {
        if (bus == null) return 0;
        long now = timeProvider.currentMillis();
        int fired = 0;
        for (Map.Entry<Integer, MobManager.Snapshot> e
                : MobManager.snapshotMap().entrySet()) {
            int npcId = e.getKey();
            MobManager.Snapshot s = e.getValue();
            if (s == null || s.state != MobState.COMBAT) continue;
            if (s.targetId == MobAI.NO_TARGET) continue;
            Long last = LAST_ATTACK.get(npcId);
            if (last != null && (now - last) < attackIntervalMs) continue;

            bus.post(new PlayerDamageIntent(s.targetId, npcId,
                    damagePerHit, 0x0a));
            LAST_ATTACK.put(npcId, now);
            fired++;
        }
        // Forget mobs that have left COMBAT so the map doesn't grow
        // unboundedly.
        LAST_ATTACK.keySet().removeIf(npcId -> {
            MobManager.Snapshot s = MobManager.getSnapshot(npcId);
            return s == null || s.state != MobState.COMBAT;
        });
        return fired;
    }

    // ─── Test seams ────────────────────────────────────────────────

    public static void setAttackIntervalForTesting(long ms) {
        attackIntervalMs = ms;
    }

    public static void setDamagePerHitForTesting(float dmg) {
        damagePerHit = dmg;
    }

    public static void setTimeProviderForTesting(TimeProvider tp) {
        timeProvider = (tp == null) ? System::currentTimeMillis : tp;
    }

    public static void resetForTesting() {
        LAST_ATTACK.clear();
        attackIntervalMs = DEFAULT_ATTACK_INTERVAL_MS;
        damagePerHit     = DEFAULT_DAMAGE_PER_HIT;
        timeProvider     = System::currentTimeMillis;
    }

    /** Visible for tests/diagnostics. */
    public static int trackedMobs() { return LAST_ATTACK.size(); }
}
