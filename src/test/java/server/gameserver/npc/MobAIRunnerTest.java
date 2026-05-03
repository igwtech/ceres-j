package server.gameserver.npc;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.gameserver.WorldMessageBus;

/**
 * Functional tests for {@link MobAIRunner}: the integration glue
 * that drives {@link MobAI} and pushes results into
 * {@link MobManager}.
 *
 * <p>Each test sets up a synthetic mob population + player
 * population, runs the tick, and asserts: the bus's pending intent
 * count, the {@link MobManager} state-map contents, and the change
 * counter returned by {@code tickAll()}.
 */
public class MobAIRunnerTest {

    static final class FakeMob implements MobAIRunner.MobView {
        final int id;
        final float x, y, z;
        FakeMob(int id, float x, float y, float z) {
            this.id = id; this.x = x; this.y = y; this.z = z;
        }
        @Override public int npcId() { return id; }
        @Override public float posX() { return x; }
        @Override public float posY() { return y; }
        @Override public float posZ() { return z; }
    }

    @Before
    public void setUp() {
        MobManager.resetForTesting();
    }

    @After
    public void tearDown() {
        MobManager.resetForTesting();
    }

    @Test
    public void emptyMobListIsNoOp() {
        WorldMessageBus bus = new WorldMessageBus();
        int n = MobAIRunner.tickAll(bus, 7, Collections.emptyList(),
                Collections.emptyList(), 20f, 30f);
        assertEquals(0, n);
        assertEquals(0, bus.pending(-1));
    }

    @Test
    public void firstTickFromUntrackedMobAggrosOnNearbyPlayer() {
        WorldMessageBus bus = new WorldMessageBus();
        FakeMob m = new FakeMob(0x42, 0f, 0f, 0f);
        MobAI.PlayerSnapshot p = new MobAI.PlayerSnapshot(7, 5f, 0f, 0f);

        int n = MobAIRunner.tickAll(bus, 1, Arrays.asList(m),
                Arrays.asList(p), 20f, 30f);

        assertEquals(1, n);
        assertEquals(1, bus.pending(-1));
        MobManager.Snapshot s = MobManager.getSnapshot(0x42);
        assertNotNull(s);
        assertEquals(MobState.COMBAT, s.state);
        assertEquals(7, s.targetId);
    }

    @Test
    public void firstTickFromUntrackedMobAloneIsNoOp() {
        WorldMessageBus bus = new WorldMessageBus();
        FakeMob m = new FakeMob(0x42, 0f, 0f, 0f);
        // No players → AI computes IDLE (default starting state).
        // Since the previous "tracked" state defaults to IDLE for
        // untracked mobs, the no-change elision in MobManager kicks
        // in and no broadcast is posted.
        int n = MobAIRunner.tickAll(bus, 1, Arrays.asList(m),
                Collections.emptyList(), 20f, 30f);
        // The untracked → IDLE transition counts as "first set" so
        // setState returns true and the change counter is 1.
        // (MobManager has no record yet, snapshot != next.)
        assertEquals(1, n);
        // Subsequent tick with same input should now elide.
        bus.drain(-1);
        int n2 = MobAIRunner.tickAll(bus, 1, Arrays.asList(m),
                Collections.emptyList(), 20f, 30f);
        assertEquals(0, n2);
    }

    @Test
    public void multipleMobsAggroIndependently() {
        WorldMessageBus bus = new WorldMessageBus();
        // Two mobs at different positions; one player near mob1.
        FakeMob m1 = new FakeMob(1, 0f, 0f, 0f);
        FakeMob m2 = new FakeMob(2, 100f, 0f, 0f);
        MobAI.PlayerSnapshot p = new MobAI.PlayerSnapshot(7, 5f, 0f, 0f);

        int n = MobAIRunner.tickAll(bus, 1, Arrays.asList(m1, m2),
                Arrays.asList(p), 20f, 30f);

        assertEquals(2, n); // both mobs transitioned (m1→COMBAT, m2→IDLE)
        assertEquals(MobState.COMBAT, MobManager.getSnapshot(1).state);
        assertEquals(7, MobManager.getSnapshot(1).targetId);
        assertEquals(MobState.IDLE, MobManager.getSnapshot(2).state);
    }

    @Test
    public void unchangedTickElidesBroadcast() {
        WorldMessageBus bus = new WorldMessageBus();
        FakeMob m = new FakeMob(0x42, 0f, 0f, 0f);
        MobAI.PlayerSnapshot p = new MobAI.PlayerSnapshot(7, 5f, 0f, 0f);

        // Tick 1: untracked → COMBAT.
        MobAIRunner.tickAll(bus, 1, Arrays.asList(m),
                Arrays.asList(p), 20f, 30f);
        bus.drain(-1);

        // Tick 2: same input should NOT post another intent.
        int n = MobAIRunner.tickAll(bus, 1, Arrays.asList(m),
                Arrays.asList(p), 20f, 30f);
        assertEquals(0, n);
        assertEquals(0, bus.pending(-1));
    }

