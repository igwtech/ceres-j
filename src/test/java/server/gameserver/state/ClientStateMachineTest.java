package server.gameserver.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Unit tests for {@link ClientStateMachine}. Covers the transition
 * log, the reliable-ack observation channel, the {@code onceAcked}
 * callback contract (registered + immediate-fire), and the u16 wrap-
 * aware seq comparison used to decide whether a new ack is newer
 * than the last one observed.
 *
 * <p>No Player, no Zone, no SQLite — just the FSM as a value object.
 */
public class ClientStateMachineTest {

    // ──────────────────────────── transitions ────────────────────

    @Test
    public void newFsmStartsInPreLogin() {
        ClientStateMachine fsm = new ClientStateMachine();
        assertEquals(ClientState.PRE_LOGIN, fsm.getState());
        assertEquals(-1, fsm.lastConfirmedReliableSeq());
        assertTrue(fsm.recentTransitions().isEmpty());
    }

    @Test
    public void transitionChangesStateAndLogs() {
        ClientStateMachine fsm = new ClientStateMachine();
        fsm.transition(ClientState.WORLDENTRY_BURST, "world-entry");
        assertEquals(ClientState.WORLDENTRY_BURST, fsm.getState());
        List<ClientStateMachine.Transition> log =
                fsm.recentTransitions();
        assertEquals(1, log.size());
        assertEquals(ClientState.PRE_LOGIN, log.get(0).from);
        assertEquals(ClientState.WORLDENTRY_BURST, log.get(0).to);
        assertEquals("world-entry", log.get(0).reason);
    }

