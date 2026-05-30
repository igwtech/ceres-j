package server.gameserver.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.client_udp.ReliableAck08;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Integration test that drives a {@link ClientStateMachine}
 * through the full lifecycle a real session would traverse:
 *
 * <pre>
 *   PRE_LOGIN
 *     → WORLDENTRY_BURST   (server begins streaming login burst)
 *       → IN_WORLD          (burst finished, gameplay tick)
 *         → CROSS_PENDING_LOAD       (UseItem portal click)
 *           → CROSS_PENDING_LOCATION (PortalCrossCommitEvent fires)
 *             → IN_WORLD              (post-cross WorldEntryEvent)
 * </pre>
 *
 * <p>This wires together the observation hooks added in tasks #239
 * and #243 + verifies the transition order matches what the
 * documentation in {@code server-lacks-client-state-machine}
 * memory describes. Subsequent Phase 2 (ACK-gated commit, #242)
 * builds on this trajectory.
 *
 * <p>Distinct from {@link ClientStateMachineTest}, which exercises
 * the machine in isolation as a value object; this test models
 * the trajectory the production code is supposed to produce.
 */
public class ClientStateMachineIntegrationTest {

    /** Capture every transition for assertion ordering. */
    private static List<ClientState> stateSequence(
            ClientStateMachine fsm) {
        List<ClientState> seq = new ArrayList<>();
        seq.add(ClientState.PRE_LOGIN); // initial
        for (ClientStateMachine.Transition t :
                fsm.recentTransitions()) {
            seq.add(t.to);
        }
        return seq;
    }

    @Test
    public void fullSessionLifecycleTransitions() {
        ClientStateMachine fsm = new ClientStateMachine();

        // PRE_LOGIN → WORLDENTRY_BURST: WorldEntryEvent.execute
        // (production code wired in WorldEntryEvent.java).
        fsm.transition(ClientState.WORLDENTRY_BURST,
                "WorldEntryEvent: begin");

        // WORLDENTRY_BURST → IN_WORLD: same event's exit hook.
        fsm.transition(ClientState.IN_WORLD,
                "WorldEntryEvent: complete");

        // IN_WORLD → CROSS_PENDING_LOAD: UseItem portal branch
        // (production code wired in UseItem.java after Packet830D).
        fsm.transition(ClientState.CROSS_PENDING_LOAD,
                "UseItem portal: 0x83/0x0d emitted");

        // CROSS_PENDING_LOAD → CROSS_PENDING_LOCATION:
        // PortalCrossCommitEvent (470ms later, after Location is
        // sent — currently fires on timer; Phase 2 will gate this
        // on the reliable ACK).
        fsm.transition(ClientState.CROSS_PENDING_LOCATION,
                "PortalCrossCommit fired (T+470ms)");

        // CROSS_PENDING_LOCATION → IN_WORLD: post-cross
        // WorldEntryEvent on the destination zone reconnect.
        fsm.transition(ClientState.WORLDENTRY_BURST,
                "WorldEntryEvent: begin (cross reconnect)");
        fsm.transition(ClientState.IN_WORLD,
                "WorldEntryEvent: complete (cross reconnect)");

        List<ClientState> expected = List.of(
                ClientState.PRE_LOGIN,
                ClientState.WORLDENTRY_BURST,
                ClientState.IN_WORLD,
                ClientState.CROSS_PENDING_LOAD,
                ClientState.CROSS_PENDING_LOCATION,
                ClientState.WORLDENTRY_BURST,
                ClientState.IN_WORLD);
        assertEquals(expected, stateSequence(fsm));
        assertEquals(ClientState.IN_WORLD, fsm.getState());
    }

    @Test
    public void walkingCrossLifecycleTransitions() {
        // Walking cross (Zoning1 path): client sends Zoning1 →
        // server transitions to CROSS_PENDING_LOAD, schedules
        // SZoning1ConfirmEvent → confirm fires +450ms with the
        // commit → CROSS_PENDING_LOCATION. Then the client
        // reconnects and WorldEntryEvent on the destination
        // brings the FSM back to IN_WORLD.
        ClientStateMachine fsm = new ClientStateMachine();
        fsm.transition(ClientState.WORLDENTRY_BURST,
                "initial login");
        fsm.transition(ClientState.IN_WORLD, "initial login done");

        fsm.transition(ClientState.CROSS_PENDING_LOAD,
                "Zoning1: walking-cross to zone 946");
        fsm.transition(ClientState.CROSS_PENDING_LOCATION,
                "SZoning1Confirm: committed walking-cross");

        // Post-cross reconnect re-enters the WorldEntry path.
        fsm.transition(ClientState.WORLDENTRY_BURST,
                "cross-reconnect");
        fsm.transition(ClientState.IN_WORLD,
                "cross-reconnect done");

        assertEquals(ClientState.IN_WORLD, fsm.getState());
        // 6 explicit transitions are logged (each transition() out
        // of a distinct state adds one entry).
        assertEquals(6, fsm.recentTransitions().size());
    }

    @Test
    public void crossPendingPredicateMatchesObservation() {
        // The isCrossPending() classifier is what production code
        // (e.g. Phase 2 admin dashboard, future #233 inventory
        // suppress-during-cross logic) will use to skip work
        // while a cross is in flight. Verify it agrees with the
        // observation-only transitions we just performed.
        ClientStateMachine fsm = new ClientStateMachine();
        fsm.transition(ClientState.WORLDENTRY_BURST, "start");
        fsm.transition(ClientState.IN_WORLD, "world");
        assertTrue("IN_WORLD must NOT be cross-pending",
                !ClientState.isCrossPending(fsm.getState()));

        fsm.transition(ClientState.CROSS_PENDING_LOAD, "portal");
        assertTrue("CROSS_PENDING_LOAD must be cross-pending",
                ClientState.isCrossPending(fsm.getState()));

        fsm.transition(ClientState.CROSS_PENDING_LOCATION, "commit");
        assertTrue("CROSS_PENDING_LOCATION must be cross-pending",
                ClientState.isCrossPending(fsm.getState()));

        fsm.transition(ClientState.IN_WORLD, "post-cross");
        assertTrue("IN_WORLD post-cross must NOT be cross-pending",
                !ClientState.isCrossPending(fsm.getState()));
    }

    @Test
    public void onceAckedDuringCrossFiresAfterFsmAdvances() {
        // Phase 2 staging test: register an onceAcked callback
        // when the cross enters CROSS_PENDING_LOCATION; it should
        // fire when the client acks the seq we registered. This
        // is the contract Phase 2 (#242) will use to replace the
        // 470ms timer.
        Player pl = PacketTestFixture.newPlayer();
        ClientStateMachine fsm = pl.getStateMachine();
        assertNotNull(fsm);

        // Simulate the production path's transitions so far.
        fsm.transition(ClientState.WORLDENTRY_BURST, "login");
        fsm.transition(ClientState.IN_WORLD, "login done");
        fsm.transition(ClientState.CROSS_PENDING_LOAD, "portal");
        fsm.transition(ClientState.CROSS_PENDING_LOCATION,
                "commit");

        // Register a Phase-2-style gate: fire `done` when seq 100
        // is acknowledged.
        boolean[] gateFired = new boolean[1];
        fsm.onceAcked(100, () -> gateFired[0] = true);
        assertEquals(1, fsm.pendingCallbackCount());

        // Real production: ReliableAck08 from the client decodes
        // [03 xx xx 08 (acked-1) LE2]. wire-99 + 1 = 100.
        byte[] ackFrame = new byte[]{
                0x03, 0x00, 0x00, 0x08,
                (byte) 99, 0x00};
        new ReliableAck08(ackFrame).execute(pl);

        assertTrue("Phase-2 gate must fire after the matching ack",
                gateFired[0]);
        assertEquals("FSM ack pointer must reflect the ack",
                100, fsm.lastConfirmedReliableSeq());
    }
}
