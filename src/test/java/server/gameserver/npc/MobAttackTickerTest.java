package server.gameserver.npc;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.gameserver.WorldMessageBus;

/**
 * Functional tests for {@link MobAttackTicker}: the consumer that
 * turns persistent COMBAT state into outgoing
 * {@link PlayerDamageIntent}s on a configurable cadence.
 */
public class MobAttackTickerTest {

    private List<PlayerDamageIntent> damageIntents;
    private AtomicLong fakeClock;

    @Before
    public void setUp() {
        MobManager.resetForTesting();
        MobAttackTicker.resetForTesting();
        damageIntents = new ArrayList<>();
        fakeClock = new AtomicLong(0L);
        MobAttackTicker.setTimeProviderForTesting(fakeClock::get);
    }

    @After
    public void tearDown() {
        MobManager.resetForTesting();
        MobAttackTicker.resetForTesting();
    }

    /** Bus that captures any PlayerDamageIntent posted to it. */
    private WorldMessageBus newCapturingBus() {
        WorldMessageBus bus = new WorldMessageBus();
        bus.registerHandler(PlayerDamageIntent.class,
                i -> damageIntents.add((PlayerDamageIntent) i));
        return bus;
    }

    // ─── Basic firing semantics ────────────────────────────────────

    @Test
    public void mobInCombatFiresOnFirstTick() {
        WorldMessageBus bus = newCapturingBus();
        MobManager.setState(bus, 0x42, 0, MobState.COMBAT, 0, 0f, 0x999);
        bus.drain(-1);

        int n = MobAttackTicker.tickOnce(bus);
        assertEquals(1, n);
        bus.drain(-1);

        assertEquals(1, damageIntents.size());
        PlayerDamageIntent intent = damageIntents.get(0);
        assertEquals(0x999, intent.victimUid);
        assertEquals(0x42, intent.attackerId);
        assertEquals(MobAttackTicker.DEFAULT_DAMAGE_PER_HIT,
                intent.amount, 0.001f);
    }

    @Test
    public void idleMobDoesNotFire() {
        WorldMessageBus bus = newCapturingBus();
        MobManager.setState(bus, 0x42, 0, MobState.IDLE, 0, 0f,
                MobAI.NO_TARGET);
        bus.drain(-1);

        int n = MobAttackTicker.tickOnce(bus);
        assertEquals(0, n);
        bus.drain(-1);
        assertTrue(damageIntents.isEmpty());
    }

    @Test
    public void combatWithNoTargetDoesNotFire() {
        WorldMessageBus bus = newCapturingBus();
        // Combat state but no target — shouldn't happen in normal AI
        // flow but the ticker must reject defensively.
        MobManager.setState(bus, 0x42, 0, MobState.COMBAT, 0, 0f,
                MobAI.NO_TARGET);
        bus.drain(-1);

        int n = MobAttackTicker.tickOnce(bus);
        assertEquals(0, n);
    }

    @Test
    public void transitionMobDoesNotFire() {
        WorldMessageBus bus = newCapturingBus();
        MobManager.setState(bus, 0x42, 0, MobState.TRANSITION, 0, 0f, 0x999);
        bus.drain(-1);

        int n = MobAttackTicker.tickOnce(bus);
        assertEquals(0, n);
    }

    // ─── Cooldown ─────────────────────────────────────────────────

    @Test
    public void secondTickWithinIntervalIsSuppressed() {
        WorldMessageBus bus = newCapturingBus();
        MobManager.setState(bus, 0x42, 0, MobState.COMBAT, 0, 0f, 0x999);
        bus.drain(-1);

        // First tick at t=0 — fires.
        MobAttackTicker.tickOnce(bus);
        bus.drain(-1);
        assertEquals(1, damageIntents.size());

        // Advance 100ms (well below the 1500ms default interval).
        fakeClock.set(100L);
        int n2 = MobAttackTicker.tickOnce(bus);
        bus.drain(-1);
        assertEquals(0, n2);
        assertEquals("still 1, no second hit", 1, damageIntents.size());
    }

    @Test
    public void tickAfterIntervalFiresAgain() {
        WorldMessageBus bus = newCapturingBus();
        MobAttackTicker.setAttackIntervalForTesting(1000L);
        MobManager.setState(bus, 0x42, 0, MobState.COMBAT, 0, 0f, 0x999);
        bus.drain(-1);

        // t=0: fires.
        MobAttackTicker.tickOnce(bus);
        bus.drain(-1);
        // t=1000: interval elapsed, fires again.
        fakeClock.set(1000L);
        int n = MobAttackTicker.tickOnce(bus);
        bus.drain(-1);
        assertEquals(1, n);
        assertEquals(2, damageIntents.size());
    }

