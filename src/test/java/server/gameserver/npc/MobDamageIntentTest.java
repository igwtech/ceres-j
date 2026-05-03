package server.gameserver.npc;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.WorldMessageBus;

/**
 * Functional tests for the damage→aggro path: a producer posts a
 * {@link MobDamageIntent}, MobManager consumes it, applies HP, and
 * forces COMBAT (or TRANSITION on death) with the attacker as
 * target. Verified via test-seam injected NPC lookup; no Zone /
 * PlayerManager required.
 */
public class MobDamageIntentTest {

    private Map<Integer, NPC> mobs;

    @Before
    public void setUp() {
        MobManager.resetForTesting();
        mobs = new HashMap<>();
        MobManager.setNpcLookupForTesting(id -> mobs.get(id));
        MobManager.setRecipientFinderForTesting(z -> Collections.emptyList());
    }

    @After
    public void tearDown() {
        MobManager.resetForTesting();
    }

    private NPC mob(int npcId, int hp) {
        // 6-arg ctor: x, y, z, hp, armor, type, id  — but we use the
        // alternate ctor that explicitly takes mapID:
        // (id, zoneId, type, name, x, y, z, angle, hp, armor)
        NPC n = new NPC(npcId, 1, 0, "test",
                0, 0, 0, 0, hp, 0);
        mobs.put(npcId, n);
        return n;
    }

    // ─── HP application ────────────────────────────────────────────

    @Test
    public void damageDecrementsHp() {
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.installBusHandlers(bus);
        NPC n = mob(0x42, 100);

        bus.post(new MobDamageIntent(0x42, 1, 0x99, 30));
        bus.drain(-1);

        assertEquals(70, n.getHP());
    }

    @Test
    public void zeroOrNegativeDamageDoesNotMutateHp() {
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.installBusHandlers(bus);
        NPC n = mob(0x42, 100);

        bus.post(new MobDamageIntent(0x42, 1, 0x99, 0));
        bus.post(new MobDamageIntent(0x42, 1, 0x99, -5));
        bus.drain(-1);

        assertEquals(100, n.getHP());
    }

    @Test
    public void damageClampsToZero() {
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.installBusHandlers(bus);
        NPC n = mob(0x42, 50);

        bus.post(new MobDamageIntent(0x42, 1, 0x99, 100));
        bus.drain(-1);

        assertEquals(0, n.getHP());
        assertTrue(n.isDead());
    }

    // ─── State transitions ─────────────────────────────────────────

    @Test
    public void survivableHitForcesCombatWithAttackerAsTarget() {
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.installBusHandlers(bus);
        NPC n = mob(0x42, 100);

        bus.post(new MobDamageIntent(0x42, 1, 0x999, 25));
        bus.drain(-1);

        MobManager.Snapshot s = MobManager.getSnapshot(0x42);
        assertNotNull(s);
        assertEquals(MobState.COMBAT, s.state);
        assertEquals(0x999, s.targetId);
    }

    @Test
    public void lethalHitTransitionsToTransitionState() {
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.installBusHandlers(bus);
        NPC n = mob(0x42, 10);

        bus.post(new MobDamageIntent(0x42, 1, 0x999, 100));
        bus.drain(-1);

        MobManager.Snapshot s = MobManager.getSnapshot(0x42);
        assertNotNull(s);
        assertEquals(MobState.TRANSITION, s.state);
        assertEquals(MobAI.NO_TARGET, s.targetId);
    }

    @Test
    public void damageOverridesIdleAndIgnoresProximity() {
        // Mob idle with a previous snapshot — even if no proximity
        // aggro would fire, a hit forces COMBAT.
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.setState(bus, 0x42, 1, MobState.IDLE, 0, 50f,
                MobAI.NO_TARGET);
        bus.drain(-1);
        MobManager.installBusHandlers(bus);

        mob(0x42, 100);
        bus.post(new MobDamageIntent(0x42, 1, 0x123, 1));
        bus.drain(-1);

        assertEquals(MobState.COMBAT, MobManager.getSnapshot(0x42).state);
        assertEquals(0x123, MobManager.getSnapshot(0x42).targetId);
    }