    @Test
    public void deaggroSequenceFollowsCombatThenTransitionThenIdle() {
        WorldMessageBus bus = new WorldMessageBus();
        FakeMob m = new FakeMob(0x42, 0f, 0f, 0f);
        MobAI.PlayerSnapshot near = new MobAI.PlayerSnapshot(7, 5f, 0f, 0f);

        // Tick 1: aggro
        MobAIRunner.tickAll(bus, 1, Arrays.asList(m),
                Arrays.asList(near), 20f, 30f);
        assertEquals(MobState.COMBAT, MobManager.getSnapshot(0x42).state);
        bus.drain(-1);

        // Tick 2: player gone → COMBAT becomes TRANSITION
        MobAIRunner.tickAll(bus, 1, Arrays.asList(m),
                Collections.<MobAI.PlayerSnapshot>emptyList(), 20f, 30f);
        assertEquals(MobState.TRANSITION, MobManager.getSnapshot(0x42).state);
        bus.drain(-1);

        // Tick 3: still alone → TRANSITION becomes IDLE
        MobAIRunner.tickAll(bus, 1, Arrays.asList(m),
                Collections.<MobAI.PlayerSnapshot>emptyList(), 20f, 30f);
        assertEquals(MobState.IDLE, MobManager.getSnapshot(0x42).state);

        // Tick 4: alone, idle, no change → no broadcast.
        bus.drain(-1);
        int n = MobAIRunner.tickAll(bus, 1, Arrays.asList(m),
                Collections.<MobAI.PlayerSnapshot>emptyList(), 20f, 30f);
        assertEquals(0, n);
    }

    @Test
    public void targetPersistsAcrossTicksWhenStillInRange() {
        WorldMessageBus bus = new WorldMessageBus();
        FakeMob m = new FakeMob(0x42, 0f, 0f, 0f);
        MobAI.PlayerSnapshot p1 = new MobAI.PlayerSnapshot(7, 5f, 0f, 0f);
        MobAI.PlayerSnapshot p2 = new MobAI.PlayerSnapshot(9, 6f, 0f, 0f);

        // Tick 1: aggros on uid=7 (nearest at 5).
        MobAIRunner.tickAll(bus, 1, Arrays.asList(m),
                Arrays.asList(p1, p2), 20f, 30f);
        assertEquals(7, MobManager.getSnapshot(0x42).targetId);
        bus.drain(-1);

        // Tick 2: uid=9 moves closer (3m), uid=7 still in range.
        // Mob holds the original target — we don't re-target unless
        // current target leaves.
        MobAI.PlayerSnapshot p2closer = new MobAI.PlayerSnapshot(9, 3f, 0f, 0f);
        int n = MobAIRunner.tickAll(bus, 1, Arrays.asList(m),
                Arrays.asList(p1, p2closer), 20f, 30f);
        assertEquals(0, n);
        assertEquals("target should not switch when current still valid",
                7, MobManager.getSnapshot(0x42).targetId);
    }

    @Test
    public void changeCountReflectsActualTransitions() {
        WorldMessageBus bus = new WorldMessageBus();
        // 3 mobs: mob1 will aggro, mob2 stays idle, mob3 too far.
        List<FakeMob> mobs = Arrays.asList(
                new FakeMob(1, 0f, 0f, 0f),
                new FakeMob(2, 50f, 0f, 0f),
                new FakeMob(3, 200f, 0f, 0f));
        MobAI.PlayerSnapshot p = new MobAI.PlayerSnapshot(7, 5f, 0f, 0f);

        // First call: all three are untracked → first-write transitions.
        int n1 = MobAIRunner.tickAll(bus, 1, mobs,
                Arrays.asList(p), 20f, 30f);
        assertEquals(3, n1);
        bus.drain(-1);

        // Second call: only mob1 in COMBAT, others IDLE; same inputs
        // → all elided.
        int n2 = MobAIRunner.tickAll(bus, 1, mobs,
                Arrays.asList(p), 20f, 30f);
        assertEquals(0, n2);
    }

    @Test
    public void nullMobIterableReturnsZero() {
        WorldMessageBus bus = new WorldMessageBus();
        int n = MobAIRunner.tickAll(bus, 1, null, Collections.emptyList(),
                20f, 30f);
        assertEquals(0, n);
    }

    @Test
    public void nullMobInIterableIsSkipped() {
        WorldMessageBus bus = new WorldMessageBus();
        FakeMob m = new FakeMob(1, 0f, 0f, 0f);
        // Iterable with a null entry — runner must skip without NPE.
        int n = MobAIRunner.tickAll(bus, 1, Arrays.asList(m, null),
                Arrays.asList(new MobAI.PlayerSnapshot(7, 5f, 0f, 0f)),
                20f, 30f);
        assertEquals(1, n);
    }
}