    @Test
    public void cooldownIsPerMobNotGlobal() {
        WorldMessageBus bus = newCapturingBus();
        MobManager.setState(bus, 0x10, 0, MobState.COMBAT, 0, 0f, 0x999);
        bus.drain(-1);

        // First mob fires at t=0.
        MobAttackTicker.tickOnce(bus);
        bus.drain(-1);
        assertEquals(1, damageIntents.size());

        // Second mob enters combat at t=100 — its first attack
        // should NOT be blocked by the first mob's cooldown.
        fakeClock.set(100L);
        MobManager.setState(bus, 0x20, 0, MobState.COMBAT, 0, 0f, 0x999);
        bus.drain(-1);

        int n = MobAttackTicker.tickOnce(bus);
        bus.drain(-1);
        assertEquals("second mob fires on its own first tick", 1, n);
        assertEquals(2, damageIntents.size());
    }

    // ─── Damage / interval overrides ───────────────────────────────

    @Test
    public void damageOverrideIsHonored() {
        WorldMessageBus bus = newCapturingBus();
        MobAttackTicker.setDamagePerHitForTesting(99f);
        MobManager.setState(bus, 0x42, 0, MobState.COMBAT, 0, 0f, 0x999);
        bus.drain(-1);

        MobAttackTicker.tickOnce(bus);
        bus.drain(-1);
        assertEquals(99f, damageIntents.get(0).amount, 0.001f);
    }

    @Test
    public void zeroIntervalFiresEveryTick() {
        WorldMessageBus bus = newCapturingBus();
        MobAttackTicker.setAttackIntervalForTesting(0L);
        MobManager.setState(bus, 0x42, 0, MobState.COMBAT, 0, 0f, 0x999);
        bus.drain(-1);

        // Each tick advances clock by 1ms; with 0ms interval, every
        // tick fires.
        for (int i = 0; i < 5; i++) {
            fakeClock.set(i);
            MobAttackTicker.tickOnce(bus);
        }
        bus.drain(-1);
        assertEquals(5, damageIntents.size());
    }

    // ─── State map cleanup ─────────────────────────────────────────

    @Test
    public void mobLeavingCombatIsForgotten() {
        WorldMessageBus bus = newCapturingBus();
        MobManager.setState(bus, 0x42, 0, MobState.COMBAT, 0, 0f, 0x999);
        bus.drain(-1);

        MobAttackTicker.tickOnce(bus);
        assertEquals(1, MobAttackTicker.trackedMobs());

        // Mob deaggros → IDLE; next tick should drop it from the
        // last-attack map.
        MobManager.setState(bus, 0x42, 0, MobState.IDLE, 0, 0f,
                MobAI.NO_TARGET);
        bus.drain(-1);

        MobAttackTicker.tickOnce(bus);
        assertEquals("forgotten on next tick after leaving COMBAT",
                0, MobAttackTicker.trackedMobs());
    }

    @Test
    public void mobClearedFromMobManagerIsForgotten() {
        WorldMessageBus bus = newCapturingBus();
        MobManager.setState(bus, 0x42, 0, MobState.COMBAT, 0, 0f, 0x999);
        bus.drain(-1);
        MobAttackTicker.tickOnce(bus);
        assertEquals(1, MobAttackTicker.trackedMobs());

        // Mob fully removed (e.g. despawned).
        MobManager.clear(0x42);
        MobAttackTicker.tickOnce(bus);
        assertEquals(0, MobAttackTicker.trackedMobs());
    }

    // ─── Edge cases ────────────────────────────────────────────────

    @Test
    public void nullBusReturnsZero() {
        assertEquals(0, MobAttackTicker.tickOnce(null));
    }

    @Test
    public void emptyMobMapIsNoOp() {
        WorldMessageBus bus = newCapturingBus();
        int n = MobAttackTicker.tickOnce(bus);
        assertEquals(0, n);
    }

    @Test
    public void targetReassignmentResetsCooldown() {
        // The cooldown is per-mob, not per (mob, target). When a mob
        // switches target mid-combat, the next attack still has to
        // wait for the cooldown — this is intentional v1 behaviour
        // (otherwise hot-swap targets would be a damage exploit).
        WorldMessageBus bus = newCapturingBus();
        MobManager.setState(bus, 0x42, 0, MobState.COMBAT, 0, 0f, 0xAAA);
        bus.drain(-1);
        MobAttackTicker.tickOnce(bus);
        bus.drain(-1);
        assertEquals(1, damageIntents.size());

        // Switch target at t=100ms (well below the 1500ms default).
        fakeClock.set(100L);
        MobManager.setState(bus, 0x42, 0, MobState.COMBAT, 0, 0f, 0xBBB);
        bus.drain(-1);
        int n = MobAttackTicker.tickOnce(bus);
        bus.drain(-1);
        assertEquals("cooldown still in effect", 0, n);
    }
}