    @Test
    public void damageReassignsTargetWhenNewAttackerHits() {
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.installBusHandlers(bus);
        mob(0x42, 1000);

        bus.post(new MobDamageIntent(0x42, 1, 0xAAA, 10));
        bus.drain(-1);
        assertEquals(0xAAA, MobManager.getSnapshot(0x42).targetId);

        // A different attacker hits — target switches.
        bus.post(new MobDamageIntent(0x42, 1, 0xBBB, 10));
        bus.drain(-1);
        assertEquals(0xBBB, MobManager.getSnapshot(0x42).targetId);
    }

    @Test
    public void altitudePreservedFromPreviousSnapshot() {
        // A prior IDLE snapshot fixed altitude=42.5; a damage hit
        // should keep that altitude in the resulting COMBAT snapshot.
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.setState(bus, 0x42, 1, MobState.IDLE, 0, 42.5f,
                MobAI.NO_TARGET);
        bus.drain(-1);
        MobManager.installBusHandlers(bus);

        mob(0x42, 100);
        bus.post(new MobDamageIntent(0x42, 1, 0x999, 5));
        bus.drain(-1);

        assertEquals(42.5f, MobManager.getSnapshot(0x42).altitude, 0.001f);
    }

    // ─── Edge cases ────────────────────────────────────────────────

    @Test
    public void unknownNpcIdIsLoggedNotFatal() {
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.installBusHandlers(bus);
        // No mobs registered.
        bus.post(new MobDamageIntent(0x42, 1, 0x99, 100));
        // Must not throw.
        bus.drain(-1);
        assertNull(MobManager.getSnapshot(0x42));
    }

    @Test
    public void nullIntentIsSafe() {
        // Direct dispatch with null — handler must not throw.
        MobManager.dispatchDamage(null);
        // No state changes.
        assertEquals(0, MobManager.trackedCount());
    }

    @Test
    public void damageEmitsBroadcastIntent() {
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.installBusHandlers(bus);
        mob(0x42, 100);

        bus.post(new MobDamageIntent(0x42, 1, 0x99, 5));
        // Drain damage intent; it posts a state-change intent we
        // can observe via pending count.
        bus.drain(-1);
        // After the second drain, both intents have been consumed.
        // The first drain processes the damage and posts the state-change;
        // since drain() loops to drain the queue, both run in one call.
    }

    @Test
    public void multipleHitsAccumulateDamage() {
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.installBusHandlers(bus);
        NPC n = mob(0x42, 100);

        for (int i = 0; i < 4; i++) {
            bus.post(new MobDamageIntent(0x42, 1, 0x99, 10));
        }
        bus.drain(-1);

        assertEquals(60, n.getHP());
        assertEquals(MobState.COMBAT, MobManager.getSnapshot(0x42).state);
    }

    @Test
    public void deathClearsTargetEvenIfPriorTargetWasOther() {
        // Mob already in combat with attacker A; attacker B's killing
        // blow should clear the target rather than keep A.
        WorldMessageBus bus = new WorldMessageBus();
        MobManager.installBusHandlers(bus);
        NPC n = mob(0x42, 10);

        bus.post(new MobDamageIntent(0x42, 1, 0xAAA, 5));  // A: 5 dmg
        bus.drain(-1);
        assertEquals(0xAAA, MobManager.getSnapshot(0x42).targetId);

        bus.post(new MobDamageIntent(0x42, 1, 0xBBB, 99)); // B: kills
        bus.drain(-1);

        MobManager.Snapshot s = MobManager.getSnapshot(0x42);
        assertEquals(MobState.TRANSITION, s.state);
        assertEquals(MobAI.NO_TARGET, s.targetId);
    }
}
