package server.gameserver.npc;

import java.util.List;

import server.gameserver.WorldMessageBus;

/**
 * Glue between {@link MobAI} (pure logic) and {@link MobManager}
 * (state map + broadcast). Runs one tick per mob and pushes any
 * resulting state transitions through the bus.
 *
 * <p>Caller responsibilities:
 * <ul>
 *   <li>Provide a snapshot of every mob currently alive in the
 *       relevant zone via the {@link MobView} adapter.</li>
 *   <li>Provide a snapshot of every player in that zone via
 *       {@link MobAI.PlayerSnapshot}.</li>
 *   <li>Choose appropriate {@code aggroRange} / {@code deaggroRange}
 *       (the mob.def aggro values; v1 hard-codes 20 / 30 m).</li>
 * </ul>
 *
 * <p>The runner itself does not iterate the zone graph — that's
 * {@code MobAIScheduler}'s job in a follow-up commit. Keeping the
 * runner pass-by-snapshot makes it trivial to unit-test against
 * synthetic inputs.
 */
public final class MobAIRunner {

    /** Adapter so the runner can read mob state without depending on
     *  any concrete entity class. Implementations come from {@link
     *  server.gameserver.NPC} (for spawn-defined mobs) and the ECS
     *  components (once Phase 6 / 7 wires them). */
    public interface MobView {
        int npcId();
        float posX();
        float posY();
        float posZ();
    }

    private MobAIRunner() {}

    /**
     * Run one AI tick across every mob in {@code mobs}. For each
     * transition, call {@link MobManager#setState} which posts a
     * broadcast intent. The flagsByte is fixed at 0x00 for v1 — the
     * retail evidence shows non-zero values only in combat with
     * specific weapon types we haven't decoded yet, so we leave
     * them untouched until a follow-up.
     *
     * @return number of mobs whose state actually changed (and thus
     *         had a broadcast intent posted)
     */
    public static int tickAll(WorldMessageBus bus, int zoneId,
                                Iterable<? extends MobView> mobs,
                                List<MobAI.PlayerSnapshot> players,
                                float aggroRange, float deaggroRange) {
        if (mobs == null) return 0;
        int changes = 0;
        for (MobView m : mobs) {
            if (m == null) continue;
            MobManager.Snapshot prev = MobManager.getSnapshot(m.npcId());
            MobState curState = (prev == null) ? MobState.IDLE : prev.state;
            int curTarget = (prev == null) ? MobAI.NO_TARGET : prev.targetId;

            MobAI.Input in = new MobAI.Input(m.npcId(),
                    m.posX(), m.posY(), m.posZ(),
                    curState, curTarget, players,
                    aggroRange, deaggroRange);
            MobAI.Result r = MobAI.tick(in);

            // Persist to MobManager regardless — setState() elides
            // unchanged writes itself, so no extra branching needed.
            float altitude = (prev == null) ? m.posZ() : prev.altitude;
            boolean changed = MobManager.setState(bus, m.npcId(), zoneId,
                    r.newState, 0x00, altitude, r.newTargetId);
            if (changed) changes++;
        }
        return changes;
    }
}