    @Test
    public void idempotentTransitionIsNoLog() {
        ClientStateMachine fsm = new ClientStateMachine();
        fsm.transition(ClientState.IN_WORLD, "first");
        fsm.transition(ClientState.IN_WORLD, "redundant");
        // Only the first transition out of PRE_LOGIN logs.
        assertEquals(1, fsm.recentTransitions().size());
        assertEquals(ClientState.IN_WORLD, fsm.getState());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullTransitionRejected() {
        new ClientStateMachine().transition(null, "bad");
    }

    @Test
    public void logIsBoundedToCapacity() {
        ClientStateMachine fsm = new ClientStateMachine();
        // Toggle between two states to force more than CAPACITY
        // transitions and confirm the deque truncates the oldest.
        for (int i = 0; i < ClientStateMachine.TRANSITION_LOG_CAPACITY
                            + 5; i++) {
            ClientState next = (i & 1) == 0
                    ? ClientState.IN_WORLD
                    : ClientState.CROSS_PENDING_LOAD;
            fsm.transition(next, "i=" + i);
        }
        assertEquals(ClientStateMachine.TRANSITION_LOG_CAPACITY,
                fsm.recentTransitions().size());
    }

    @Test
    public void crossPendingPredicateClassifiesEachState() {
        assertFalse(ClientState.isCrossPending(ClientState.PRE_LOGIN));
        assertFalse(ClientState.isCrossPending(ClientState.IN_WORLD));
        assertTrue(ClientState.isCrossPending(
                ClientState.CROSS_PENDING_LOAD));
        assertTrue(ClientState.isCrossPending(
                ClientState.CROSS_PENDING_LOCATION));
        assertTrue(ClientState.isCrossPending(
                ClientState.CROSS_RECONNECTING));
        assertFalse(ClientState.isCrossPending(
                ClientState.DEAD_RESPAWNING));
    }

    // ──────────────────────────── reliable acks ──────────────────

    @Test
    public void observeReliableAckUpdatesLastConfirmed() {
        ClientStateMachine fsm = new ClientStateMachine();
        fsm.observeReliableAck(42);
        assertEquals(42, fsm.lastConfirmedReliableSeq());
    }

    @Test
    public void observeReliableAckDoesNotMoveBackwards() {
        // A flaky path can deliver acks out-of-order. Newer acks
        // advance the pointer; older ones must be ignored so we
        // don't unfire a Phase-2 callback that already triggered.
        ClientStateMachine fsm = new ClientStateMachine();
        fsm.observeReliableAck(100);
        fsm.observeReliableAck(50);
        assertEquals(100, fsm.lastConfirmedReliableSeq());
    }

    @Test
    public void observeReliableAckRespectsU16Wraparound() {
        // 0xfffe → 0x0003 is a forward step (5 across the wrap),
        // not a backward 65,531-step regression.
        ClientStateMachine fsm = new ClientStateMachine();
        fsm.observeReliableAck(0xfffe);
        fsm.observeReliableAck(0x0003);
        assertEquals(0x0003, fsm.lastConfirmedReliableSeq());
    }

    // ──────────────────────────── onceAcked ──────────────────────

    @Test
    public void onceAckedFiresWhenAckArrives() {
        ClientStateMachine fsm = new ClientStateMachine();
        AtomicInteger fired = new AtomicInteger();
        fsm.onceAcked(7, fired::incrementAndGet);
        assertEquals(0, fired.get());
        assertEquals(1, fsm.pendingCallbackCount());

        fsm.observeReliableAck(7);
        assertEquals(1, fired.get());
        assertEquals(0, fsm.pendingCallbackCount());
    }

    @Test
    public void onceAckedFiresImmediatelyIfAlreadyPast() {
        ClientStateMachine fsm = new ClientStateMachine();
        fsm.observeReliableAck(100);
        AtomicInteger fired = new AtomicInteger();
        fsm.onceAcked(50, fired::incrementAndGet);
        assertEquals(1, fired.get());
        assertEquals(0, fsm.pendingCallbackCount());
    }

    @Test
    public void onceAckedDoesNotFireForOlderSeq() {
        // The callback registers for seq 50 but only an ack for
        // seq 20 arrives — must NOT fire.
        ClientStateMachine fsm = new ClientStateMachine();
        AtomicInteger fired = new AtomicInteger();
        fsm.onceAcked(50, fired::incrementAndGet);
        fsm.observeReliableAck(20);
        assertEquals(0, fired.get());
        assertEquals(1, fsm.pendingCallbackCount());
        fsm.observeReliableAck(60);
        assertEquals(1, fired.get());
    }

    @Test
    public void multipleCallbacksAtSameSeqAllFire() {
        ClientStateMachine fsm = new ClientStateMachine();
        AtomicInteger fired = new AtomicInteger();
        fsm.onceAcked(10, fired::incrementAndGet);
        fsm.onceAcked(10, fired::incrementAndGet);
        fsm.onceAcked(10, fired::incrementAndGet);
        assertEquals(3, fsm.pendingCallbackCount());
        fsm.observeReliableAck(10);
        assertEquals(3, fired.get());
        assertEquals(0, fsm.pendingCallbackCount());
    }

    @Test
    public void cascadingCallbacksDoNotDeadlock() {
        // A callback that re-enters the machine (e.g. registers a
        // follow-up onceAcked) must NOT deadlock. The fsm runs
        // callbacks OUTSIDE its lock specifically to enable this.
        ClientStateMachine fsm = new ClientStateMachine();
        AtomicInteger fired = new AtomicInteger();
        fsm.onceAcked(5, () -> {
            fsm.transition(ClientState.IN_WORLD,
                    "cascading from ack-5");
            fired.incrementAndGet();
        });

        // Deadlock would manifest as a hang here. Junit's @Test
        // doesn't enforce a hard timeout without @Timeout, so we
        // rely on the test suite's wallclock instead.
        long start = System.nanoTime();
        fsm.observeReliableAck(5);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (elapsedMs > 1000) {
            fail("re-entrant callback hung for " + elapsedMs + "ms"
                    + " — likely deadlock on the FSM lock");
        }
        assertEquals(1, fired.get());
        assertEquals(ClientState.IN_WORLD, fsm.getState());
    }

    @Test(expected = IllegalArgumentException.class)
    public void onceAckedRejectsNullCallback() {
        new ClientStateMachine().onceAcked(0, null);
    }

    // ──────────────────────────── seqGteUnsigned helper ──────────

    @Test
    public void seqGteHandlesEquality() {
        assertTrue(ClientStateMachine.seqGteUnsigned(42, 42));
    }

    @Test
    public void seqGteHandlesSimpleForward() {
        assertTrue(ClientStateMachine.seqGteUnsigned(100, 50));
        assertFalse(ClientStateMachine.seqGteUnsigned(50, 100));
    }

    @Test
    public void seqGteHandlesForwardWrap() {
        // Forward across the wrap: 0x0003 is "ahead of" 0xfffe.
        assertTrue(ClientStateMachine.seqGteUnsigned(0x0003,
                0xfffe));
        // …and not the other way around.
        assertFalse(ClientStateMachine.seqGteUnsigned(0xfffe,
                0x0003));
    }

    @Test
    public void seqGteHandlesHalfWindowEdge() {
        // Distance exactly 0x8000 (half the seq space) — by
        // convention this counts as "behind" (RFC 1982 §3.2).
        assertFalse(ClientStateMachine.seqGteUnsigned(0x8000, 0));
        assertTrue(ClientStateMachine.seqGteUnsigned(0x7fff, 0));
    }

    // ──────────────────────────── timeInState ────────────────────

    @Test
    public void timeInStateMsNeverNegative() {
        ClientStateMachine fsm = new ClientStateMachine();
        long t = fsm.timeInStateMs();
        assertTrue("timeInStateMs must be ≥0, got " + t, t >= 0);
    }

    // ──────────────────────────── transitions snapshot ───────────

    @Test
    public void recentTransitionsReturnsDefensiveCopy() {
        ClientStateMachine fsm = new ClientStateMachine();
        fsm.transition(ClientState.IN_WORLD, "first");
        List<ClientStateMachine.Transition> log =
                fsm.recentTransitions();
        // Mutating the snapshot must not affect the FSM.
        log.clear();
        assertEquals(1, fsm.recentTransitions().size());
    }

    @Test
    public void transitionToStringIncludesFromToReason() {
        ClientStateMachine fsm = new ClientStateMachine();
        fsm.transition(ClientState.IN_WORLD, "test");
        String s = fsm.recentTransitions().get(0).toString();
        assertNotNull(s);
        assertTrue(s, s.contains("PRE_LOGIN"));
        assertTrue(s, s.contains("IN_WORLD"));
        assertTrue(s, s.contains("test"));
    }
}
