package server.gameserver.npc;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for the {@link MobAI} state machine. Each transition
 * in the table documented on the class gets at least one test.
 *
 * <p>These tests are pure-function — no Zone, no PlayerManager, no
 * bus. The AI is deliberately stateless so it can be unit-tested in
 * isolation; the surrounding harness (per-tick caller, input
 * builder) lives in {@link MobAITickTest}.
 */
public class MobAITest {

    private static final float AGGRO   = 20f;
    private static final float DEAGGRO = 30f;

    private static MobAI.PlayerSnapshot p(int uid, float x, float y, float z) {
        return new MobAI.PlayerSnapshot(uid, x, y, z);
    }

    private static MobAI.PlayerSnapshot dead(int uid, float x, float y, float z) {
        return new MobAI.PlayerSnapshot(uid, x, y, z, false);
    }

    private static MobAI.Input mob(MobState state, int target,
                                    MobAI.PlayerSnapshot... players) {
        return new MobAI.Input(0x42, 0f, 0f, 0f, state, target,
                Arrays.asList(players), AGGRO, DEAGGRO);
    }

    // ─── IDLE transitions ──────────────────────────────────────────

    @Test
    public void idleStaysIdleWithNoPlayers() {
        MobAI.Result r = MobAI.tick(mob(MobState.IDLE, MobAI.NO_TARGET));
        assertEquals(MobState.IDLE, r.newState);
        assertEquals(MobAI.NO_TARGET, r.newTargetId);
    }

    @Test
    public void idleStaysIdleWithPlayerOutsideAggroRange() {
        // Player at distance 25 (aggro=20, so just outside).
        MobAI.Result r = MobAI.tick(mob(MobState.IDLE, MobAI.NO_TARGET,
                p(1, 25f, 0f, 0f)));
        assertEquals(MobState.IDLE, r.newState);
    }

    @Test
    public void idleAggrosOnPlayerWithinAggroRange() {
        MobAI.Result r = MobAI.tick(mob(MobState.IDLE, MobAI.NO_TARGET,
                p(7, 10f, 0f, 0f)));
        assertEquals(MobState.COMBAT, r.newState);
        assertEquals(7, r.newTargetId);
    }

    @Test
    public void idleAggrosOnNearestPlayer() {
        // 3 players at distances 18, 5, 12 — nearest is uid=2 (5m).
        MobAI.Result r = MobAI.tick(mob(MobState.IDLE, MobAI.NO_TARGET,
                p(1, 18f, 0f, 0f),
                p(2, 5f, 0f, 0f),
                p(3, 12f, 0f, 0f)));
        assertEquals(MobState.COMBAT, r.newState);
        assertEquals(2, r.newTargetId);
    }

    @Test
    public void idleIgnoresDeadPlayers() {
        // Dead player inside aggro range; one alive at edge.
        MobAI.Result r = MobAI.tick(mob(MobState.IDLE, MobAI.NO_TARGET,
                dead(1, 5f, 0f, 0f),
                p(2, 19f, 0f, 0f)));
        assertEquals(MobState.COMBAT, r.newState);
        assertEquals("must skip dead player and pick the alive one",
                2, r.newTargetId);
    }

    // ─── COMBAT transitions ────────────────────────────────────────

    @Test
    public void combatHoldsTargetThatStaysWithinDeaggroRange() {
        // Target at 25m (outside aggro=20 but inside deaggro=30).
        MobAI.Result r = MobAI.tick(mob(MobState.COMBAT, 7,
                p(7, 25f, 0f, 0f)));
        assertEquals(MobState.COMBAT, r.newState);
        assertEquals(7, r.newTargetId);
    }

    @Test
    public void combatRetargetsWhenCurrentTargetGone() {
        // Old target uid=7 is missing; another player uid=9 in aggro.
        MobAI.Result r = MobAI.tick(mob(MobState.COMBAT, 7,
                p(9, 10f, 0f, 0f)));
        assertEquals(MobState.COMBAT, r.newState);
        assertEquals(9, r.newTargetId);
    }

    @Test
    public void combatRetargetsWhenTargetMovesPastDeaggro() {
        // uid=7 moved out to 35m (past deaggro=30); uid=9 within aggro.
        MobAI.Result r = MobAI.tick(mob(MobState.COMBAT, 7,
                p(7, 35f, 0f, 0f),
                p(9, 5f, 0f, 0f)));
        assertEquals(MobState.COMBAT, r.newState);
        assertEquals(9, r.newTargetId);
    }

    @Test
    public void combatRetargetsWhenTargetDies() {
        // uid=7 died; uid=9 alive in aggro.
        MobAI.Result r = MobAI.tick(mob(MobState.COMBAT, 7,
                dead(7, 5f, 0f, 0f),
                p(9, 10f, 0f, 0f)));
        assertEquals(MobState.COMBAT, r.newState);
        assertEquals(9, r.newTargetId);
    }

    @Test
    public void combatTransitionsWhenTargetGoneAndNoOneElseInAggro() {
        // Old target moved out, no other players in aggro range.
        MobAI.Result r = MobAI.tick(mob(MobState.COMBAT, 7,
                p(7, 35f, 0f, 0f)));
        assertEquals(MobState.TRANSITION, r.newState);
        assertEquals(MobAI.NO_TARGET, r.newTargetId);
    }

    @Test
    public void combatTransitionsWhenAllPlayersDead() {
        MobAI.Result r = MobAI.tick(mob(MobState.COMBAT, 7,
                dead(7, 5f, 0f, 0f)));
        assertEquals(MobState.TRANSITION, r.newState);
        assertEquals(MobAI.NO_TARGET, r.newTargetId);
    }

    @Test
    public void combatTransitionsWhenAlone() {
        MobAI.Result r = MobAI.tick(mob(MobState.COMBAT, 7));
        assertEquals(MobState.TRANSITION, r.newState);
    }

    // ─── TRANSITION transitions ────────────────────────────────────

    @Test
    public void transitionBacksToIdleWhenAlone() {
        MobAI.Result r = MobAI.tick(mob(MobState.TRANSITION, MobAI.NO_TARGET));
        assertEquals(MobState.IDLE, r.newState);
    }

    @Test
    public void transitionRecombatsWhenPlayerInAggro() {
        MobAI.Result r = MobAI.tick(mob(MobState.TRANSITION, MobAI.NO_TARGET,
                p(11, 10f, 0f, 0f)));
        assertEquals(MobState.COMBAT, r.newState);
        assertEquals(11, r.newTargetId);
    }

    // ─── Hysteresis (the core invariant) ───────────────────────────

    @Test
    public void hysteresisPreventsFlicker() {
        // Player parks at 25m — between aggro (20) and deaggro (30).
        // From IDLE → still IDLE (not in aggro yet).
        MobAI.Result r1 = MobAI.tick(mob(MobState.IDLE, MobAI.NO_TARGET,
                p(7, 25f, 0f, 0f)));
        assertEquals(MobState.IDLE, r1.newState);
        // From COMBAT → stays COMBAT (target still in deaggro).
        MobAI.Result r2 = MobAI.tick(mob(MobState.COMBAT, 7,
                p(7, 25f, 0f, 0f)));
        assertEquals(MobState.COMBAT, r2.newState);
        assertEquals(7, r2.newTargetId);
        // The same input parked at 25m therefore produces DIFFERENT
        // outputs depending on whether the mob was previously idle
        // or in combat — which is exactly what hysteresis means.
    }

    // ─── Rare states — they behave as IDLE entry points ─────────────

    @Test
    public void rare72IsIdleLikeForAggro() {
        MobAI.Result r = MobAI.tick(mob(MobState.RARE_72, MobAI.NO_TARGET,
                p(1, 5f, 0f, 0f)));
        assertEquals(MobState.COMBAT, r.newState);
    }

    @Test
    public void rare6fIsIdleLikeForAggro() {
        MobAI.Result r = MobAI.tick(mob(MobState.RARE_6F, MobAI.NO_TARGET));
        assertEquals(MobState.IDLE, r.newState);
    }

    // ─── Edge cases & input validation ──────────────────────────────

    @Test
    public void nullInputReturnsSafeIdle() {
        MobAI.Result r = MobAI.tick(null);
        assertEquals(MobState.IDLE, r.newState);
        assertEquals(MobAI.NO_TARGET, r.newTargetId);
    }

    @Test
    public void rejectsDeaggroSmallerThanAggro() {
        try {
            new MobAI.Input(1, 0f, 0f, 0f, MobState.IDLE, MobAI.NO_TARGET,
                    Collections.emptyList(), 30f, 20f);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void aggroEqualToDeaggroIsAllowed() {
        // Allowed (no hysteresis but still well-defined behaviour).
        new MobAI.Input(1, 0f, 0f, 0f, MobState.IDLE, MobAI.NO_TARGET,
                Collections.emptyList(), 20f, 20f);
    }

    @Test
    public void distanceUses3DEuclidean() {
        // Player at (3, 4, 0) is exactly 5m away from origin.
        MobAI.Result r = MobAI.tick(new MobAI.Input(0x42, 0f, 0f, 0f,
                MobState.IDLE, MobAI.NO_TARGET,
                Arrays.asList(p(1, 3f, 4f, 0f)),
                4.5f, 6f));
        // Inside deaggro=6 but outside aggro=4.5 — should NOT aggro.
        assertEquals(MobState.IDLE, r.newState);

        MobAI.Result r2 = MobAI.tick(new MobAI.Input(0x42, 0f, 0f, 0f,
                MobState.IDLE, MobAI.NO_TARGET,
                Arrays.asList(p(1, 3f, 4f, 0f)),
                5.5f, 6f));
        // Same player, larger aggro=5.5 > 5 — should aggro.
        assertEquals(MobState.COMBAT, r2.newState);
    }

    @Test
    public void emptyPlayerListCoercesToEmpty() {
        MobAI.Input in = new MobAI.Input(0x42, 0f, 0f, 0f,
                MobState.COMBAT, 7, null, AGGRO, DEAGGRO);
        // Null list must coerce to empty without NPE.
        MobAI.Result r = MobAI.tick(in);
        assertEquals(MobState.TRANSITION, r.newState);
    }

    @Test
    public void noTargetSentinelMatchesDecoder() {
        // The AI must use the same NO_TARGET constant as the decoder
        // so consumers can compare straight with intent.targetId.
        assertEquals(MobDataDecoder.NO_TARGET, MobAI.NO_TARGET);
    }

    @Test
    public void resultHelpersWorkCorrectly() {
        MobAI.Result r = MobAI.tick(mob(MobState.IDLE, MobAI.NO_TARGET,
                p(1, 5f, 0f, 0f)));
        assertTrue(r.stateChanged(MobState.IDLE));
        assertFalse(r.stateChanged(MobState.COMBAT));
        assertTrue(r.targetChanged(MobAI.NO_TARGET));
        assertFalse(r.targetChanged(1));
    }

    @Test
    public void emptyPlayerListExplicitDoesNotThrow() {
        MobAI.Result r = MobAI.tick(new MobAI.Input(1, 0f, 0f, 0f,
                MobState.IDLE, MobAI.NO_TARGET,
                Collections.<MobAI.PlayerSnapshot>emptyList(),
                AGGRO, DEAGGRO));
        assertEquals(MobState.IDLE, r.newState);
    }

    @Test
    public void nullCurrentStateCoercesToIdle() {
        MobAI.Input in = new MobAI.Input(1, 0f, 0f, 0f, null, MobAI.NO_TARGET,
                Collections.<MobAI.PlayerSnapshot>emptyList(),
                AGGRO, DEAGGRO);
        assertEquals(MobState.IDLE, in.currentState);
    }
}
